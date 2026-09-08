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

package kr.or.kashi.hde.ksx4506;

import android.util.Log;

import kr.or.kashi.hde.base.ByteArrayBuffer;
import kr.or.kashi.hde.base.PropertyMap;
import kr.or.kashi.hde.base.PropertyValue;
import kr.or.kashi.hde.base.StageablePropertyMap;
import kr.or.kashi.hde.HomePacket;
import kr.or.kashi.hde.MainContext;
import kr.or.kashi.hde.PacketSchedule;
import kr.or.kashi.hde.HomeDevice;
import kr.or.kashi.hde.device.PowerSaver;
import kr.or.kashi.hde.ksx4506.KSAddress;
import kr.or.kashi.hde.ksx4506.KSDeviceContextBase;
import kr.or.kashi.hde.ksx4506.KSPacket;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * [KS X 4506 / KASH B1101-12:2024] The standby-power-saver implementation for PowerSaver
 *
 * Frame summary (device id 0x39):
 *  - 0x01/0x81 status: [error][3 bytes per channel: state nibble + BCD watt]
 *  - 0x0F/0x8F characteristic: [error][channel count][1 capability byte per channel]
 *  - 0x41/0xC1 channel control: req [mask/ctrl byte per channel], rsp [error][state byte per channel]
 *  - 0x42 all/group control: [0x01 on / 0x00 off], sent 3 times, no response
 *  - 0x31/0xB1 standby(auto-cut) threshold getting: rsp [error][2 BCD bytes per channel]
 *  - 0x43/0xC3 standby(auto-cut) threshold setting: req [2 BCD bytes per channel], rsp same as 0xB1
 */
public class KSPowerSaver extends KSDeviceContextBase {
    private static final String TAG = "KSPowerSaver";
    private static final boolean DBG = true;

    public static final int CMD_STANDBY_POWER_GETTING_REQ = 0x31;
    public static final int CMD_STANDBY_POWER_GETTING_RSP = 0xB1;
    public static final int CMD_STANDBY_POWER_SETTING_REQ = 0x43;
    public static final int CMD_STANDBY_POWER_SETTING_RSP = 0xC3;

    public static final int CHANNEL_STATE_BYTES = 3;
    public static final int CHANNEL_CHARC_BYTES = 1;
    public static final int CHANNEL_STANDBY_BYTES = 2;

    // Channel state byte (upper nibble of the first status byte)
    private static final int STATE_BIT_ON               = (1 << 4);  // 1: channel on (power supplied)
    private static final int STATE_BIT_OVERLOAD         = (1 << 5);  // 1: overload
    private static final int STATE_BIT_STANDBY          = (1 << 6);  // 1: consumption is under the auto-cut threshold
    private static final int STATE_BIT_AUTO_CUT_SET     = (1 << 7);  // 1: auto-cut(standby blocking) is set

    // Channel capability byte (characteristic)
    private static final int CHARC_BIT_OVERLOAD_CUT     = (1 << 5);  // overload blocking function
    private static final int CHARC_BIT_POWER_METERING   = (1 << 6);  // active power measurement function
    private static final int CHARC_BIT_AUTO_CUT         = (1 << 7);  // auto-cut(standby blocking) function

    // Channel control byte (0x41 request) and control result byte (0xC1 response)
    private static final int CTRL_BIT_ON                = (1 << 0);  // 1: channel on, 0: channel off
    private static final int CTRL_BIT_AUTO_CUT_SET      = (1 << 1);  // 1: set auto-cut, 0: clear auto-cut
    private static final int CTRL_MASK_ON               = (1 << 4);  // 1: apply CTRL_BIT_ON
    private static final int CTRL_MASK_AUTO_CUT_SET     = (1 << 5);  // 1: apply CTRL_BIT_AUTO_CUT_SET

    // KASH B1101-12:2024 5.16/5.20: threshold over 100.0W must be ignored by the device.
    private static final float STANDBY_POWER_MAX_WATT   = 100.0f;
    private static final float CURRENT_POWER_MAX_WATT   = 9999.9f;
    private static final float SLAVE_DEFAULT_STANDBY_WATT = 5.0f;

    private int mChannelCountInGroup = 0;
    private PacketSchedule mStanbyPowerGettingSchedule = null;

    public KSPowerSaver(MainContext mainContext, Map defaultProps) {
        super(mainContext, defaultProps, PowerSaver.class);

        if (isMaster()) {
            // Register the tasks to be performed when specific property changes.
            setPropertyTask(HomeDevice.PROP_ONOFF, this::onStateControlTask);
            setPropertyTask(PowerSaver.PROP_CURRENT_SETTINGS, this::onStateControlTask);
            setPropertyTask(PowerSaver.PROP_STANDBY_CONSUMPTION, this::onStandbyPowerSettingTask);
        } else {
            // In slave(device) mode, property changes from UI are reflected directly by the
            // default reflection tasks (see DeviceContextBase). Initialize the characteristics
            // that a real device would report.
            long supportedStates = 0;
            supportedStates |= PowerSaver.State.OVERLOAD_DETECTED;
            supportedStates |= PowerSaver.State.STANDBY_DETECTED;
            mRxPropertyMap.put(PowerSaver.PROP_SUPPORTED_STATES, supportedStates);

            long supportedSettings = 0;
            supportedSettings |= PowerSaver.Setting.STANDBY_BLOCKING_ON;
            mRxPropertyMap.put(PowerSaver.PROP_SUPPORTED_SETTINGS, supportedSettings);

            mRxPropertyMap.put(PowerSaver.PROP_STANDBY_CONSUMPTION, SLAVE_DEFAULT_STANDBY_WATT);
            mRxPropertyMap.commit();
        }
    }

