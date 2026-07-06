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

import android.content.Context;
import android.util.Log;

import kr.or.kashi.hde.DeviceContextBase;
import kr.or.kashi.hde.base.ByteArrayBuffer;
import kr.or.kashi.hde.base.PropertyMap;
import kr.or.kashi.hde.MainContext;
import kr.or.kashi.hde.HomeDevice;
import kr.or.kashi.hde.device.Light;
import kr.or.kashi.hde.ksx4506.KSAddress;
import kr.or.kashi.hde.ksx4506.KSDeviceContextBase;
import kr.or.kashi.hde.ksx4506.KSPacket;

import java.nio.ByteBuffer;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * [KS X 4506] The light implementation for Light
 */
public class KSLight extends KSDeviceContextBase {
    private static final String TAG = "KSLight";
    private static final boolean DBG = true;

    public static final int CMD_LIGHT_ALL_CONTROL_REQ = 0x43;
    public static final int CMD_TONE_CONTROL_REQ = 0x44;
    public static final int CMD_TONE_CONTROL_RSP = 0xC4;

    private static final int LIGHT_LEVEL_MIN = 0x01;
    private static final int LIGHT_LEVEL_MAX = 0x0F;

    protected int mTotalCountInGroup = 0;
    protected int mToneSupportedFlagsInGroup = 0;

    public KSLight(MainContext mainContext, Map defaultProps) {
        super(mainContext, defaultProps, Light.class);

        if (!isSlave()) { // TODO:
            // Register the tasks to be performed when specific property changes.
            setPropertyTask(HomeDevice.PROP_ONOFF, mSingleControlTask);
            setPropertyTask(Light.PROP_CUR_DIM_LEVEL, mSingleControlTask);
            setPropertyTask(Light.PROP_CUR_TONE_LEVEL, this::onToneControlTask);
        }
    }


    @Override
    public @ParseResult int parsePayload(KSPacket packet, PropertyMap outProps) {
        switch (packet.commandType) {
            case CMD_TONE_CONTROL_REQ: return parseToneControlReq(packet, outProps);
            case CMD_TONE_CONTROL_RSP: return parseToneControlRsp(packet, outProps);
        }
        return super.parsePayload(packet, outProps);
    }

    @Override
    protected @ParseResult int parseStatusReq(KSPacket packet, PropertyMap outProps) {
        ByteArrayBuffer data = new ByteArrayBuffer();
        data.append(0); // error code

        @ParseResult int res = makeStatusRsp(packet, outProps, data);
        if (res < PARSE_OK_NONE) return res;

        sendPacket(createPacket(CMD_STATUS_RSP, data.toArray()));

        return PARSE_OK_STATE_UPDATED;
    }

    protected int makeStatusRsp(KSPacket reqPacket, PropertyMap outProps, ByteArrayBuffer outData) {
        final KSAddress.DeviceSubId thisSubId = ((KSAddress)getAddress()).getDeviceSubId();
        if (thisSubId.isSingle() || thisSubId.isSingleOfGroup()) {
            outData.append(makeSingleLightStateByte(outProps));
        } else if (thisSubId.isFull() || thisSubId.isFullOfGroup()) {
            for (KSLight child: getChildren(KSLight.class)) {
                outData.append(makeSingleLightStateByte(child.getReadPropertyMap()));
            }
            for (KSLight child: getChildren(KSLight.class)) {
                PropertyMap childProps = child.getReadPropertyMap();
                if (childProps.get(Light.PROP_TONE_SUPPORTED, Boolean.class)) {
                    outData.append(makeSingleToneStateByte(childProps));
                }
            }
        } else {
            Log.w(TAG, "parse-status-req: should never reach this");
        }
        return PARSE_OK_STATE_UPDATED;
    }

