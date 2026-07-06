/*
 * Copyright (C) 2023 Korea Association of AI Smart Home.
 * Copyright (C) 2023 KyungDong Navien Co, Ltd.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package kr.or.kashi.hde.session;

import android.util.Log;

import java.io.File;
import java.io.IOException;
import java.io.RandomAccessFile;
import java.util.Arrays;

/**
 * Plain /dev/tty* based network session for boards where the vendor UartSched
 * library is a compile-time stub or not usable from an app process.
 *
 * This class intentionally does not touch UartSchedSession. It only provides an
 * alternate INTERNAL-port implementation through the same NetworkSessionAdapter
 * interface used by USB/UartSched sessions.
 */
public class DirectTtyNetworkSession extends NetworkSessionAdapter {
    private static final String TAG = "DirectTtySession";
    private static final int READ_BUFFER_SIZE = 512;
    private static final int WRITE_DELAY_MS = 10;

    private final String mPortName;
    private final int mPortSpeed;

    private RandomAccessFile mTty;
    private Thread mReadThread;
    private volatile boolean mRunning;
    private boolean mBoardDriverRs485Configured;
    private Rs485DirectionControl mRs485DirectionControl;

    public DirectTtyNetworkSession(String name, int speed) {
        mPortName = name;
        mPortSpeed = speed;
    }

    @Override
    public boolean onOpen() {
        File portFile = new File(mPortName);
        Log.d(TAG, "open requested: " + mPortName
                + " exists=" + portFile.exists()
                + ", canRead=" + portFile.canRead()
                + ", canWrite=" + portFile.canWrite()
                + ", speed=" + mPortSpeed);

        if (!portFile.exists()) {
            Log.e(TAG, "port does not exist: " + mPortName);
            return false;
        }

        configurePortByStty(mPortName, mPortSpeed);

        try {
            mTty = new RandomAccessFile(portFile, "rw");
        } catch (IOException e) {
            Log.e(TAG, "open failed: " + mPortName, e);
            closeQuietly();
            return false;
        }

        configureBoardRs485IfNeeded();

        mRunning = true;
        mReadThread = new Thread(this::readLoop, "DirectTty-RX-" + portFile.getName());
        mReadThread.start();

        Log.d(TAG, "opened internal UART port " + mPortName + " " + mPortSpeed + " by DirectTtyNetworkSession");
        return true;
    }

    @Override
    public void onClose() {
        mRunning = false;

        // Closing the fd first unblocks a pending RandomAccessFile.read() on tty devices.
        closeQuietly();

        if (mReadThread != null) {
            mReadThread.interrupt();
            try {
                mReadThread.join(300);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
            mReadThread = null;
        }

        if (mRs485DirectionControl != null) {
            mRs485DirectionControl.close();
            mRs485DirectionControl = null;
        }
        mBoardDriverRs485Configured = false;

        Log.d(TAG, "closed internal UART port " + mPortName);
    }

    @Override
    public void onWrite(byte[] b) {
        if (b == null || b.length == 0) return;

        RandomAccessFile tty = mTty;
        if (tty == null) {
            Log.e(TAG, "write failed: port is not opened");
            return;
        }

        try {
            Thread.sleep(WRITE_DELAY_MS);
            synchronized (this) {
                Rs485DirectionControl directionControl = mBoardDriverRs485Configured
                        ? null : mRs485DirectionControl;
                if (directionControl != null) {
                    directionControl.beforeTx();
                }
                tty.write(b);
                if (directionControl != null) {
                    directionControl.afterTx(b.length, mPortSpeed);
                }
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            Log.w(TAG, "write interrupted");
        } catch (IOException e) {
            Log.e(TAG, "write failed: " + mPortName, e);
        }
    }

    private void configureBoardRs485IfNeeded() {
        if (!BoardUartRs485Configurator.isBoardRs485Port(mPortName)) {
            return;
        }

        mBoardDriverRs485Configured = BoardUartRs485Configurator.configure(mPortName);
        if (mBoardDriverRs485Configured) {
            Log.d(TAG, "board RS485 driver direction control enabled: " + mPortName);
            return;
        }

        Log.w(TAG, "board RS485 ioctl setup failed; fallback to manual GPIO direction control: "
                + mPortName);
        mRs485DirectionControl = Rs485DirectionControl.createIfNeeded(mPortName);
        if (mRs485DirectionControl != null && !mRs485DirectionControl.open()) {
            Log.w(TAG, "manual RS485 direction GPIO setup failed; TX may not leave the bus: "
                    + mPortName);
            mRs485DirectionControl = null;
        }
    }

    private void readLoop() {
        byte[] buffer = new byte[READ_BUFFER_SIZE];
        while (mRunning) {
            RandomAccessFile tty = mTty;
            if (tty == null) break;

            try {
                int readLen = tty.read(buffer);
                if (readLen > 0) {
                    byte[] data = Arrays.copyOf(buffer, readLen);
                    putData(data);
                } else if (readLen < 0) {
                    Thread.sleep(10);
                }
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                break;
            } catch (IOException e) {
                if (mRunning) {
                    Log.e(TAG, "read failed: " + mPortName, e);
                }
                break;
            }
        }
        Log.d(TAG, "read thread exited: " + mPortName);
    }

    private void closeQuietly() {
        RandomAccessFile tty = mTty;
        mTty = null;
        if (tty != null) {
            try {
                tty.close();
            } catch (IOException e) {
                Log.w(TAG, "close failed: " + mPortName, e);
            }
        }
    }

    private static void configurePortByStty(String portName, int speed) {
        String[] sttyPaths = new String[] { "/system/bin/stty", "/vendor/bin/stty", "stty" };
        for (String stty : sttyPaths) {
            try {
                Process process = new ProcessBuilder(
                        stty, "-F", portName,
                        String.valueOf(speed),
                        "cs8", "-cstopb", "-parenb",
                        "raw", "-echo", "-echoe", "-echok", "-ixon", "-ixoff")
                        .redirectErrorStream(true)
                        .start();
                int exit = process.waitFor();
                if (exit == 0) {
                    Log.d(TAG, "UART configured: " + portName + " " + speed + " by " + stty);
                    return;
                }
                Log.w(TAG, "stty failed: path=" + stty + ", exit=" + exit);
            } catch (Exception e) {
                Log.w(TAG, "stty unavailable: " + stty + " for " + portName + ", " + e.getMessage());
            }
        }
        Log.w(TAG, "UART stty configuration failed; try direct open anyway: " + portName + " " + speed);
    }
}