    @Override
    public void onDetachedFromStream() {
        // TODO: Use some batch cleanup interface
        if (mStanbyPowerGettingSchedule != null) {
            cancelSchedule(mStanbyPowerGettingSchedule);
            mStanbyPowerGettingSchedule = null;
        }

        super.onDetachedFromStream(); // Call super
    }

    @Override
    public void requestUpdate(PropertyMap props) {
        super.requestUpdate(props); // Call super

        if (isSlave()) {
            return; // Nothing to request in slave mode.
        }

        if (!isDetected()) {
            return;
        }

        if (mStanbyPowerGettingSchedule != null) {
            cancelSchedule(mStanbyPowerGettingSchedule);
            mStanbyPowerGettingSchedule = null;
        }

        // TODO: Use some batch registration interface
        KSPacket packet = createPacket(CMD_STANDBY_POWER_GETTING_REQ);

        // Querying status of power settings against group device is more efficient
        // comparing to each single devices, so convert the device sub id to group id
        // if it's accociated to a group. see also, KSDeviceContextBase.createPacket()
        if ((packet.deviceSubId & 0xF0) != 0) {
            packet.deviceSubId |= 0x0F;
        }

        PacketSchedule schedule = new PacketSchedule.Builder(packet)
                .setRepeatCount(0 /* infinity */)
                .setRepeatInterval(1000L /* TODO: */)
                .build();

        if (schedulePacket(schedule)) {
            mStanbyPowerGettingSchedule = schedule;
        } else {
            sendPacket(packet);
        }
    }

    @Override
    public @ParseResult int parsePayload(KSPacket packet, PropertyMap outProps) {
        // TODO: Use some new interface of parsing action.
        switch (packet.commandType) {
            // Requests (handled in slave mode)
            case CMD_GROUP_CONTROL_REQ:
                return parseGroupControlReq(packet, outProps);
            case CMD_STANDBY_POWER_GETTING_REQ:
                return parseStandbyPowerGettingReq(packet, outProps);
            case CMD_STANDBY_POWER_SETTING_REQ:
                return parseStandbyPowerSettingReq(packet, outProps);
            // Responses (handled in master mode)
            case CMD_STANDBY_POWER_GETTING_RSP:
            case CMD_STANDBY_POWER_SETTING_RSP:
                return parseStandbyPowerRsp(packet, outProps);
        }
        return super.parsePayload(packet, outProps);
    }

    ////////////////////////////////////////////////////////////////////////////
    // Slave (device) side: request parsing and response encoding

    /** Children (channels) of this group context in ascending order of sub-id. */
    private List<KSPowerSaver> getChannels() {
        return new ArrayList<>(getChildren(KSPowerSaver.class));
    }

    private boolean isSingleChannel() {
        final KSAddress.DeviceSubId subId = getDeviceSubId();
        return subId.isSingle() || subId.isSingleOfGroup();
    }

    @Override
    protected @ParseResult int parseStatusReq(KSPacket packet, PropertyMap outProps) {
        if (!isSlave()) return PARSE_OK_NONE;

        final ByteArrayBuffer data = new ByteArrayBuffer();
        data.append(0); // no error

        if (isSingleChannel()) {
            makeChannelStateBytes(getReadPropertyMap(), data);
        } else if (getDeviceSubId().isFullOfGroup()) {
            for (KSPowerSaver channel: getChannels()) {
                makeChannelStateBytes(channel.getReadPropertyMap(), data);
            }
        } else {
            // Status request to 0x0F/0xFF is not defined in the specification.
            return PARSE_OK_NONE;
        }

        sendPacket(createPacket(CMD_STATUS_RSP, data.toArray()));

        return PARSE_OK_STATE_UPDATED;
    }

    @Override
    protected @ParseResult int parseCharacteristicReq(KSPacket packet, PropertyMap outProps) {
        if (!isSlave()) return PARSE_OK_NONE;

        final ByteArrayBuffer data = new ByteArrayBuffer();
        data.append(0); // no error

        if (isSingleChannel()) {
            data.append(1); // Single device has always one channel.
            data.append(makeChannelCapabilityByte(getReadPropertyMap()));
        } else if (getDeviceSubId().isFullOfGroup()) {
            final List<KSPowerSaver> channels = getChannels();
            data.append(channels.size());
            for (KSPowerSaver channel: channels) {
                data.append(makeChannelCapabilityByte(channel.getReadPropertyMap()));
            }
        } else {
            // Characteristic request to 0x0F/0xFF is not defined in the specification.
            return PARSE_OK_NONE;
        }

        sendPacket(createPacket(CMD_CHARACTERISTIC_RSP, data.toArray()));

        return PARSE_OK_STATE_UPDATED;
    }

