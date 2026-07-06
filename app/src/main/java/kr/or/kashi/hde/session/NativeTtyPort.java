package kr.or.kashi.hde.session;

import android.util.Log;

/**
 * Native UART helper for board internal RS-485 ports.
 *
 * The implementation is based on the wallpad_service KSX4506 JNI UART path:
 * open(/dev/ttyASx) -> ioctl(fd, 21507, 11000 + gpio) ->
 * ioctl(fd, 21507, 8000 + delay) -> termios raw 8N1.
 */
final class NativeTtyPort {
    private static final String TAG = "NativeTtyPort";

    static {
        try {
            System.loadLibrary("hde-native-tty");
            Log.d(TAG, "hde-native-tty loaded");
        } catch (UnsatisfiedLinkError e) {
            Log.e(TAG, "failed to load hde-native-tty", e);
        }
    }

    private NativeTtyPort() {
    }

    static native int open(String device, int baudRate);
    static native int read(int fd, byte[] buffer, int maxLength, int timeoutMs);
    static native int write(int fd, byte[] data, int length);
    static native void close(int fd);
}
