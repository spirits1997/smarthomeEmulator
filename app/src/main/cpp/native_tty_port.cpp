#include <jni.h>
#include <android/log.h>
#include <cerrno>
#include <cstring>
#include <fcntl.h>
#include <poll.h>
#include <termios.h>
#include <unistd.h>
#include <sys/ioctl.h>

#define LOG_TAG "NativeTtyPort"
#define LOGD(...) __android_log_print(ANDROID_LOG_DEBUG, LOG_TAG, __VA_ARGS__)
#define LOGW(...) __android_log_print(ANDROID_LOG_WARN,  LOG_TAG, __VA_ARGS__)
#define LOGE(...) __android_log_print(ANDROID_LOG_ERROR, LOG_TAG, __VA_ARGS__)

#define KSX_IOCTL_COM_UART           (21507L)
#define KSX_IOCTL_COM_UART_TIME_SET  (8000L)
#define KSX_IOCTL_COM_UART_IO        (11000L)
#define KSX_UART_DEFAULT_DELAY_MS    (30)  // OS driver uses 100us unit on the target wallpad kernel.

static int getRs485GpioForPort(const char *device) {
    if (device == nullptr) return -1;
    if (strcmp(device, "/dev/ttyAS5") == 0) return 136; // PE8
    if (strcmp(device, "/dev/ttyAS6") == 0) return 134; // PE6
    if (strcmp(device, "/dev/ttyAS7") == 0) return 359; // PL7
    return -1;
}

static void configureBoardUart(int fd, const char *device) {
    if (fd < 0 || device == nullptr) return;

    const int gpio = getRs485GpioForPort(device);
    if (gpio >= 0) {
        const int arg = KSX_IOCTL_COM_UART_IO + gpio;
        const int ret = ioctl(fd, KSX_IOCTL_COM_UART, arg);
        LOGD("KSX RS485 gpio config device=%s gpio=%d request=%ld arg=%d ret=%d errno=%d msg=%s",
             device, gpio, KSX_IOCTL_COM_UART, arg, ret, errno, strerror(errno));
    }

    const int delayArg = KSX_IOCTL_COM_UART_TIME_SET + KSX_UART_DEFAULT_DELAY_MS;
    const int ret = ioctl(fd, KSX_IOCTL_COM_UART, delayArg);
    LOGD("KSX RS485 delay config device=%s delayArg=%d ret=%d errno=%d msg=%s",
         device, delayArg, ret, errno, strerror(errno));
}

static speed_t baudToSpeed(int baudRate) {
    switch (baudRate) {
        case 1200: return B1200;
        case 2400: return B2400;
        case 4800: return B4800;
        case 9600: return B9600;
        case 19200: return B19200;
        case 38400: return B38400;
        case 57600: return B57600;
        case 115200: return B115200;
        case 230400: return B230400;
#ifdef B460800
        case 460800: return B460800;
#endif
#ifdef B921600
        case 921600: return B921600;
#endif
        default: return B9600;
    }
}

static bool configureTermios(int fd, int baudRate) {
    struct termios tio{};
    if (tcgetattr(fd, &tio) != 0) {
        LOGE("tcgetattr failed fd=%d errno=%d msg=%s", fd, errno, strerror(errno));
        return false;
    }

    cfmakeraw(&tio);
    const speed_t speed = baudToSpeed(baudRate);
    cfsetispeed(&tio, speed);
    cfsetospeed(&tio, speed);

    tio.c_cflag &= ~PARENB;
    tio.c_cflag &= ~CSTOPB;
    tio.c_cflag &= ~CSIZE;
    tio.c_cflag |= CS8;
    tio.c_cflag |= CLOCAL | CREAD;
    tio.c_iflag = IGNPAR;
    tio.c_oflag = 0;
    tio.c_lflag = 0;
    tio.c_cc[VMIN] = 0;
    tio.c_cc[VTIME] = 0;

    tcflush(fd, TCIOFLUSH);
    if (tcsetattr(fd, TCSANOW, &tio) != 0) {
        LOGE("tcsetattr failed fd=%d baud=%d errno=%d msg=%s", fd, baudRate, errno, strerror(errno));
        return false;
    }

    LOGD("termios configured fd=%d baud=%d 8N1 raw", fd, baudRate);
    return true;
}