    @Override
    protected @ParseResult int parseSingleControlReq(KSPacket packet, PropertyMap outProps) {
        if (!isSlave()) return PARSE_OK_NONE;

        final ByteArrayBuffer data = new ByteArrayBuffer();
        data.append(0); // no error

        if (isSingleChannel()) {
            if (packet.data.length < 1) {
                if (DBG) Log.w(TAG, "parse-single-ctrl-req: no control data");
                return PARSE_ERROR_MALFORMED_PACKET;
            }
            // NOTE: Parse into the output props so that the response below reflects the
            // new (still uncommitted) state; the caller commits it after this returns.
            applyChannelControlByte(packet.data[0] & 0xFF, outProps);
            data.append(makeChannelControlStateByte(outProps));
        } else if (getDeviceSubId().isFullOfGroup()) {
            final List<KSPowerSaver> channels = getChannels();
            if (packet.data.length != channels.size()) {
                // KASH B1101-12:2024 5.11: the device ignores the frame if the number of
                // data bytes doesn't match the number of channels.
                if (DBG) Log.w(TAG, "parse-single-ctrl-req: data size(" + packet.data.length +
                        ") doesn't match to channel count(" + channels.size() + "), ignored");
                return PARSE_OK_NONE;
            }
            int index = 0;
            for (KSPowerSaver channel: channels) {
                channel.applyChannelControlByteAndCommit(packet.data[index++] & 0xFF);
                data.append(makeChannelControlStateByte(channel.getReadPropertyMap()));
            }
        } else {
            return PARSE_OK_NONE;
        }

        sendPacket(createPacket(CMD_SINGLE_CONTROL_RSP, data.toArray()));

        return PARSE_OK_ACTION_PERFORMED;
    }

    protected @ParseResult int parseGroupControlReq(KSPacket packet, PropertyMap outProps) {
        if (!isSlave()) return PARSE_OK_NONE;

        if (packet.data.length < 1) {
            if (DBG) Log.w(TAG, "parse-group-ctrl-req: no control data");
            return PARSE_ERROR_MALFORMED_PACKET;
        }

        // KASH B1101-12:2024 5.10/5.13: 0x42 turns all channels on/off. The wallpad sends
        // it 3 times in a row and the device only changes its state without any response.
        final boolean isOn = ((packet.data[0] & 0xFF) == 0x01);

        if (isSingleChannel()) {
            outProps.put(HomeDevice.PROP_ONOFF, isOn);
        } else {
            for (KSPowerSaver channel: getChannels()) {
                channel.mRxPropertyMap.put(HomeDevice.PROP_ONOFF, isOn);
                channel.commitPropertyChanges(channel.mRxPropertyMap);
            }
        }

        return PARSE_OK_ACTION_PERFORMED;
    }

    protected @ParseResult int parseStandbyPowerGettingReq(KSPacket packet, PropertyMap outProps) {
        if (!isSlave()) return PARSE_OK_NONE;

        final ByteArrayBuffer data = new ByteArrayBuffer();
        data.append(0); // no error

        if (isSingleChannel()) {
            makeStandbyPowerBytes(getReadPropertyMap(), data);
        } else if (getDeviceSubId().isFullOfGroup()) {
            for (KSPowerSaver channel: getChannels()) {
                makeStandbyPowerBytes(channel.getReadPropertyMap(), data);
            }
        } else {
            return PARSE_OK_NONE;
        }

        sendPacket(createPacket(CMD_STANDBY_POWER_GETTING_RSP, data.toArray()));

        return PARSE_OK_STATE_UPDATED;
    }

    protected @ParseResult int parseStandbyPowerSettingReq(KSPacket packet, PropertyMap outProps) {
        if (!isSlave()) return PARSE_OK_NONE;

        final ByteArrayBuffer data = new ByteArrayBuffer();
        data.append(0); // no error

        if (isSingleChannel()) {
            if (packet.data.length < CHANNEL_STANDBY_BYTES) {
                if (DBG) Log.w(TAG, "parse-standbypower-setting-req: wrong size of data " + packet.data.length);
                return PARSE_ERROR_MALFORMED_PACKET;
            }
            applyStandbyPowerBytes(packet.data[0], packet.data[1], outProps);
            makeStandbyPowerBytes(outProps, data);
        } else if (getDeviceSubId().isFullOfGroup()) {
            final List<KSPowerSaver> channels = getChannels();
            if (packet.data.length != channels.size() * CHANNEL_STANDBY_BYTES) {
                // KASH B1101-12:2024 5.20: ignore the frame if the data doesn't match the channel count.
                if (DBG) Log.w(TAG, "parse-standbypower-setting-req: data size(" + packet.data.length +
                        ") doesn't match to channel count(" + channels.size() + "), ignored");
                return PARSE_OK_NONE;
            }
            int offset = 0;
            for (KSPowerSaver channel: channels) {
                channel.applyStandbyPowerBytes(packet.data[offset], packet.data[offset + 1], channel.mRxPropertyMap);
                channel.commitPropertyChanges(channel.mRxPropertyMap);
                makeStandbyPowerBytes(channel.getReadPropertyMap(), data);
                offset += CHANNEL_STANDBY_BYTES;
            }
        } else {
            return PARSE_OK_NONE;
        }

        sendPacket(createPacket(CMD_STANDBY_POWER_SETTING_RSP, data.toArray()));

        return PARSE_OK_ACTION_PERFORMED;
    }

