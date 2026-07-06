package kr.or.kashi.hde;

import android.content.Context;
import android.os.Handler;
import android.util.Log;

import java.io.Closeable;
import java.io.File;
import java.io.RandomAccessFile;
import java.io.IOException;
import java.util.Arrays;

/**
 * Keeps the board-MCU watchdog disabled through /dev/ttyS5.
 *
 * Site-Hub <-> T5 TTL-232 frame used by the working board app:
 *   STX, STX, STX, OP-code, DATA, BCC, ETX
 *   BCC = OP-code xor DATA
 *
 * Watchdog disable command:
 *   02 02 02 55 00 55 03
 *
 * Guide LED all ON diagnostic command:
 *   02 02 02 B1 F1 40 03
 *
 * NOTE:
 *   Do not reuse UartSchedSession here. That class is left unchanged for the
 *   existing app path, but on this target the bundled UartSched jar is a stub and
 *   throws RuntimeException("Stub!") at construction. The MCU watchdog path uses
 *   a separate direct tty read/write implementation. The existing UartSchedSession
 *   remains untouched for the normal app communication path.
 */
public final class McuWatchdogDisabler {
    private static final String TAG = "McuWatchdogDisabler";

    private static final String WATCHDOG_UART_PORT = "/dev/ttyS5";
    private static final int WATCHDOG_UART_BAUD = 115200;
    private static final long WATCHDOG_REOPEN_DELAY_MS = 5_000L;

    private static final byte STX = 0x02;
    private static final byte OP_WATCHDOG = 0x55;
    private static final byte OP_GUIDE_LED = (byte) 0xB1;
    private static final byte DATA_DISABLE_WATCHDOG = 0x00;
    private static final byte DATA_GUIDE_LED_ALL_ON = (byte) 0xF1;
    private static final byte ETX = 0x03;

    private static final byte[] WATCHDOG_DISABLE_PACKET = makeMcuPacket(
            OP_WATCHDOG, DATA_DISABLE_WATCHDOG);

    private static final byte[] GUIDE_LED_ALL_ON_PACKET = makeMcuPacket(
            OP_GUIDE_LED, DATA_GUIDE_LED_ALL_ON);


    private static byte[] makeMcuPacket(byte opCode, byte data) {
        return new byte[] {
                STX,
                STX,
                STX,
                opCode,
                data,
                (byte) (opCode ^ data),
                ETX
        };
    }

    private static final Object sLock = new Object();
    private static boolean sWorkerStarted;

    private McuWatchdogDisabler() {
    }

    public static void sendOnceOnAppStart(Context context, Handler handler) {
        synchronized (sLock) {
            if (sWorkerStarted) {
                Log.d(TAG, "watchdog worker already running");
                return;
            }
            sWorkerStarted = true;
        }

        final Context appContext = context.getApplicationContext();
        Thread thread = new Thread(() -> runWorker(appContext), "MCU-Watchdog-Off");
        thread.start();
    }

    private static void runWorker(Context context) {
        while (true) {
            File portFile = new File(WATCHDOG_UART_PORT);
            Log.d(TAG, "watchdog UART candidate " + WATCHDOG_UART_PORT
                    + " exists=" + portFile.exists()
                    + ", canRead=" + portFile.canRead()
                    + ", canWrite=" + portFile.canWrite());

            if (!portFile.exists()) {
                Log.e(TAG, "watchdog UART port does not exist: " + WATCHDOG_UART_PORT);
                sleepQuietly(WATCHDOG_REOPEN_DELAY_MS);
                continue;
            }

            McuUartPort port = null;
            try {
                port = McuUartPort.open(context, WATCHDOG_UART_PORT, WATCHDOG_UART_BAUD);
                if (port == null) {
                    Log.e(TAG, "watchdog UART open failed: " + WATCHDOG_UART_PORT
                            + ", " + WATCHDOG_UART_BAUD + "bps");
                    sleepQuietly(WATCHDOG_REOPEN_DELAY_MS);
                    continue;
                }

                Log.d(TAG, "watchdog UART opened: " + port.describe());

                runRxThread(port);

                writePacket(port, GUIDE_LED_ALL_ON_PACKET, "guide-led-all-on startup TX");
                sleepQuietly(50L);
                writePacket(port, WATCHDOG_DISABLE_PACKET, "watchdog-disable startup TX");
                runTxLoop(port);
            } catch (Throwable e) {
                Log.e(TAG, "watchdog UART worker failed; reopen after "
                        + WATCHDOG_REOPEN_DELAY_MS + "ms", e);
            } finally {
                closeQuietly(port);
                sleepQuietly(WATCHDOG_REOPEN_DELAY_MS);
            }
        }
    }