    @Override
    protected @ParseResult int parseStatusRsp(KSPacket packet, PropertyMap outProps) {
        if (packet.data.length < 2) {
            if (DBG) Log.w(TAG, "parse-status-rsp: wrong size of data " + packet.data.length);
            return PARSE_ERROR_MALFORMED_PACKET;
        }

        final int error = packet.data[0] & 0xFF;
        if (error != 0) {
            if (DBG) Log.d(TAG, "parse-status-rsp: error occurred! " + error);
            onErrorOccurred(HomeDevice.Error.UNKNOWN);
            return PARSE_OK_ERROR_RECEIVED;
        }

        if (KSAddress.toDeviceSubId(packet.deviceSubId).isSingle()) {
            final int state = packet.data[1] & 0xFF;
            parseSingleLightStateByte(state, outProps);
        } else {
            KSAddress address = (KSAddress)getAddress();
            int index = address.getDeviceSubId().value() & 0x0F;
            if (index != 0x00 && index != 0x0F) {
                if (index < packet.data.length) {
                    // Pick only one dimming/onoff state related with this device.
                    final int state = packet.data[index] & 0xFF;
                    parseSingleLightStateByte(state, outProps);
                }
                if (mTotalCountInGroup > 0 && outProps.get(Light.PROP_TONE_SUPPORTED, Boolean.class)) {
                    int toneOrder = getToneOrder(index);
                    int toneOffset = 1 + mTotalCountInGroup + toneOrder;
                    if (toneOrder >= 0 && toneOffset < packet.data.length) {
                        parseSingleToneStateByte(packet.data[toneOffset] & 0xFF, outProps);
                    }
                }
            }
        }

        return PARSE_OK_STATE_UPDATED;
    }

    @Override
    protected @ParseResult int parseCharacteristicReq(KSPacket packet, PropertyMap outProps) {
        final ByteArrayBuffer data = new ByteArrayBuffer();
        data.append(0); // error code

        @ParseResult int res = makeCharacteristicRsp(packet, outProps, data);
        if (res < PARSE_OK_NONE) return res;

        sendPacket(createPacket(CMD_CHARACTERISTIC_RSP, data.toArray()));

        return PARSE_OK_STATE_UPDATED;
    }

    protected int makeCharacteristicRsp(KSPacket reqPacket, PropertyMap outProps, ByteArrayBuffer outData) {
        final PropertyMap props = getReadPropertyMap();

        int normalCount = 0;
        int dimmableCount = 0;
        int dimmableFlags = 0;
        int toneCount = 0;
        int toneFlags = 0;
        int maxDimLevel = 0;
        int maxToneLevel = 0;

        final KSAddress.DeviceSubId thisSubId = ((KSAddress)getAddress()).getDeviceSubId();
        if (thisSubId.isSingle() || thisSubId.isSingleOfGroup()) {
            boolean dimSupported = props.get(Light.PROP_DIM_SUPPORTED, Boolean.class);
            boolean toneSupported = props.get(Light.PROP_TONE_SUPPORTED, Boolean.class);
            normalCount = dimSupported ? 0 : 1;
            dimmableCount = dimSupported ? 1 : 0;
            toneCount = toneSupported ? 1 : 0;
            maxDimLevel = dimSupported ? props.get(Light.PROP_MAX_DIM_LEVEL, Integer.class) : 0;
            maxToneLevel = toneSupported ? props.get(Light.PROP_MAX_TONE_LEVEL, Integer.class) : 0;
        } else if (thisSubId.isFull() || thisSubId.isFullOfGroup()) {
            int index = 0;
            for (KSLight child: getChildren(KSLight.class)) {
                PropertyMap childProps = child.getReadPropertyMap();
                if (childProps.get(Light.PROP_DIM_SUPPORTED, Boolean.class)) {
                    dimmableFlags |= (1 << index);
                    dimmableCount++;
                    maxDimLevel = Math.max(maxDimLevel, childProps.get(Light.PROP_MAX_DIM_LEVEL, Integer.class));
                } else {
                    normalCount++;
                }
                if (childProps.get(Light.PROP_TONE_SUPPORTED, Boolean.class)) {
                    toneFlags |= (1 << index);
                    toneCount++;
                    maxToneLevel = Math.max(maxToneLevel, childProps.get(Light.PROP_MAX_TONE_LEVEL, Integer.class));
                }
                index++;
            }
        }

        outData.append(normalCount);
        outData.append(dimmableCount);
        outData.append((dimmableFlags >> 0) & 0xFF);
        outData.append((dimmableFlags >> 8) & 0xFF);
        outData.append(toneCount);
        outData.append(clamp("maxDimLevel", maxDimLevel, 0, LIGHT_LEVEL_MAX));
        outData.append(clamp("maxToneLevel", maxToneLevel, 0, LIGHT_LEVEL_MAX));
        outData.append((toneFlags >> 0) & 0xFF);
        outData.append((toneFlags >> 8) & 0xFF);

        return PARSE_OK_STATE_UPDATED;
    }

