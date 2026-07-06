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

package kr.or.kashi.hde;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.hardware.usb.UsbDevice;
import android.hardware.usb.UsbManager;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;
import android.view.MotionEvent;
import android.view.View;
import android.view.inputmethod.InputMethodManager;
import android.widget.ArrayAdapter;
import android.widget.Button;
import android.widget.EditText;
import android.widget.Spinner;

import androidx.appcompat.app.AppCompatActivity;
import androidx.fragment.app.Fragment;

import java.io.File;
import java.util.ArrayList;
import java.util.List;

import kr.or.kashi.hde.session.DirectTtyNetworkSession;
import kr.or.kashi.hde.session.NetworkSession;
import kr.or.kashi.hde.session.UsbNetworkSession;
import kr.or.kashi.hde.util.DebugLog;
import kr.or.kashi.hde.util.LocalPreferences;
import kr.or.kashi.hde.util.LocalPreferences.Pref;
import kr.or.kashi.hde.util.Utils;

public class MainActivity extends AppCompatActivity {
    private static final String TAG = MainActivity.class.getSimpleName();

    private static final String PORT_TYPE_INTERNAL = "INTERNAL";
    private static final String PORT_TYPE_USB = "USB";
    private static final String PROTOCOL_TYPE_KSX4506 = "KS X 4506";
    private static final String MODE_TYPE_MASTER = "MASTER";
    private static final String MODE_TYPE_SLAVE = "SLAVE";

    private static final String ACTION_USB_PERMISSION = "kr.or.kashi.hde.ACTION_USB_PERMISSION";

    private Handler mHandler;
    private InputMethodManager mInputMethodManager;
    private EmptyFragment mEmptyFragment;

    private Button mStartButton;
    private Button mStopButton;
    private Spinner mPortsSpinner;
    private Spinner mProtocalsSpinner;
    private Spinner mModesSpinner;

    private HomeNetwork mHomeNetwork;

    private BroadcastReceiver mUsbReceiver = new BroadcastReceiver() {
        @Override
        public void onReceive(Context context, Intent intent) {
            UsbDevice device = intent.getParcelableExtra(UsbManager.EXTRA_DEVICE);
            if (ACTION_USB_PERMISSION.equals(intent.getAction())) {
                if (intent.getBooleanExtra(UsbManager.EXTRA_PERMISSION_GRANTED, false)) {
                    Log.d(TAG, "Device permission granted: " + device);
                    startEmulator();
                } else {
                    setStateText("PERMISSION DENIED");
                }
            }
        }
    };