    private static void runTxLoop(McuUartPort port) throws IOException {
        long lastIdleLog = System.currentTimeMillis();

        while (port.isOpen()) {
            long now = System.currentTimeMillis();
            if (now - lastIdleLog >= 10_000L) {
                Log.d(TAG, "watchdog UART alive/rx-wait idle: " + port.describe());
                lastIdleLog = now;
            }
            sleepQuietly(100L);
        }
    }

    private static void runRxThread(McuUartPort port) {
        Thread thread = new Thread(() -> {
            byte[] readBuffer = new byte[128];
            byte[] frameBuffer = new byte[256];
            int frameLength = 0;

            while (port.isOpen()) {
                try {
                    int read = port.read(readBuffer, 0, readBuffer.length);
                    if (read <= 0) {
                        sleepQuietly(20L);
                        continue;
                    }

                    byte[] rx = Arrays.copyOf(readBuffer, read);
                    Log.d(TAG, "watchdog UART RX(" + WATCHDOG_UART_PORT + ", "
                            + WATCHDOG_UART_BAUD + "): " + bytesToHex(rx));

                    for (int i = 0; i < read; i++) {
                        int value = readBuffer[i] & 0xFF;
                        if (frameLength == 0 && value != (STX & 0xFF)) {
                            continue;
                        }

                        if (frameLength >= frameBuffer.length) {
                            Log.w(TAG, "watchdog UART frame buffer overflow; drop partial frame");
                            frameLength = 0;
                        }

                        frameBuffer[frameLength++] = readBuffer[i];
                        if (value == (ETX & 0xFF)) {
                            byte[] frame = Arrays.copyOf(frameBuffer, frameLength);
                            handleMcuFrame(port, frame);
                            frameLength = 0;
                        }
                    }
                } catch (Throwable e) {
                    if (port.isOpen()) {
                        Log.e(TAG, "watchdog UART RX thread failed", e);
                        port.markClosed();
                    }
                    break;
                }
            }
        }, "MCU-Watchdog-RX");
        thread.start();
    }

    private static void handleMcuFrame(McuUartPort port, byte[] frame) throws IOException {
        Log.d(TAG, "watchdog UART RX-FRAME(" + WATCHDOG_UART_PORT + "): "
                + bytesToHex(frame));

        if (isValidMcuFrame(frame) && frame[3] == OP_WATCHDOG) {
            writePacket(port, frame, "watchdog-echo TX");
            return;
        }

        Log.w(TAG, "watchdog UART ignored non-watchdog/invalid frame: " + bytesToHex(frame));
    }


    private static boolean isValidMcuFrame(byte[] frame) {
        return frame.length == 7
                && frame[0] == STX
                && frame[1] == STX
                && frame[2] == STX
                && frame[6] == ETX
                && frame[5] == (byte) (frame[3] ^ frame[4]);
    }

    private static void writePacket(McuUartPort port, byte[] packet, String label) throws IOException {
        int written = port.write(packet, 0, packet.length);
        Log.d(TAG, label + " write bytes=" + written + ", via=" + port.getOpenMode());
        Log.d(TAG, label + "(" + WATCHDOG_UART_PORT + ", "
                + WATCHDOG_UART_BAUD + "): " + bytesToHex(packet));
    }