    /** Applies a channel control byte (0x41 DATA) honoring the mask bits (bit5, bit4). */
    private void applyChannelControlByte(int control, PropertyMap outProps) {
        if ((control & (CTRL_MASK_ON | CTRL_MASK_AUTO_CUT_SET)) == 0) {
            // KASH B1101-12:2024 5.8: bit1/bit0 are applied only when the mask bits are set.
            Log.w(TAG, mLogPrefix + " control byte 0x" + Integer.toHexString(control) +
                    " has no mask bit(bit5/bit4), ignored by specification");
            return;
        }
        if ((control & CTRL_MASK_ON) != 0) {
            outProps.put(HomeDevice.PROP_ONOFF, (control & CTRL_BIT_ON) != 0);
        }
        if ((control & CTRL_MASK_AUTO_CUT_SET) != 0) {
            outProps.putBit(PowerSaver.PROP_CURRENT_SETTINGS, PowerSaver.Setting.STANDBY_BLOCKING_ON,
                    (control & CTRL_BIT_AUTO_CUT_SET) != 0);
        }
    }

    private void applyChannelControlByteAndCommit(int control) {
        applyChannelControlByte(control, mRxPropertyMap);
        commitPropertyChanges(mRxPropertyMap);
    }

    /** Encodes the control result byte (0xC1 DATA1~) from the channel state. */
    private int makeChannelControlStateByte(PropertyMap props) {
        int state = 0;
        if (props.get(HomeDevice.PROP_ONOFF, Boolean.class)) state |= CTRL_BIT_ON;
        final long settings = props.get(PowerSaver.PROP_CURRENT_SETTINGS, Long.class);
        if ((settings & PowerSaver.Setting.STANDBY_BLOCKING_ON) != 0) state |= CTRL_BIT_AUTO_CUT_SET;
        return state;
    }

    /** Encodes 3 bytes of the channel status (0x81 DATA) from the properties. */
    private void makeChannelStateBytes(PropertyMap props, ByteArrayBuffer outData) {
        int state = 0;
        if (props.get(HomeDevice.PROP_ONOFF, Boolean.class)) state |= STATE_BIT_ON;
        final long curStates = props.get(PowerSaver.PROP_CURRENT_STATES, Long.class);
        if ((curStates & PowerSaver.State.OVERLOAD_DETECTED) != 0) state |= STATE_BIT_OVERLOAD;
        if ((curStates & PowerSaver.State.STANDBY_DETECTED) != 0) state |= STATE_BIT_STANDBY;
        final long settings = props.get(PowerSaver.PROP_CURRENT_SETTINGS, Long.class);
        if ((settings & PowerSaver.Setting.STANDBY_BLOCKING_ON) != 0) state |= STATE_BIT_AUTO_CUT_SET;

        float watt = props.get(PowerSaver.PROP_CURRENT_CONSUMPTION, Float.class);
        watt = Math.max(0.0f, Math.min(watt, CURRENT_POWER_MAX_WATT));
        final int tenth = Math.round(watt * 10.0f); // in 0.1W
        final int bcd1kw  = (tenth / 10000) % 10;
        final int bcd100w = (tenth / 1000) % 10;
        final int bcd10w  = (tenth / 100) % 10;
        final int bcd1w   = (tenth / 10) % 10;
        final int bcd0d1w = tenth % 10;

        outData.append((state & 0xF0) | bcd1kw);
        outData.append((bcd100w << 4) | bcd10w);
        outData.append((bcd1w << 4) | bcd0d1w);
    }

    /** Encodes the channel capability byte (0x8F DATA2~) from the properties. */
    private int makeChannelCapabilityByte(PropertyMap props) {
        int charc = 0;
        final long supportedStates = props.get(PowerSaver.PROP_SUPPORTED_STATES, Long.class);
        if ((supportedStates & PowerSaver.State.OVERLOAD_DETECTED) != 0) charc |= CHARC_BIT_OVERLOAD_CUT;
        if ((supportedStates & PowerSaver.State.STANDBY_DETECTED) != 0) charc |= CHARC_BIT_POWER_METERING;
        final long supportedSettings = props.get(PowerSaver.PROP_SUPPORTED_SETTINGS, Long.class);
        if ((supportedSettings & PowerSaver.Setting.STANDBY_BLOCKING_ON) != 0) charc |= CHARC_BIT_AUTO_CUT;
        return charc;
    }