    @Override
    protected void attachBaseContext(Context newBase) {
        super.attachBaseContext(Utils.createDensityAdjustedContextByHeight(newBase, 600));
    }

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.root);
        registerReceiver(mUsbReceiver, new IntentFilter(ACTION_USB_PERMISSION));

        mHandler = new Handler(Looper.getMainLooper());
        McuWatchdogDisabler.sendOnceOnAppStart(this, mHandler);
        mInputMethodManager = (InputMethodManager) getSystemService(Context.INPUT_METHOD_SERVICE);

        mStartButton = findViewById(R.id.start_button);
        mStartButton.setOnClickListener(v -> startEmulator());
        mStartButton.setEnabled(true);
        mStopButton = findViewById(R.id.stop_button);
        mStopButton.setOnClickListener(v -> stopEmulator());
        mStopButton.setEnabled(false);

        List<String> portTypes = new ArrayList<>();
        portTypes.add(PORT_TYPE_USB);
        portTypes.add(PORT_TYPE_INTERNAL);
        mPortsSpinner = findViewById(R.id.ports_spinner);
        mPortsSpinner.setAdapter(new ArrayAdapter<>(this, R.layout.spinner_item, portTypes));
        mPortsSpinner.setSelection(LocalPreferences.getInt(Pref.PORT_INDEX));

        List<String> protocolTypes = new ArrayList<>();
        protocolTypes.add(PROTOCOL_TYPE_KSX4506);
        mProtocalsSpinner = findViewById(R.id.protocols_spinner);
        mProtocalsSpinner.setAdapter(new ArrayAdapter<>(this, R.layout.spinner_item, protocolTypes));
        mProtocalsSpinner.setSelection(LocalPreferences.getInt(Pref.PROTOCOL_INDEX));

        List<String> modeTypes = new ArrayList<>();
        modeTypes.add(MODE_TYPE_MASTER);
        modeTypes.add(MODE_TYPE_SLAVE);
        mModesSpinner = findViewById(R.id.modes_spinner);
        mModesSpinner.setAdapter(new ArrayAdapter<>(this, R.layout.spinner_item, modeTypes));
        mModesSpinner.setSelection(LocalPreferences.getInt(Pref.MODE_INDEX));

        mEmptyFragment = new EmptyFragment();
        setStateText("STOPPED");
        showFragment(mEmptyFragment);

        if (LocalPreferences.getBoolean(Pref.LAST_RUNNING)) {
            stopEmulator();
            new Handler().postDelayed(this::startEmulator, 100);
        }
    }

    @Override
    protected void onDestroy() {
        // Clear the static UI logger reference first. Otherwise the old Activity/View can
        // be retained after launcher relaunch or process recreation.
        DebugLog.setLogger(null);

        // If the Activity is actually destroyed, close the emulator resources explicitly.
        // LAST_RUNNING remains true unless the user pressed STOP, so launcher relaunch can
        // start it again.
        if (mHomeNetwork != null) {
            mHomeNetwork.stop();
            mHomeNetwork = null;
        }

        try {
            unregisterReceiver(mUsbReceiver);
        } catch (IllegalArgumentException e) {
            Log.w(TAG, "USB receiver was already unregistered", e);
        }

        super.onDestroy();
    }

    private void setStateText(String text) {
        Log.d(TAG, "" + text);
        if (mEmptyFragment != null) {
            mEmptyFragment.setText(text);
        }
    }

    private List<String> getAvailableInternalSerialPorts() {
        List<String> availablePorts = new ArrayList<>();
        String[] allowedPorts = new String[] { "/dev/ttyS0", "/dev/ttyS4", "/dev/ttyAS5" };

        File devDir = new File("/dev");
        File[] ttyEntries = devDir.listFiles((dir, name) -> name.equals("ttyS0") || name.equals("ttyS4") || name.equals("ttyAS5"));
        List<String> foundPorts = new ArrayList<>();
        if (ttyEntries != null) {
            for (File entry : ttyEntries) {
                foundPorts.add(entry.getAbsolutePath());
            }
        }
        Log.d(TAG, "Internal UART scan /dev allowed=[/dev/ttyS0, /dev/ttyS4, /dev/ttyAS5], found=" + foundPorts);

        for (String portPath : allowedPorts) {
            File portFile = new File(portPath);
            boolean exists = portFile.exists();
            boolean canRead = portFile.canRead();
            boolean canWrite = portFile.canWrite();
            Log.d(TAG, "Internal UART candidate " + portPath
                    + " exists=" + exists
                    + ", canRead=" + canRead
                    + ", canWrite=" + canWrite);
            if (exists) {
                availablePorts.add(portPath);
            }
        }

        Log.d(TAG, "Internal UART available port list=" + availablePorts);
        return availablePorts;
    }

    private String findInternalSerialPort() {
        // Do not reject by manufacturer string. EVB and wallpad images expose
        // different build properties, while the actual availability must be
        // decided by /dev UART nodes.
        if (Build.MANUFACTURER.length() > 7 && !Build.MANUFACTURER.substring(3,5).equals("AV")) {
            Log.w(TAG, "Internal UART manufacturer is not in old allow-list, but will scan /dev: "
                    + Build.MANUFACTURER);
        }

        List<String> availablePorts = getAvailableInternalSerialPorts();
        if (availablePorts.isEmpty()) {
            Log.e(TAG, "No internal UART port exists in /dev. allowed=[/dev/ttyS0, /dev/ttyS4, /dev/ttyAS5]");
            return null;
        }

        String preferredPort = "/dev/ttyS0";
        if (Build.MODEL.length() == 15 && Build.MODEL.substring(6,15).equals("NS-SAMPLE")) {
            preferredPort = "/dev/ttyS4";
        }

        // KOCOM wallpad boards expose the internal RS-485 emulator UART as /dev/ttyAS5.
        // Prefer it whenever it exists so wallpad master/slave tests do not fall back to EVB ports.
        if (availablePorts.contains("/dev/ttyAS5")) {
            Log.d(TAG, "Internal UART selected wallpad port=/dev/ttyAS5");
            return "/dev/ttyAS5";
        }

        if (availablePorts.contains(preferredPort)) {
            Log.d(TAG, "Internal UART selected preferred port=" + preferredPort);
            return preferredPort;
        }

        String fallbackPort = availablePorts.get(0);
        Log.w(TAG, "Preferred internal UART " + preferredPort
                + " does not exist. selected fallback port=" + fallbackPort);
        return fallbackPort;
    }

    private void startEmulator() {
        setStateText("STARTING...");

        NetworkSession networkSession = null;

        switch (mPortsSpinner.getSelectedItem().toString()) {
            case PORT_TYPE_INTERNAL: {
                final String portPath = findInternalSerialPort();
                if (portPath == null) {
                    setStateText("ERROR: NO INTERNAL PORT!");
                    break;
                }

                networkSession = new DirectTtyNetworkSession(portPath, 9600);
                Log.d(TAG, "Internal direct-tty network session created! port:" + portPath);
                break;
            }

            case PORT_TYPE_USB: {
                List<UsbDevice> usbDevices = UsbNetworkSession.getAvailableDevices(this);
                if (usbDevices.isEmpty()) {
                    setStateText("ERROR: NO USB");
                    break;
                }

                Intent intent = new Intent(ACTION_USB_PERMISSION);
                intent.setPackage(getPackageName());
                if (!UsbNetworkSession.requestPermission(this, usbDevices.get(0), intent)) {
                    setStateText("WAITING USB PERMISSION");
                    break;
                }

                networkSession = new UsbNetworkSession(this);
                Log.d(TAG, "USB network session created! dev:" + usbDevices.get(0));
                break;
            }
        }

        if (networkSession == null) {
            return;
        }

        final boolean isSlaveMode = MODE_TYPE_SLAVE.equals(mModesSpinner.getSelectedItem().toString());

        mHomeNetwork = new HomeNetwork(this, isSlaveMode);
        boolean res = mHomeNetwork.start(networkSession);
        if (!res) {
            setStateText("ERROR: CAN'T START NETWORK!");
            return;
        }

        // Save user selections before staring the emulator
        LocalPreferences.putInt(Pref.PORT_INDEX, mPortsSpinner.getSelectedItemPosition());
        LocalPreferences.putInt(Pref.PROTOCOL_INDEX, mProtocalsSpinner.getSelectedItemPosition());
        LocalPreferences.putInt(Pref.MODE_INDEX, mModesSpinner.getSelectedItemPosition());
        LocalPreferences.putBoolean(Pref.LAST_RUNNING, true);

        mPortsSpinner.setEnabled(false);
        mProtocalsSpinner.setEnabled(false);
        mModesSpinner.setEnabled(false);
        mStartButton.setEnabled(false);
        mStopButton.setEnabled(true);

        showFragment(new MainFragment(this, mHomeNetwork));
    }

    private void stopEmulator() {
        setStateText("STOPPED");

        if (mHomeNetwork != null) {
            mHomeNetwork.stop();
        }

        LocalPreferences.putBoolean(Pref.LAST_RUNNING, false);

        mPortsSpinner.setEnabled(true);
        mProtocalsSpinner.setEnabled(true);
        mModesSpinner.setEnabled(true);
        mStartButton.setEnabled(true);
        mStopButton.setEnabled(false);

        showFragment(mEmptyFragment);
    }

    private void showFragment(Fragment fragment) {
        getSupportFragmentManager().beginTransaction()
                .replace(R.id.content, fragment)
                .commit();
    }

    @Override
    public boolean dispatchTouchEvent(final MotionEvent ev) {
        final View currentFocus = getCurrentFocus();
        if (currentFocus != null) {
            if (!(currentFocus instanceof EditText) || !isTouchInsideView(ev, currentFocus)) {
                currentFocus.clearFocus();
                mInputMethodManager.hideSoftInputFromWindow(
                        currentFocus.getWindowToken(), InputMethodManager.HIDE_NOT_ALWAYS);
            }
        }
        return super.dispatchTouchEvent(ev);
    }

    private boolean isTouchInsideView(final MotionEvent ev, final View view) {
        final int[] loc = new int[2];
        view.getLocationOnScreen(loc);
        return ev.getRawX() > loc[0] && ev.getRawY() > loc[1] && ev.getRawX() < (loc[0] + view.getWidth())
            && ev.getRawY() < (loc[1] + view.getHeight());
    }
}