    private static void closeQuietly(Closeable closeable) {
        if (closeable == null) return;
        try {
            closeable.close();
        } catch (IOException | RuntimeException ignored) {
        }
    }

    private static void sleepQuietly(long millis) {
        try {
            Thread.sleep(millis);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    private static String bytesToHex(byte[] data) {
        StringBuilder builder = new StringBuilder();
        for (int i = 0; i < data.length; i++) {
            if (i > 0) builder.append(' ');
            builder.append(String.format("%02X", data[i] & 0xFF));
        }
        return builder.toString();
    }

    private static final class McuUartPort implements Closeable {
        private final String mPortName;
        private final int mBaudRate;
        private final RandomAccessFile mTty;
        private volatile boolean mOpen = true;

        private McuUartPort(String portName, int baudRate, RandomAccessFile tty) {
            mPortName = portName;
            mBaudRate = baudRate;
            mTty = tty;
        }

        static McuUartPort open(Context context, String portName, int baudRate) {
            try {
                configureByStty(portName, baudRate);

                /*
                 * Do not use android.hardware.SerialManager or UartSched here.
                 * - SerialManager requires signature permission android.permission.SERIAL_PORT.
                 * - The bundled UartSched jar in this project is a build-time stub and throws
                 *   RuntimeException("Stub!") when constructed.
                 *
                 * Open the tty as read/write, not write-only. Some board UART drivers do not
                 * enable the hardware path correctly for a write-only Java stream, and RX can
                 * never be observed from FileOutputStream. RandomAccessFile("rw") gives one fd
                 * for both TX and RX while keeping the existing UartSchedSession untouched.
                 */
                RandomAccessFile tty = new RandomAccessFile(portName, "rw");
                Log.d(TAG, "watchdog UART direct tty rw opened: " + portName + " "
                        + baudRate + "bps");
                return new McuUartPort(portName, baudRate, tty);
            } catch (Throwable e) {
                Log.e(TAG, "watchdog UART direct tty rw open failed", e);
                return null;
            }
        }

        private static void configureByStty(String portName, int baudRate) {
            String[] candidates = new String[] { "/system/bin/stty", "/vendor/bin/stty", "stty" };
            for (String stty : candidates) {
                try {
                    Process process = new ProcessBuilder(stty, "-F", portName,
                            String.valueOf(baudRate), "cs8", "-parenb", "-cstopb",
                            "raw", "-echo", "-echoe", "-echok", "-ixon", "-ixoff", "-crtscts")
                            .redirectErrorStream(true)
                            .start();
                    int exit = process.waitFor();
                    if (exit == 0) {
                        Log.d(TAG, "watchdog UART configured: " + portName + " "
                                + baudRate + " 8N1 raw by " + stty);
                        return;
                    }
                    Log.w(TAG, "stty failed: " + stty + " exit=" + exit);
                } catch (Throwable e) {
                    Log.w(TAG, "stty unavailable: " + stty + ", " + e.getMessage());
                }
            }
        }

        boolean isOpen() {
            return mOpen;
        }

        void markClosed() {
            mOpen = false;
        }

        String getOpenMode() {
            return "DirectTtyRw";
        }

        String describe() {
            return mPortName + ", " + mBaudRate + "bps, mode=" + getOpenMode();
        }

        int read(byte[] buffer, int offset, int length) throws IOException {
            if (!mOpen) return -1;
            return mTty.read(buffer, offset, length);
        }

        int write(byte[] packet, int offset, int length) throws IOException {
            if (!mOpen) throw new IOException("serial port is closed");
            mTty.write(packet, offset, length);
            return length;
        }

        @Override
        public void close() throws IOException {
            mOpen = false;
            mTty.close();
        }
    }

}
