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

package kr.or.kashi.hde.device;

import android.content.Context;
import android.util.AttributeSet;
import android.view.View;
import android.widget.Button;
import android.widget.CheckBox;
import android.widget.EditText;
import android.widget.RadioButton;
import android.widget.TextView;

import androidx.annotation.Nullable;

import kr.or.kashi.hde.R;
import kr.or.kashi.hde.base.PropertyMap;
import kr.or.kashi.hde.base.PropertyValue;
import kr.or.kashi.hde.widget.HomeDeviceView;

public class PowerSaverView extends HomeDeviceView<PowerSaver> implements View.OnClickListener {
    private static final String TAG = PowerSaverView.class.getSimpleName();
    private final Context mContext;

    private CheckBox mStateCheck;
    private CheckBox mOverloadDetectedCheck;
    private CheckBox mStandbyDetectedCheck;
    private CheckBox mSettingCheck;
    private RadioButton mStandbyBlockingOffRadio;
    private RadioButton mStandbyBlockingOnRadio;
    private CheckBox mCurrentPowerCheck;
    private TextView mCurrentPowerText;
    private EditText mCurrentPowerEdit;
    private TextView mCurrentPowerUnitText;
    private Button mCurrentPowerSetButton;
    private CheckBox mStandbyPowerCheck;
    private TextView mStandbyPowerText;
    private EditText mStandbyPowerEdit;
    private Button mStandbyPowerSetButton;

    public PowerSaverView(Context context, @Nullable AttributeSet attrs) {
        super(context, attrs);
        mContext = context;
    }

    @Override
    protected void onFinishInflate () {
        super.onFinishInflate();

        mStateCheck = findViewById(R.id.state_check);
        mStateCheck.setEnabled(true);
        mStateCheck.setClickable(false);
        // Detected states are reported by the device, so they are only toggleable in
        // slave(device) mode to simulate the overload / standby detection.
        mOverloadDetectedCheck = findViewById(R.id.overload_detected_check);
        mOverloadDetectedCheck.setOnClickListener(this);
        mOverloadDetectedCheck.setClickable(isSlave());
        mStandbyDetectedCheck = findViewById(R.id.standby_detected_check);
        mStandbyDetectedCheck.setOnClickListener(this);
        mStandbyDetectedCheck.setClickable(isSlave());
        mSettingCheck = findViewById(R.id.setting_check);
        mStandbyBlockingOffRadio = findViewById(R.id.standby_blocking_off_radio);
        mStandbyBlockingOffRadio.setOnClickListener(this);
        mStandbyBlockingOnRadio = findViewById(R.id.standby_blocking_on_radio);
        mStandbyBlockingOnRadio.setOnClickListener(this);
        mCurrentPowerCheck = findViewById(R.id.current_power_check);
        mCurrentPowerText = findViewById(R.id.current_power_text);
        // Current consumption is measured by the device; allow editing it in slave mode only.
        mCurrentPowerEdit = findViewById(R.id.current_power_edit);
        mCurrentPowerEdit.setVisibility(isSlave() ? View.VISIBLE : View.GONE);
        mCurrentPowerUnitText = findViewById(R.id.current_power_unit_text);
        mCurrentPowerUnitText.setVisibility(isSlave() ? View.VISIBLE : View.GONE);
        mCurrentPowerSetButton = findViewById(R.id.current_power_set_button);
        mCurrentPowerSetButton.setVisibility(isSlave() ? View.VISIBLE : View.GONE);
        mCurrentPowerSetButton.setOnClickListener(this);
        mStandbyPowerCheck = findViewById(R.id.standby_power_check);
        mStandbyPowerText = findViewById(R.id.standby_power_text);
        mStandbyPowerEdit = findViewById(R.id.standby_power_edit);
        mStandbyPowerSetButton = findViewById(R.id.standby_power_set_button);
        mStandbyPowerSetButton.setOnClickListener(this);
    }