    @Override
    protected @ParseResult int parseCharacteristicRsp(KSPacket packet, PropertyMap outProps) {
        if (packet.data.length < 3) {
            if (DBG) Log.w(TAG, "parse-chr-rsp: wrong size of data " + packet.data.length);
            return PARSE_ERROR_MALFORMED_PACKET;
        }

        final int error = packet.data[0] & 0xFF;
        if (error != 0) {
            if (DBG) Log.d(TAG, "parse-chr-rsp: error occurred! " + error);
            onErrorOccurred(HomeDevice.Error.UNKNOWN);
            return PARSE_OK_ERROR_RECEIVED;
        }

        final int normalCount = packet.data[1] & 0xFF;
        final int dimmableCount = packet.data[2] & 0xFF;
        final int totalCount = normalCount + dimmableCount;

        final boolean singleResponse = KSAddress.toDeviceSubId(packet.deviceSubId).isSingle();
        if (singleResponse) {
            outProps.put(Light.PROP_DIM_SUPPORTED, (dimmableCount > 0));
            mTotalCountInGroup = 1;
        } else {
            mTotalCountInGroup = totalCount;
        }

        KSAddress address = (KSAddress)getAddress();
        int index = singleResponse ? 1 : (address.getDeviceSubId().value() & 0x0F);
        if (!singleResponse && index > totalCount) {
            // Exit since the index is out of count.
            return PARSE_OK_NONE;
        }

        boolean dimSupported = (dimmableCount > 0);

        if (packet.data.length < 5) {
            // HACK: Just guess characteristic if the data is shorter than normal.
            dimSupported = (dimmableCount == totalCount);
            outProps.put(Light.PROP_DIM_SUPPORTED, dimSupported);
            return PARSE_OK_PEER_DETECTED;
        }

        final int dimFlag1 = packet.data[3] & 0xFF;
        final int dimFlag2 = packet.data[4] & 0xFF;

        if (!singleResponse) {
            if (index >= 1 && index <= 8) {
                dimSupported = ((dimFlag1 >> (index-1)) & 0x01) != 0;
            } else if (index >= 9 && index <= 0xE) {
                dimSupported = ((dimFlag2 >> (index-9)) & 0x01) != 0;
            }
        }

        outProps.put(Light.PROP_DIM_SUPPORTED, dimSupported);

        // KASH B1101-1 extended characteristic response. Keep LENGTH 0x05 compatibility.
        if (packet.data.length >= 10) {
            final int maxDimLevel = packet.data[6] & 0xFF;
            final int maxToneLevel = packet.data[7] & 0xFF;
            final int toneFlag1 = packet.data[8] & 0xFF;
            final int toneFlag2 = packet.data[9] & 0xFF;
            boolean toneSupported = false;
            if (singleResponse) {
                toneSupported = maxToneLevel > 0;
            } else if (index >= 1 && index <= 8) {
                toneSupported = ((toneFlag1 >> (index-1)) & 0x01) != 0;
            } else if (index >= 9 && index <= 0xE) {
                toneSupported = ((toneFlag2 >> (index-9)) & 0x01) != 0;
            }
            mToneSupportedFlagsInGroup = toneFlag1 | (toneFlag2 << 8);
            outProps.put(Light.PROP_TONE_SUPPORTED, toneSupported);
            if (maxDimLevel > 0) outProps.put(Light.PROP_MAX_DIM_LEVEL, clamp("maxDimLevel", maxDimLevel, LIGHT_LEVEL_MIN, LIGHT_LEVEL_MAX));
            if (maxToneLevel > 0) outProps.put(Light.PROP_MAX_TONE_LEVEL, clamp("maxToneLevel", maxToneLevel, LIGHT_LEVEL_MIN, LIGHT_LEVEL_MAX));
        }

        return PARSE_OK_PEER_DETECTED;
    }