    /** Encodes 2 BCD bytes of the standby(auto-cut) threshold (0xB1/0xC3 DATA1~). */
    private void makeStandbyPowerBytes(PropertyMap props, ByteArrayBuffer outData) {
        final float watt = props.get(PowerSaver.PROP_STANDBY_CONSUMPTION, Float.class);
        byte[] bytes = new byte[CHANNEL_STANDBY_BYTES];
        makeStandbyPowerData(watt, bytes, 0);
        outData.append(bytes);
    }

    /** Applies the standby threshold from 2 BCD bytes, ignoring the value over 100.0W. */
    private void applyStandbyPowerBytes(byte data1, byte data2, PropertyMap outProps) {
        final float watt = parseStandbyPowerBytes(data1, data2);
        if (watt > STANDBY_POWER_MAX_WATT) {
            Log.w(TAG, mLogPrefix + " standby power " + watt + "W exceeds " + STANDBY_POWER_MAX_WATT + "W, keep previous value");
            return;
        }
        outProps.put(PowerSaver.PROP_STANDBY_CONSUMPTION, watt);
    }

    ////////////////////////////////////////////////////////////////////////////
    // Master (wallpad) side: response parsing and request encoding

    protected @ParseResult int parseStatusRsp(KSPacket packet, PropertyMap outProps) {
        if (packet.data.length < 4) { // At least, 4 = error byte(1) + single channel data(3)
            if (DBG) Log.w(TAG, "parse-status-rsp: wrong size of data " + packet.data.length);
            return PARSE_ERROR_MALFORMED_PACKET;
        }

        final int error = packet.data[0] & 0xFF;
        if (error != 0) {
            if (DBG) Log.d(TAG, "parse-status-rsp: error occurred! " + error);
            onErrorOccurred(HomeDevice.Error.UNKNOWN);
            return PARSE_OK_ERROR_RECEIVED;
        }

        final KSAddress.DeviceSubId thisSubId = ((KSAddress)getAddress()).getDeviceSubId();

        if (!thisSubId.isSingle() && !thisSubId.isSingleOfGroup()) {
            // Device object that represents as parent of single devices doesn't
            // need to parse any state data since it's depending on child object
            // and stateless.
            return PARSE_OK_NONE;
        }

        final KSAddress.DeviceSubId pktSubId = KSAddress.toDeviceSubId(packet.deviceSubId);

        if (pktSubId.isSingle() || pktSubId.isSingleOfGroup()) {
            // Parse just single data since this is single device.
            return parseChannelStateBytes(packet.data, 1, outProps);
        } else if (pktSubId.isFullOfGroup()) {
            // From group data, parse only one channel associated to this single device.
            final int thisSingleId = thisSubId.value() & 0x0F;
            if (thisSingleId > 0x0 && thisSingleId < 0xF) {
                final int thisSingleIndex = thisSingleId - 1;
                final int dataOffset = 1 + (thisSingleIndex * CHANNEL_STATE_BYTES);
                return parseChannelStateBytes(packet.data, dataOffset, outProps);
            } else {
                Log.w(TAG, "parse-status-rsp: out of id range: " + thisSingleId);
            }
        } else {
            Log.w(TAG, "parse-status-rsp: not implemented case, should never reach this");
        }

        return PARSE_OK_NONE;
    }

    private @ParseResult int parseChannelStateBytes(byte[] data, int offset, PropertyMap outProps) {
        int channelSize = Math.min(CHANNEL_STATE_BYTES, data.length-offset);
        if (channelSize < CHANNEL_STATE_BYTES) {
            if (DBG) Log.w(TAG, "parse-status-rsp: wrong size of channel " + channelSize);
            return PARSE_ERROR_MALFORMED_PACKET;
        }

        final int states  = ((data[offset + 0] & 0xF0));
        final int bcd1kw  = ((data[offset + 0] & 0x0F));        // 1000W
        final int bcd100w = ((data[offset + 1] & 0xF0) >> 4);   // 100W
        final int bcd10w  = ((data[offset + 1] & 0x0F));        // 10W
        final int bcd1w   = ((data[offset + 2] & 0xF0) >> 4);   // 1W
        final int bcd0d1w = ((data[offset + 2] & 0x0F));        // 0.1W

        // Chennel on/off state
        boolean isOn = ((states & STATE_BIT_ON) != 0);
        outProps.put(HomeDevice.PROP_ONOFF, isOn);

        // Power states
        long newStates = 0;
        if ((states & STATE_BIT_OVERLOAD) != 0) newStates |= PowerSaver.State.OVERLOAD_DETECTED;
        if ((states & STATE_BIT_STANDBY) != 0) newStates |= PowerSaver.State.STANDBY_DETECTED;
        outProps.put(PowerSaver.PROP_CURRENT_STATES, newStates);

        // Settings
        long newSettings = 0;
        if ((states & STATE_BIT_AUTO_CUT_SET) != 0) newSettings |= PowerSaver.Setting.STANDBY_BLOCKING_ON;
        outProps.put(PowerSaver.PROP_CURRENT_SETTINGS, newSettings);

        // Current power consumption
        float currentWatt = 0.0f;
        currentWatt += bcd1kw  * 1000.0f;
        currentWatt += bcd100w * 100.0f;
        currentWatt += bcd10w  * 10.0f;
        currentWatt += bcd1w   * 1.0f;
        currentWatt += bcd0d1w * 0.1f;
        currentWatt = Math.round(currentWatt * 10) / 10.0f;
        outProps.put(PowerSaver.PROP_CURRENT_CONSUMPTION, currentWatt);

        return PARSE_OK_STATE_UPDATED;
    }

