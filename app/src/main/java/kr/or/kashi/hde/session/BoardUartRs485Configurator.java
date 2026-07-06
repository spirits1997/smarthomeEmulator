/*
 * Copyright (C) 2026 KOCOM
 *
 * Board UART RS-485 direction configuration helper.
 */

package kr.or.kashi.hde.session;

import android.util.Log;

import java.io.BufferedReader;
import java.io.File;
import java.io.IOException;
import java.io.InputStreamReader;
import java.util.ArrayList;
import java.util.List;

/**
 * Configures the board UART driver so the OS toggles the RS-485 direction GPIO
 * while transmitting on internal ttyAS ports.
 *
 * The wallpad service used the following ioctl values:
 *   AIRCON_IOCTL_COM_UART           = 21507
 *   AIRCON_IOCTL_COM_UART_TIME_SET  = 8000
 *   AIRCON_IOCTL_COM_UART_IO        = 11000
 *   /dev/ttyAS5 -> GPIO 136 (PE8)
 *
 * This helper is intentionally separated from DirectTtyNetworkSession so the
 * protocol/session flow stays unchanged and board-specific constants remain in
 * one place.
 */
final class BoardUartRs485Configurator {
    private static final String TAG = "BoardUartRs485";

    private static final long AIRCON_IOCTL_COM_UART = 21507L;
    private static final long AIRCON_IOCTL_COM_UART_TIME_SET = 8000L;
    private static final long AIRCON_IOCTL_COM_UART_IO = 11000L;
    private static final int AIRCON_UART_DEFAULT_DELAY_MS = 30;

    private static final String PORT_TTY_AS5 = "/dev/ttyAS5";
    private static final String PORT_TTY_AS6 = "/dev/ttyAS6";
    private static final String PORT_TTY_AS7 = "/dev/ttyAS7";

    private static final int GPIO_TTY_AS5_PE8 = 136;
    private static final int GPIO_TTY_AS6_PE6 = 134;
    private static final int GPIO_TTY_AS7_PL7 = 359;

    private BoardUartRs485Configurator() {
    }

    static boolean isBoardRs485Port(String device) {
        return getRs485GpioForPort(device) >= 0;
    }

    static boolean configure(String device) {
        int gpio = getRs485GpioForPort(device);
        if (gpio < 0) {
            return false;
        }

        long gpioRequestArg = AIRCON_IOCTL_COM_UART_IO + gpio;
        long delayRequestArg = AIRCON_IOCTL_COM_UART_TIME_SET + AIRCON_UART_DEFAULT_DELAY_MS;

        boolean gpioOk = runIoctl(device, AIRCON_IOCTL_COM_UART, gpioRequestArg,
                "AIRCON RS485 gpio config", gpio);
        boolean delayOk = runIoctl(device, AIRCON_IOCTL_COM_UART, delayRequestArg,
                "AIRCON RS485 delay config", -1);

        boolean configured = gpioOk && delayOk;
        Log.d(TAG, "RS485 board UART config result device=" + device
                + " gpio=" + gpio
                + " gpioOk=" + gpioOk
                + " delayOk=" + delayOk
                + " configured=" + configured);
        return configured;
    }

    private static int getRs485GpioForPort(String device) {
        if (device == null) return -1;
        if (PORT_TTY_AS5.equals(device)) return GPIO_TTY_AS5_PE8;
        if (PORT_TTY_AS6.equals(device)) return GPIO_TTY_AS6_PE6;
        if (PORT_TTY_AS7.equals(device)) return GPIO_TTY_AS7_PL7;
        return -1;
    }

    private static boolean runIoctl(String device, long request, long argument,
            String logPrefix, int gpio) {
        List<String[]> commands = new ArrayList<>();
        commands.add(new String[] { "/system/bin/ioctl", device,
                String.valueOf(request), String.valueOf(argument) });
        commands.add(new String[] { "/vendor/bin/ioctl", device,
                String.valueOf(request), String.valueOf(argument) });
        commands.add(new String[] { "/system/bin/toybox", "ioctl", device,
                String.valueOf(request), String.valueOf(argument) });
        commands.add(new String[] { "/vendor/bin/toybox", "ioctl", device,
                String.valueOf(request), String.valueOf(argument) });
        commands.add(new String[] { "toybox", "ioctl", device,
                String.valueOf(request), String.valueOf(argument) });
        commands.add(new String[] { "ioctl", device,
                String.valueOf(request), String.valueOf(argument) });

        for (String[] command : commands) {
            if (command.length > 0 && command[0].startsWith("/") && !new File(command[0]).exists()) {
                continue;
            }

            IoctlResult result = runCommand(command);
            if (result.started) {
                if (gpio >= 0) {
                    Log.d(TAG, logPrefix + " device=" + device
                            + " gpio=" + gpio
                            + " request=" + request
                            + " arg=" + argument
                            + " cmd=" + join(command)
                            + " ret=" + result.exitCode
                            + formatOutput(result.output));
                } else {
                    Log.d(TAG, logPrefix + " device=" + device
                            + " delay=" + (AIRCON_UART_DEFAULT_DELAY_MS / 10)
                            + " request=" + request
                            + " arg=" + argument
                            + " cmd=" + join(command)
                            + " ret=" + result.exitCode
                            + formatOutput(result.output));
                }
                return result.exitCode == 0;
            }
        }

        Log.w(TAG, logPrefix + " failed: no ioctl command available, device=" + device
                + " request=" + request + " arg=" + argument);
        return false;
    }

    private static IoctlResult runCommand(String[] command) {
        Process process = null;
        try {
            process = new ProcessBuilder(command)
                    .redirectErrorStream(true)
                    .start();

            StringBuilder output = new StringBuilder();
            BufferedReader reader = new BufferedReader(
                    new InputStreamReader(process.getInputStream()));
            String line;
            while ((line = reader.readLine()) != null) {
                if (output.length() > 0) output.append(" | ");
                output.append(line);
            }
            int exit = process.waitFor();
            return new IoctlResult(true, exit, output.toString());
        } catch (IOException e) {
            return new IoctlResult(false, -1, e.getMessage());
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return new IoctlResult(true, -1, "interrupted");
        } finally {
            if (process != null) {
                process.destroy();
            }
        }
    }

    private static String join(String[] command) {
        StringBuilder builder = new StringBuilder();
        for (String item : command) {
            if (builder.length() > 0) builder.append(' ');
            builder.append(item);
        }
        return builder.toString();
    }

    private static String formatOutput(String output) {
        if (output == null || output.length() == 0) return "";
        return " output=" + output;
    }

    private static final class IoctlResult {
        final boolean started;
        final int exitCode;
        final String output;

        IoctlResult(boolean started, int exitCode, String output) {
            this.started = started;
            this.exitCode = exitCode;
            this.output = output;
        }
    }
}