extern "C" JNIEXPORT jint JNICALL
Java_kr_or_kashi_hde_session_NativeTtyPort_open(JNIEnv *env, jclass, jstring jDevice, jint baudRate) {
    if (jDevice == nullptr) return -1;

    const char *device = env->GetStringUTFChars(jDevice, nullptr);
    if (device == nullptr) return -1;

    LOGD("open requested device=%s baud=%d", device, baudRate);
    int fd = open(device, O_RDWR | O_NOCTTY | O_NONBLOCK);
    if (fd < 0) {
        LOGE("open failed device=%s errno=%d msg=%s", device, errno, strerror(errno));
        env->ReleaseStringUTFChars(jDevice, device);
        return -1;
    }

    configureBoardUart(fd, device);

    if (!configureTermios(fd, baudRate)) {
        close(fd);
        env->ReleaseStringUTFChars(jDevice, device);
        return -1;
    }

    LOGD("open success device=%s fd=%d baud=%d", device, fd, baudRate);
    env->ReleaseStringUTFChars(jDevice, device);
    return fd;
}

extern "C" JNIEXPORT jint JNICALL
Java_kr_or_kashi_hde_session_NativeTtyPort_read(JNIEnv *env, jclass, jint fd, jbyteArray buffer, jint maxLength, jint timeoutMs) {
    if (fd < 0 || buffer == nullptr || maxLength <= 0) return -1;

    const jsize arrayLength = env->GetArrayLength(buffer);
    const int readMax = maxLength < arrayLength ? maxLength : arrayLength;
    if (readMax <= 0) return -1;

    struct pollfd pfd{};
    pfd.fd = fd;
    pfd.events = POLLIN;
    const int pollRet = poll(&pfd, 1, timeoutMs);
    if (pollRet == 0) return 0;
    if (pollRet < 0) {
        if (errno == EINTR) return 0;
        LOGE("poll failed fd=%d errno=%d msg=%s", fd, errno, strerror(errno));
        return -1;
    }

    jbyte stackBuf[512];
    jbyte *tmp = stackBuf;
    bool heapAllocated = false;
    if (readMax > (int)sizeof(stackBuf)) {
        tmp = new jbyte[readMax];
        heapAllocated = true;
    }

    int result = 0;
    const ssize_t n = ::read(fd, tmp, readMax);
    if (n > 0) {
        env->SetByteArrayRegion(buffer, 0, (jsize)n, tmp);
        result = (int)n;
    } else if (n == 0 || errno == EAGAIN || errno == EWOULDBLOCK || errno == EINTR) {
        result = 0;
    } else {
        LOGE("read failed fd=%d errno=%d msg=%s", fd, errno, strerror(errno));
        result = -1;
    }

    if (heapAllocated) delete[] tmp;
    return result;
}

extern "C" JNIEXPORT jint JNICALL
Java_kr_or_kashi_hde_session_NativeTtyPort_write(JNIEnv *env, jclass, jint fd, jbyteArray data, jint length) {
    if (fd < 0 || data == nullptr || length <= 0) return -1;

    const jsize arrayLength = env->GetArrayLength(data);
    const int writeLen = length < arrayLength ? length : arrayLength;
    if (writeLen <= 0) return -1;

    jbyte *body = env->GetByteArrayElements(data, nullptr);
    if (body == nullptr) return -1;

    int total = 0;
    while (total < writeLen) {
        const ssize_t n = ::write(fd, body + total, writeLen - total);
        if (n > 0) {
            total += (int)n;
            continue;
        }
        if (n < 0 && (errno == EAGAIN || errno == EWOULDBLOCK || errno == EINTR)) {
            usleep(1000);
            continue;
        }
        LOGE("write failed fd=%d errno=%d msg=%s", fd, errno, strerror(errno));
        env->ReleaseByteArrayElements(data, body, JNI_ABORT);
        return total > 0 ? total : -1;
    }

    tcdrain(fd);
    env->ReleaseByteArrayElements(data, body, JNI_ABORT);
    return total;
}

extern "C" JNIEXPORT void JNICALL
Java_kr_or_kashi_hde_session_NativeTtyPort_close(JNIEnv *, jclass, jint fd) {
    if (fd >= 0) {
        LOGD("close fd=%d", fd);
        close(fd);
    }
}