    protected @ParseResult int parseCharacteristicRsp(KSPacket packet, PropertyMap outProps) {
        if (packet.data.length < 3) { // At least, 3 = error byte(1) + channel count(1) + single channel state(1)
            if (DBG) Log.w(TAG, "parse-chr-rsp: wrong size of data " + packet.data.length);
            return PARSE_ERROR_MALFORMED_PACKET;
        }

        final int error = packet.data[0] & 0xFF;
        if (error != 0) {
            if (DBG) Log.d(TAG, "parse-chr-rsp: error occurred! " + error);
            onErrorOccurred(HomeDevice.Error.UNKNOWN);
            return PARSE_OK_ERROR_RECEIVED;
        }

        mChannelCountInGroup = packet.data[1] & 0xFF;

        final KSAddress.DeviceSubId thisSubId = ((KSAddress)getAddress()).getDeviceSubId();

        if (!thisSubId.isSingle() && !thisSubId.isSingleOfGroup()) {
            // Device object that represents as parent of single devices doesn't
            // need to parse any state data since it's depending on child object
            // and stateless.
            return PARSE_OK_NONE;
        }

        final KSAddress.DeviceSubId pktSubId = KSAddress.toDeviceSubId(packet.deviceSubId);

        if (pktSubId.isSingle() || pktSubId.isSingleOfGroup()) {
            // Parse just single data since this is single device.
            parseChannelCapabilityByte(packet.data[2], outProps);
            return PARSE_OK_PEER_DETECTED;
        } else if (pktSubId.isFullOfGroup()) {
            // From group data, parse only one channel associated to this single device.
            final int thisSingleId = thisSubId.value() & 0x0F;
            final int thisSingleIndex = thisSingleId - 1;
            if (thisSingleIndex >= 0 && thisSingleIndex < mChannelCountInGroup) {
                final int dataOffset = 2 + (thisSingleIndex * CHANNEL_CHARC_BYTES); // error(1) + count(1)
                if (dataOffset < packet.data.length) {
                    parseChannelCapabilityByte(packet.data[dataOffset], outProps);
                    return PARSE_OK_PEER_DETECTED;
                } else {
                    Log.w(TAG, "parse-chr-rsp: data is shorter than expected");
                }
            }
        } else {
            Log.w(TAG, "parse-chr-rsp: not implemented case, should never reach this");
        }

        return PARSE_OK_NONE;
    }

    private void parseChannelCapabilityByte(byte data, PropertyMap outProps) {
        long newStateSupports = 0;
        if ((data & CHARC_BIT_OVERLOAD_CUT) != 0) newStateSupports |= PowerSaver.State.OVERLOAD_DETECTED;
        if ((data & CHARC_BIT_POWER_METERING) != 0) newStateSupports |= PowerSaver.State.STANDBY_DETECTED;
        outProps.put(PowerSaver.PROP_SUPPORTED_STATES, newStateSupports);

        long newSettingSupports = 0;
        if ((data & CHARC_BIT_AUTO_CUT) != 0) newSettingSupports |= PowerSaver.Setting.STANDBY_BLOCKING_ON;
        outProps.put(PowerSaver.PROP_SUPPORTED_SETTINGS, newSettingSupports);
    }