    protected @ParseResult int parseToneControlReq(KSPacket packet, PropertyMap outProps) {
        if (packet.data.length != 1) {
            if (DBG) Log.w(TAG, "parse-tone-req: wrong size of data " + packet.data.length);
            return PARSE_ERROR_MALFORMED_PACKET;
        }
        parseToneControlData(packet.data[0] & 0xFF, outProps);

        final ByteArrayBuffer data = new ByteArrayBuffer();
        data.append(0); // error code
        data.append(makeSingleToneStateByte(outProps));
        sendPacket(createPacket(CMD_TONE_CONTROL_RSP, data.toArray()));

        return PARSE_OK_ACTION_PERFORMED;
    }

    protected @ParseResult int parseToneControlRsp(KSPacket packet, PropertyMap outProps) {
        if (packet.data.length != 2) {
            if (DBG) Log.w(TAG, "parse-tone-rsp: wrong size of data " + packet.data.length);
            return PARSE_ERROR_MALFORMED_PACKET;
        }
        final int error = packet.data[0] & 0xFF;
        if (error != 0) {
            if (DBG) Log.d(TAG, "parse-tone-rsp: error occurred! " + error);
            onErrorOccurred(HomeDevice.Error.CANT_CONTROL);
            return PARSE_OK_ERROR_RECEIVED;
        }
        parseSingleToneStateByte(packet.data[1] & 0xFF, outProps);
        return PARSE_OK_ACTION_PERFORMED;
    }

    protected @ParseResult int parseSingleControlReq(KSPacket packet, PropertyMap outProps) {
        parseLightControlData(packet.data, outProps);

        // NOTE: Just use the output props for reading because uncommitted changes that is produced
        // by previous parsing could be staging in the output props and that should be reflect by
        // consecutive response packet to the peer.
        final PropertyMap props = outProps;

        final ByteArrayBuffer data = new ByteArrayBuffer();
        data.append(0); // error code
        data.append(makeSingleLightStateByte(props));

        sendPacket(createPacket(CMD_SINGLE_CONTROL_RSP, data.toArray()));

        return PARSE_OK_ACTION_PERFORMED;
    }

    @Override
    protected @ParseResult int parseSingleControlRsp(KSPacket packet, PropertyMap outProps) {
        if (packet.data.length != 2) {
            if (DBG) Log.w(TAG, "parse-ctrl-rsp: wrong size of data " + packet.data.length);
            return PARSE_ERROR_MALFORMED_PACKET;
        }

        final int error = packet.data[0] & 0xFF;
        if (error != 0) {
            if (DBG) Log.d(TAG, "parse-ctrl-rsp: error occurred! " + error);
            onErrorOccurred(HomeDevice.Error.CANT_CONTROL);
            return PARSE_OK_ERROR_RECEIVED;
        }

        final int state = packet.data[1] & 0xFF;
        parseSingleLightStateByte(state, outProps);

        return PARSE_OK_ACTION_PERFORMED;
    }

    private int makeSingleLightStateByte(PropertyMap props) {
        final boolean isOn = props.get(HomeDevice.PROP_ONOFF, Boolean.class);
        final boolean dimSupported = props.get(Light.PROP_DIM_SUPPORTED, Boolean.class);
        final int dimLevel = props.get(Light.PROP_CUR_DIM_LEVEL, Integer.class);

        int state = 0;
        if (isOn) state |= (1 << 0);
        if (dimSupported) state |= (1 << 1);
        state |= (dimLevel & 0x0F) << 4;

        return state;
    }

    private void parseSingleLightStateByte(int state, PropertyMap outProps) {
        final boolean isOn = (state & 0x01) != 0;
        final boolean dimSupported = (state & 0x02) != 0;
        final int dimLevel = (state >> 4) & 0x0F;

        outProps.put(HomeDevice.PROP_ONOFF, isOn);
        outProps.put(Light.PROP_DIM_SUPPORTED, dimSupported);
        outProps.put(Light.PROP_CUR_DIM_LEVEL, dimLevel);
    }


