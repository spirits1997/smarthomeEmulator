/*
 * Copyright (C) 2026 KOCOM
 *
 * RS-485 direction control helper for internal UART ports.
 */

package kr.or.kashi.hde.session;

import android.util.Log;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;

/**
 * Controls an external RS-485 DE/RE direction GPIO for direct /dev/tty* sessions.
 *
 * The KOCOM wallpad routes /dev/ttyAS5 through an RS-485 transceiver whose
 * direction pin is PE8.  In the legacy wallpad service this was mapped as:
 *     if (strcmp(device, "/dev/ttyAS5") == 0) return 136; // PE8
 *
 * Keep this mapping centralized here instead of scattering hard-coded GPIO
 * numbers in the TX path.
 */
final class Rs485DirectionControl {
    private static final String TAG = "Rs485Direction";

    private static final String WALLPAD_RS485_PORT = "/dev/ttyAS5";
    private static final int WALLPAD_RS485_GPIO_PE8 = 136;

    private static final int RS485_TX_ACTIVE_VALUE = 1;
    private static final int RS485_RX_IDLE_VALUE = 0;

    private static final int PRE_TX_DELAY_MS = 1;
    private static final int POST_TX_MARGIN_MS = 5;
    private static final int BITS_PER_SERIAL_BYTE = 11; // start + 8 data + parity/stop margin

    private final String mPortName;
    private final int mGpioNumber;
    private final File mGpioDir;
    private boolean mReady;

    private Rs485DirectionControl(String portName, int gpioNumber) {
        mPortName = portName;
        mGpioNumber = gpioNumber;
        mGpioDir = new File("/sys/class/gpio/gpio" + gpioNumber);
    }

    static Rs485DirectionControl createIfNeeded(String portName) {
        if (WALLPAD_RS485_PORT.equals(portName)) {
            return new Rs485DirectionControl(portName, WALLPAD_RS485_GPIO_PE8);
        }
        return null;
    }

    boolean open() {
        if (!ensureGpioExported()) {
            Log.e(TAG, "RS485 direction GPIO export failed: port=" + mPortName
                    + ", gpio=" + mGpioNumber);
            return false;
        }

        if (!writeText(new File(mGpioDir, "direction"), "out")) {
            Log.e(TAG, "RS485 direction GPIO direction setup failed: port=" + mPortName
                    + ", gpio=" + mGpioNumber);
            return false;
        }

        mReady = setRxIdle();
        if (mReady) {
            Log.d(TAG, "RS485 direction GPIO enabled: port=" + mPortName
                    + ", gpio=" + mGpioNumber + "(PE8), tx=" + RS485_TX_ACTIVE_VALUE
                    + ", rx=" + RS485_RX_IDLE_VALUE);
        }
        return mReady;
    }

    void close() {
        if (mReady) {
            setRxIdle();
        }
        mReady = false;
    }

    void beforeTx() {
        if (!mReady) return;
        if (!writeValue(RS485_TX_ACTIVE_VALUE)) {
            Log.w(TAG, "RS485 direction set TX failed: gpio=" + mGpioNumber);
            return;
        }
        sleepQuietly(PRE_TX_DELAY_MS);
    }

    void afterTx(int byteCount, int baudRate) {
        if (!mReady) return;
        sleepQuietly(calculateTxDoneDelayMs(byteCount, baudRate));
        if (!writeValue(RS485_RX_IDLE_VALUE)) {
            Log.w(TAG, "RS485 direction set RX failed: gpio=" + mGpioNumber);
        }
    }

    private boolean setRxIdle() {
        return writeValue(RS485_RX_IDLE_VALUE);
    }

    private boolean writeValue(int value) {
        return writeText(new File(mGpioDir, "value"), String.valueOf(value));
    }

    private int calculateTxDoneDelayMs(int byteCount, int baudRate) {
        int speed = baudRate > 0 ? baudRate : 9600;
        int bytes = Math.max(byteCount, 1);
        long txTimeMs = ((long) bytes * BITS_PER_SERIAL_BYTE * 1000L + speed - 1L) / speed;
        long delayMs = txTimeMs + POST_TX_MARGIN_MS;
        if (delayMs > 100L) return 100;
        return (int) Math.max(delayMs, POST_TX_MARGIN_MS);
    }

    private boolean ensureGpioExported() {
        if (mGpioDir.exists()) return true;

        if (!writeText(new File("/sys/class/gpio/export"), String.valueOf(mGpioNumber))) {
            return mGpioDir.exists();
        }

        for (int i = 0; i < 10; i++) {
            if (mGpioDir.exists()) return true;
            sleepQuietly(10);
        }
        return mGpioDir.exists();
    }

    private static boolean writeText(File file, String text) {
        FileOutputStream fos = null;
        try {
            fos = new FileOutputStream(file);
            fos.write(text.getBytes(StandardCharsets.US_ASCII));
            fos.flush();
            return true;
        } catch (IOException e) {
            Log.w(TAG, "write failed: " + file.getAbsolutePath() + " value=" + text
                    + ", " + e.getMessage());
            return false;
        } finally {
            if (fos != null) {
                try {
                    fos.close();
                } catch (IOException ignored) {
                }
            }
        }
    }

    private static void sleepQuietly(int ms) {
        if (ms <= 0) return;
        try {
            Thread.sleep(ms);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }
}