    @Override
    protected @ParseResult int parseSingleControlRsp(KSPacket packet, PropertyMap outProps) {
        final String FUNTAG = "parse-single-ctrl-rsp";

        if (packet.data.length < 2) { // At least, 2 = error byte(1) + control result(1)
            if (DBG) Log.w(TAG, FUNTAG + ": wrong size of data " + packet.data.length);
            return PARSE_ERROR_MALFORMED_PACKET;
        }

        final int error = packet.data[0] & 0xFF;
        if (error != 0) {
            if (DBG) Log.d(TAG, FUNTAG + ": error occurred! " + error);
            onErrorOccurred(HomeDevice.Error.UNKNOWN);
            return PARSE_OK_ERROR_RECEIVED;
        }

        final KSAddress.DeviceSubId thisSubId = ((KSAddress)getAddress()).getDeviceSubId();

        if (!thisSubId.isSingle() && !thisSubId.isSingleOfGroup()) {
            // Device object that represents as parent of single devices doesn't
            // need to parse any state data since it's depending on child object
            // and stateless.
            return PARSE_OK_NONE;
        }

        final KSAddress.DeviceSubId pktSubId = KSAddress.toDeviceSubId(packet.deviceSubId);

        if (pktSubId.isSingle() || pktSubId.isSingleOfGroup()) {
            // Parse just single set of data since this is single device.
            return parseSingleControlRspData(packet.data[1], outProps);
        } else if (pktSubId.isFullOfGroup()) {
            // From group data, parse only exact set of data associated to this single device.
            final int thisSingleId = thisSubId.value() & 0x0F;
            if (thisSingleId > 0x0 && thisSingleId < 0xF) {
                final int thisSingleIndex = thisSingleId - 1;
                final int dataOffset = 1 + thisSingleIndex;
                if (dataOffset < packet.data.length) {
                    return parseSingleControlRspData(packet.data[dataOffset], outProps);
                } else {
                    Log.w(TAG, FUNTAG + ": data is shorter than expected");
                }
            } else {
                Log.w(TAG, FUNTAG + ": out of id range: " + thisSingleId);
            }
        } else {
            Log.w(TAG, FUNTAG + ": not implemented case, should never reach this");
        }

        return PARSE_OK_NONE;
    }

    private @ParseResult int parseSingleControlRspData(byte data, PropertyMap outProps) {
        final boolean isOn = ((data & CTRL_BIT_ON) != 0);
        outProps.put(HomeDevice.PROP_ONOFF, isOn);

        long settings = outProps.get(PowerSaver.PROP_CURRENT_SETTINGS, Long.class);
        if ((data & CTRL_BIT_AUTO_CUT_SET) != 0) {
            settings |= PowerSaver.Setting.STANDBY_BLOCKING_ON;
        } else {
            settings &= ~PowerSaver.Setting.STANDBY_BLOCKING_ON;
        }
        outProps.put(PowerSaver.PROP_CURRENT_SETTINGS, settings);

        return PARSE_OK_ACTION_PERFORMED;
    }

    protected @ParseResult int parseStandbyPowerRsp(KSPacket packet, PropertyMap outProps) {
        final String FUNTAG = "parse-standbypower-rsp";

        if (packet.data.length < 3) { // At least, 3 = error byte(1) + standby power values(2)
            if (DBG) Log.w(TAG, FUNTAG + ": wrong size of data " + packet.data.length);
            return PARSE_ERROR_MALFORMED_PACKET;
        }

        final int error = packet.data[0] & 0xFF;
        if (error != 0) {
            if (DBG) Log.d(TAG, FUNTAG + ": error occurred! " + error);
            onErrorOccurred(HomeDevice.Error.UNKNOWN);
            return PARSE_OK_ERROR_RECEIVED;
        }

        final KSAddress.DeviceSubId thisSubId = ((KSAddress)getAddress()).getDeviceSubId();

        if (!thisSubId.isSingle() && !thisSubId.isSingleOfGroup()) {
            // Device object that represents as parent of single devices doesn't
            // need to parse any state data since it's depending on child object
            // and stateless.
            return PARSE_OK_NONE;
        }

        final KSAddress.DeviceSubId pktSubId = KSAddress.toDeviceSubId(packet.deviceSubId);

        if (pktSubId.isSingle() || pktSubId.isSingleOfGroup()) {
            // Parse just single set of data since this is single device.
            final byte data1 = packet.data[1];
            final byte data2 = packet.data[2];
            return parseStandbyPowerData(data1, data2, outProps);
        } else if (pktSubId.isFullOfGroup()) {
            // From group data, parse only exact set of data associated to this single device.
            final int thisSingleId = thisSubId.value() & 0x0F;
            if (thisSingleId > 0x0 && thisSingleId < 0xF) {
                final int thisSingleIndex = thisSingleId - 1;
                final int data1Offset = 1 + (thisSingleIndex * CHANNEL_STANDBY_BYTES);
                final int data2Offset = data1Offset + 1;
                if (data1Offset < packet.data.length && data2Offset < packet.data.length) {
                    final byte data1 = packet.data[data1Offset];
                    final byte data2 = packet.data[data2Offset];
                    return parseStandbyPowerData(data1, data2, outProps);
                }
            } else {
                Log.w(TAG, FUNTAG + ": out of id range: " + thisSingleId);
            }
        } else {
            Log.w(TAG, FUNTAG + ": not implemented case, should never reach this");
        }

        return PARSE_OK_NONE;
    }

    private @ParseResult int parseStandbyPowerData(byte data1, byte data2, PropertyMap outProps) {
        outProps.put(PowerSaver.PROP_STANDBY_CONSUMPTION, parseStandbyPowerBytes(data1, data2));
        return PARSE_OK_STATE_UPDATED;
    }