    private int makeSingleToneStateByte(PropertyMap props) {
        int toneLevel = props.get(Light.PROP_CUR_TONE_LEVEL, Integer.class);
        return clamp("toneLevel", toneLevel, LIGHT_LEVEL_MIN, LIGHT_LEVEL_MAX) & 0x0F;
    }

    private void parseSingleToneStateByte(int state, PropertyMap outProps) {
        final int toneLevel = state & 0x0F;
        if (toneLevel > 0) {
            outProps.put(Light.PROP_CUR_TONE_LEVEL, toneLevel);
            outProps.put(Light.PROP_TONE_SUPPORTED, true);
        }
    }

    private int getToneOrder(int index) {
        int zeroBased = index - 1;
        if (zeroBased < 0) return -1;
        if (((mToneSupportedFlagsInGroup >> zeroBased) & 0x01) == 0) return -1;
        int order = 0;
        for (int i = 0; i < zeroBased; i++) {
            if (((mToneSupportedFlagsInGroup >> i) & 0x01) != 0) order++;
        }
        return order;
    }

    private void parseToneControlData(int data, PropertyMap outProps) {
        int toneLevel = data & 0x0F;
        if (toneLevel > 0) {
            outProps.put(Light.PROP_CUR_TONE_LEVEL, clamp("toneLevel", toneLevel, LIGHT_LEVEL_MIN, LIGHT_LEVEL_MAX));
        }
    }

    protected boolean onToneControlTask(PropertyMap reqProps, PropertyMap outProps) {
        final int toneLevel = (int) reqProps.get(Light.PROP_CUR_TONE_LEVEL).getValue();
        sendPacket(createPacket(CMD_TONE_CONTROL_REQ, (byte)(clamp("toneLevel", toneLevel, LIGHT_LEVEL_MIN, LIGHT_LEVEL_MAX) & 0x0F)));
        return true;
    }

    @Override
    protected KSPacket makeControlReq(PropertyMap props) {
        KSAddress address = (KSAddress)getAddress();
        if (address.getDeviceSubId().isAll())
            return makeAllControlReq(props);
        else if (address.getDeviceSubId().isFullOfGroup())
            return makeGroupControlReq(props);
        return makeSingleControlReq(props);
    }

    private KSPacket makeAllControlReq(PropertyMap props) {
        final boolean isOn = (Boolean) props.get(HomeDevice.PROP_ONOFF).getValue();
        return createPacket(CMD_LIGHT_ALL_CONTROL_REQ, (byte)(isOn ? 0x01 : 0x00));
    }

    private KSPacket makeGroupControlReq(PropertyMap props) {
        final boolean isOn = (Boolean) props.get(HomeDevice.PROP_ONOFF).getValue();
        return createPacket(CMD_GROUP_CONTROL_REQ, (byte)(isOn ? 0x01 : 0x00));
    }

    private KSPacket makeSingleControlReq(PropertyMap props) {
        final boolean isOn = (Boolean) props.get(HomeDevice.PROP_ONOFF).getValue();
        final int dimLevel = (int) props.get(Light.PROP_CUR_DIM_LEVEL).getValue();
        // TODO: check if the dimLevel is between min and max
        // TODO: assert if dimLevel is within 0x0 ~ 0xF
        return createPacket(CMD_SINGLE_CONTROL_REQ, makeLightControlData(isOn, dimLevel));
    }

    private byte[] makeLightControlData(boolean isOn, int dimLevel) {
        byte[] data = new byte[1];
        data[0] = (byte)(((isOn) ? 1 : 0) | ((dimLevel & 0x0F) << 4));
        return data;
    }

    private void parseLightControlData(byte[] data, PropertyMap outProps) {
        final boolean isOn = ((data[0] & 0x01) == 1);
        final int dimLevel = ((data[0] >> 4) & 0x0F);
        outProps.put(HomeDevice.PROP_ONOFF, isOn);
        outProps.put(Light.PROP_CUR_DIM_LEVEL, dimLevel);
    }
}
