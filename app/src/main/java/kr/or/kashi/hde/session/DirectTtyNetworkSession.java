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

    private int mNativeFd = -1;
    private Thread mReadThread;
    private volatile boolean mRunning;

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

        mNativeFd = NativeTtyPort.open(mPortName, mPortSpeed);
        if (mNativeFd < 0) {
            Log.e(TAG, "native open/configure failed: " + mPortName);
            closeQuietly();
            return false;
        }

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

        Log.d(TAG, "closed internal UART port " + mPortName);
    }

    @Override
    public void onWrite(byte[] b) {
        if (b == null || b.length == 0) return;

        if (mNativeFd < 0) {
            Log.e(TAG, "write failed: port is not opened");
            return;
        }

        try {
            Thread.sleep(WRITE_DELAY_MS);
            synchronized (this) {
                int written = NativeTtyPort.write(mNativeFd, b, b.length);
                if (written != b.length) {
                    Log.e(TAG, "native write incomplete: " + mPortName
                            + " written=" + written + " expected=" + b.length);
                }
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            Log.w(TAG, "write interrupted");
        }
    }

    private void readLoop() {
        byte[] buffer = new byte[READ_BUFFER_SIZE];
        while (mRunning) {
            int fd = mNativeFd;
            if (fd < 0) break;

            int readLen = NativeTtyPort.read(fd, buffer, buffer.length, 200);
            if (readLen > 0) {
                byte[] data = Arrays.copyOf(buffer, readLen);
                putData(data);
            } else if (readLen < 0) {
                if (mRunning) {
                    Log.e(TAG, "native read failed: " + mPortName);
                }
                break;
            }
        }
        Log.d(TAG, "read thread exited: " + mPortName);
    }

    private void closeQuietly() {
        int fd = mNativeFd;
        mNativeFd = -1;
        if (fd >= 0) {
            NativeTtyPort.close(fd);
        }
    }


}