    @Override
    public void onUpdateProperty(PropertyMap props, PropertyMap changed) {
        final long supportedStates = props.get(PowerSaver.PROP_SUPPORTED_STATES, Long.class);
        mOverloadDetectedCheck.setEnabled((supportedStates & PowerSaver.State.OVERLOAD_DETECTED) != 0);
        mStandbyDetectedCheck.setEnabled((supportedStates & PowerSaver.State.STANDBY_DETECTED) != 0);

        final long currentStates = props.get(PowerSaver.PROP_CURRENT_STATES, Long.class);
        mOverloadDetectedCheck.setChecked((currentStates & PowerSaver.State.OVERLOAD_DETECTED) != 0);
        mStandbyDetectedCheck.setChecked((currentStates & PowerSaver.State.STANDBY_DETECTED) != 0);

        final long supportedSettings = props.get(PowerSaver.PROP_SUPPORTED_SETTINGS, Long.class);
        mStandbyBlockingOffRadio.setEnabled((supportedSettings & PowerSaver.Setting.STANDBY_BLOCKING_ON) != 0);
        mStandbyBlockingOnRadio.setEnabled((supportedSettings & PowerSaver.Setting.STANDBY_BLOCKING_ON) != 0);

        final long currentSettings = props.get(PowerSaver.PROP_CURRENT_SETTINGS, Long.class);
        mStandbyBlockingOffRadio.setChecked((currentSettings & PowerSaver.Setting.STANDBY_BLOCKING_ON) == 0);
        mStandbyBlockingOnRadio.setChecked((currentSettings & PowerSaver.Setting.STANDBY_BLOCKING_ON) != 0);

        final float currentConsumption = props.get(PowerSaver.PROP_CURRENT_CONSUMPTION, Float.class);
        mCurrentPowerText.setText("" + currentConsumption);
        if (!mCurrentPowerEdit.hasFocus()) mCurrentPowerEdit.setText("" + currentConsumption);

        final float standbyConsumption = props.get(PowerSaver.PROP_STANDBY_CONSUMPTION, Float.class);
        mStandbyPowerText.setText("" + standbyConsumption);
        if (!mStandbyPowerEdit.hasFocus()) mStandbyPowerEdit.setText("" + standbyConsumption);
    }

    @Override
    public void onClick(View v) {
        if (v == mOverloadDetectedCheck || v == mStandbyDetectedCheck) {
            long states = 0;
            if (mOverloadDetectedCheck.isChecked()) states |= PowerSaver.State.OVERLOAD_DETECTED;
            if (mStandbyDetectedCheck.isChecked()) states |= PowerSaver.State.STANDBY_DETECTED;
            device().setProperty(PowerSaver.PROP_CURRENT_STATES, Long.class, states);
        } else if (v == mCurrentPowerSetButton) {
            final String editStr = mCurrentPowerEdit.getText().toString();
            float currentConsumption = PropertyValue.newValueObject(Float.class, editStr);
            device().setProperty(PowerSaver.PROP_CURRENT_CONSUMPTION, Float.class, currentConsumption);
        } else if (v == mStandbyBlockingOffRadio) {
            final long currentSettings = device().getProperty(PowerSaver.PROP_CURRENT_SETTINGS, Long.class);
            device().setProperty(PowerSaver.PROP_CURRENT_SETTINGS, Long.class, currentSettings & ~PowerSaver.Setting.STANDBY_BLOCKING_ON);
        } else if (v == mStandbyBlockingOnRadio) {
            final long currentSettings = device().getProperty(PowerSaver.PROP_CURRENT_SETTINGS, Long.class);
            device().setProperty(PowerSaver.PROP_CURRENT_SETTINGS, Long.class, currentSettings | PowerSaver.Setting.STANDBY_BLOCKING_ON);
        } else if (v == mStandbyPowerSetButton) {
            final String editStr = mStandbyPowerEdit.getText().toString();
            float standbyConsumption = PropertyValue.newValueObject(Float.class, editStr);
            device().setProperty(PowerSaver.PROP_STANDBY_CONSUMPTION, Float.class, standbyConsumption);
        }
    }
}