    private static float parseStandbyPowerBytes(byte data1, byte data2) {
        final int bcd100w = ((data1 & 0xF0) >> 4);   // 100W
        final int bcd10w  = ((data1 & 0x0F));        // 10W
        final int bcd1w   = ((data2 & 0xF0) >> 4);   // 1W
        final int bcd0d1w = ((data2 & 0x0F));        // 0.1W

        float standbyWatt = 0;
        standbyWatt += bcd100w * 100.0f;
        standbyWatt += bcd10w  * 10.0f;
        standbyWatt += bcd1w   * 1.0f;
        standbyWatt += bcd0d1w * 0.1f;

        return Math.round(standbyWatt * 10) / 10.0f;
    }

    protected boolean onStateControlTask(PropertyMap reqProps, PropertyMap outProps) {
        boolean consumed = false;

        final KSAddress.DeviceSubId thisSubId = ((KSAddress)getAddress()).getDeviceSubId();
        if (thisSubId.isSingle() || thisSubId.isSingleOfGroup()) {
            sendPacket(createPacket(CMD_SINGLE_CONTROL_REQ, makeChannelControlByte(reqProps)));
            consumed |= true;
        } else if (thisSubId.isFull() || thisSubId.isFullOfGroup() || thisSubId.isAll()) {
            // KASH B1101-12:2024 5.10/5.13: all/group control is sent 3 times without response.
            final boolean isOn = reqProps.get(HomeDevice.PROP_ONOFF, Boolean.class);
            final byte data = (byte) (isOn ? 0x01 : 0x00);
            sendPacket(createPacket(CMD_GROUP_CONTROL_REQ, data), 2 /* 2 more, total 3 frames */);
            consumed |= true;
        } else {
            Log.w(TAG, "on-ctrl-action: should never reach this");
        }

        return consumed;
    }

    private byte makeChannelControlByte(PropertyMap reqProps) {
        final boolean chOn = (reqProps.get(HomeDevice.PROP_ONOFF, Boolean.class));
        final long settings = reqProps.get(PowerSaver.PROP_CURRENT_SETTINGS, Long.class);

        // Set the mask bit only for the property that is actually requested so that an
        // on/off request doesn't touch the auto-cut setting and vice versa (5.8).
        boolean onOffRequested = true;
        boolean settingRequested = true;
        if (reqProps instanceof StageablePropertyMap) {
            onOffRequested = false;
            settingRequested = false;
            for (PropertyValue prop: ((StageablePropertyMap)reqProps).getStaging()) {
                if (HomeDevice.PROP_ONOFF.equals(prop.getName())) onOffRequested = true;
                if (PowerSaver.PROP_CURRENT_SETTINGS.equals(prop.getName())) settingRequested = true;
            }
            if (!onOffRequested && !settingRequested) {
                onOffRequested = true;
                settingRequested = true;
            }
        }

        byte data = 0;

        if (onOffRequested) {
            data |= (byte)CTRL_MASK_ON;
            if (chOn) data |= (byte)CTRL_BIT_ON;
        }

        if (settingRequested) {
            data |= (byte)CTRL_MASK_AUTO_CUT_SET;
            if ((settings & PowerSaver.Setting.STANDBY_BLOCKING_ON) != 0) data |= (byte)CTRL_BIT_AUTO_CUT_SET;
        }

        return data;
    }

    private boolean onStandbyPowerSettingTask(PropertyMap reqProps, PropertyMap outProps) {
        final float powerWatt = reqProps.get(PowerSaver.PROP_STANDBY_CONSUMPTION, Float.class);
        boolean consumed = false;

        final KSAddress.DeviceSubId thisSubId = ((KSAddress)getAddress()).getDeviceSubId();
        if (thisSubId.isSingle() || thisSubId.isSingleOfGroup()) {
            byte data[] = new byte[CHANNEL_STANDBY_BYTES];
            makeStandbyPowerData(powerWatt, data, 0);
            sendPacket(createPacket(CMD_STANDBY_POWER_SETTING_REQ, data));
            consumed |= true;
        } else if (thisSubId.isFull() || thisSubId.isFullOfGroup() || thisSubId.isAll()) {
            byte data[] = new byte[mChannelCountInGroup * CHANNEL_STANDBY_BYTES];
            for (int i=0; i<mChannelCountInGroup; i++) {
                // TODO: Put real data of each single devices. For now, just put
                // same watt for all channels.
                makeStandbyPowerData(powerWatt, data, i * CHANNEL_STANDBY_BYTES);
            }
            sendPacket(createPacket(CMD_STANDBY_POWER_SETTING_REQ, data));
            consumed |= true;
        } else {
            Log.w(TAG, "on-standbypower-setting-action: should never reach this");
        }

        return consumed;
    }

    private static void makeStandbyPowerData(float watt, byte[] out, int offset) {
        final int tenth = Math.round(Math.max(0.0f, Math.min(watt, 999.9f)) * 10.0f); // in 0.1W
        final int bcd100w = (tenth / 1000) % 10;
        final int bcd10w  = (tenth / 100) % 10;
        final int bcd1w   = (tenth / 10) % 10;
        final int bcd0d1w = tenth % 10;
        out[offset + 0]   = (byte)((bcd100w << 4) | bcd10w);
        out[offset + 1]   = (byte)((bcd1w << 4) | bcd0d1w);
    }
}
