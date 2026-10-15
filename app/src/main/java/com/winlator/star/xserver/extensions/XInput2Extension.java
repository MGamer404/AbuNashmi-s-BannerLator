package com.winlator.star.xserver.extensions;

import static com.winlator.star.xserver.XClientRequestHandler.RESPONSE_CODE_SUCCESS;

import android.util.Log;

import com.winlator.star.xconnector.XInputStream;
import com.winlator.star.xconnector.XOutputStream;
import com.winlator.star.xconnector.XStreamLock;
import com.winlator.star.xserver.XClient;
import com.winlator.star.xserver.XLock;
import com.winlator.star.xserver.XServer;
import com.winlator.star.xserver.errors.BadImplementation;
import com.winlator.star.xserver.errors.BadValue;
import com.winlator.star.xserver.errors.BadWindow;
import com.winlator.star.xserver.errors.XRequestError;
import com.winlator.star.xserver.events.XIRawEvent;

import java.io.IOException;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * XInput 2.2, the raw-input subset: enough for Wine's winex11 to find XI2 and select raw mouse
 * events, so games that read the mouse through raw input (WM_INPUT, usually mouse-look) get
 * movement. Without it every Wine log says "XInput 2.0 not available" and those games see none.
 *
 * Ported from GameNative's XInput2Extension (GPL-3.0; André Vitor, PR #1084 + #1354). Requests:
 * GetExtensionVersion (XI 1.x), XIGetClientPointer, XISelectEvents, XIQueryVersion, XIQueryDevice;
 * anything else gets BadImplementation so the client never waits on a reply that won't come. One
 * device, the master pointer (id 2), with 7 buttons and two relative axes. Events: RawMotion,
 * RawButtonPress, RawButtonRelease, fed by XServer from the app's pointer injection.
 * Registered only via XServer.enableXInput2().
 */
public class XInput2Extension implements Extension {
    private static final String TAG = "XInput2Extension";
    public static final byte MAJOR_OPCODE = -109; // -105 RandR, -106 GLX, -107 Composite, -108 RENDER here

    private static final short XI_MAJOR = 2;
    private static final short XI_MINOR = 2;
    private static final int XI_ALL_DEVICES = 0;
    private static final int XI_ALL_MASTER_DEVICES = 1;
    public static final int MASTER_POINTER_ID = 2;
    private static final int MASTER_KEYBOARD_ID = 3;
    private static final int RAW_BUTTON_PRESS_MASK = 1 << XIRawEvent.RAW_BUTTON_PRESS;
    private static final int RAW_BUTTON_RELEASE_MASK = 1 << XIRawEvent.RAW_BUTTON_RELEASE;
    private static final int RAW_MOTION_MASK = 1 << XIRawEvent.RAW_MOTION;
    private static final int POINTER_BUTTON_COUNT = 7;

    private static final byte GET_EXTENSION_VERSION = 1;
    private static final byte GET_CLIENT_POINTER = 45;
    private static final byte SELECT_EVENTS = 46;
    private static final byte QUERY_VERSION = 47;
    private static final byte QUERY_DEVICE = 48;

    private static final class Selection {
        XClient client;
        int windowId;
        int deviceId;
        int mask; // first mask word; the raw bits (15-17) all live there
    }

    private final List<Selection> selections = new CopyOnWriteArrayList<>();

    @Override
    public String getName() {
        return "XInputExtension";
    }

    @Override
    public byte getMajorOpcode() {
        return MAJOR_OPCODE;
    }

    // libXi installs converters for the 17 XI 1.x event codes from first_event, so it must be a
    // real block: 80..96 is clear of MIT-SHM/RandR (64..) and below the send-event bit (128).
    @Override
    public byte getFirstEventId() {
        return 80;
    }

    @Override
    public byte getFirstErrorId() {
        return Byte.MIN_VALUE + 40; // clear of RandR (+0..3), GLX (+4..) and RENDER (+32..36)
    }

    @Override
    public void handleRequest(XClient client, XInputStream inputStream, XOutputStream outputStream) throws IOException, XRequestError {
        switch (client.getRequestData()) {
            case GET_EXTENSION_VERSION:
                getExtensionVersion(client, inputStream, outputStream);
                break;
            case GET_CLIENT_POINTER:
                getClientPointer(client, inputStream, outputStream);
                break;
            case SELECT_EVENTS:
                try (XLock lock = client.xServer.lock(XServer.Lockable.WINDOW_MANAGER)) {
                    selectEvents(client, inputStream);
                }
                break;
            case QUERY_VERSION:
                queryVersion(client, inputStream, outputStream);
                break;
            case QUERY_DEVICE:
                queryDevice(client, inputStream, outputStream);
                break;
            default:
                Log.w(TAG, "unhandled minor opcode " + client.getRequestData());
                inputStream.skip(client.getRemainingRequestLength());
                throw new BadImplementation();
        }
    }

    private static void writeReplyHeader(XClient client, XOutputStream outputStream, int length) {
        outputStream.writeByte(RESPONSE_CODE_SUCCESS);
        outputStream.writeByte((byte)0);
        outputStream.writeShort(client.getSequenceNumber());
        outputStream.writeInt(length);
    }

    private static void getExtensionVersion(XClient client, XInputStream inputStream, XOutputStream outputStream) throws IOException {
        inputStream.skip(client.getRemainingRequestLength());
        try (XStreamLock lock = outputStream.lock()) {
            writeReplyHeader(client, outputStream, 0);
            outputStream.writeShort(XI_MAJOR);
            outputStream.writeShort((short)0);
            outputStream.writeByte((byte)1); // present
            outputStream.writePad(19);
        }
    }

    private static void getClientPointer(XClient client, XInputStream inputStream, XOutputStream outputStream) throws IOException {
        inputStream.skip(client.getRemainingRequestLength());
        try (XStreamLock lock = outputStream.lock()) {
            writeReplyHeader(client, outputStream, 0);
            outputStream.writeByte((byte)1); // set
            outputStream.writeByte((byte)0);
            outputStream.writeShort((short)MASTER_POINTER_ID);
            outputStream.writePad(20);
        }
    }

    private static void queryVersion(XClient client, XInputStream inputStream, XOutputStream outputStream) throws IOException {
        short clientMajor = inputStream.readShort();
        short clientMinor = inputStream.readShort();
        inputStream.skip(client.getRemainingRequestLength());
        boolean older = clientMajor < XI_MAJOR || (clientMajor == XI_MAJOR && clientMinor < XI_MINOR);
        try (XStreamLock lock = outputStream.lock()) {
            writeReplyHeader(client, outputStream, 0);
            outputStream.writeShort(older ? clientMajor : XI_MAJOR);
            outputStream.writeShort(older ? clientMinor : XI_MINOR);
            outputStream.writePad(20);
        }
    }

    private static void queryDevice(XClient client, XInputStream inputStream, XOutputStream outputStream) throws IOException {
        inputStream.skip(client.getRemainingRequestLength());
        byte[] name = "Virtual core pointer".getBytes(XServer.LATIN1_CHARSET);
        int namePad = (name.length + 3) & ~3;
        int buttonClassBytes = 8 + 4 + POINTER_BUTTON_COUNT * 4; // header, state mask, labels
        int valuatorClassBytes = 44;
        int length = (12 + namePad + buttonClassBytes + 2 * valuatorClassBytes) / 4;

        try (XStreamLock lock = outputStream.lock()) {
            writeReplyHeader(client, outputStream, length);
            outputStream.writeShort((short)1); // num_devices
            outputStream.writePad(22);

            // xXIDeviceInfo
            outputStream.writeShort((short)MASTER_POINTER_ID);
            outputStream.writeShort((short)1); // use: XIMasterPointer
            outputStream.writeShort((short)MASTER_KEYBOARD_ID); // attachment: paired keyboard
            outputStream.writeShort((short)3); // num_classes: buttons + 2 valuators
            outputStream.writeShort((short)name.length);
            outputStream.writeByte((byte)1); // enabled
            outputStream.writeByte((byte)0);
            outputStream.write(name);
            outputStream.writePad(namePad - name.length);

            // xXIButtonInfo, then the button state mask (all up) and one label atom per button
            outputStream.writeShort((short)1); // ButtonClass
            outputStream.writeShort((short)(buttonClassBytes / 4));
            outputStream.writeShort((short)MASTER_POINTER_ID);
            outputStream.writeShort((short)POINTER_BUTTON_COUNT);
            outputStream.writeInt(0);
            for (int i = 0; i < POINTER_BUTTON_COUNT; i++) outputStream.writeInt(0);

            for (int axis = 0; axis < 2; axis++) {
                // xXIValuatorInfo: relative axis, no range
                outputStream.writeShort((short)2); // ValuatorClass
                outputStream.writeShort((short)(valuatorClassBytes / 4));
                outputStream.writeShort((short)MASTER_POINTER_ID);
                outputStream.writeShort((short)axis);
                outputStream.writeInt(0); // label
                outputStream.writeFP3232(0); // min
                outputStream.writeFP3232(0); // max
                outputStream.writeFP3232(0); // value
                outputStream.writeInt(0); // resolution
                outputStream.writeByte((byte)0); // mode: Relative
                outputStream.writePad(3);
            }
        }
    }

    private void selectEvents(XClient client, XInputStream inputStream) throws XRequestError {
        int windowId = inputStream.readInt();
        int numMasks = inputStream.readShort() & 0xffff;
        inputStream.skip(2);
        if (numMasks == 0) {
            inputStream.skip(client.getRemainingRequestLength());
            throw new BadValue(numMasks);
        }
        if (client.xServer.windowManager.getWindow(windowId) == null) {
            inputStream.skip(client.getRemainingRequestLength());
            throw new BadWindow(windowId);
        }

        for (int i = 0; i < numMasks; i++) {
            int deviceId = inputStream.readShort() & 0xffff;
            int maskLen = inputStream.readShort() & 0xffff;
            int mask = 0;
            for (int word = 0; word < maskLen; word++) {
                int value = inputStream.readInt();
                if (word == 0) mask = value;
            }

            // XISelectEvents replaces the mask for this client/window/device; an empty mask removes it.
            final int fDeviceId = deviceId;
            selections.removeIf(old -> old.client == client && old.windowId == windowId && old.deviceId == fDeviceId);
            if (mask != 0) {
                Selection sel = new Selection();
                sel.client = client;
                sel.windowId = windowId;
                sel.deviceId = deviceId;
                sel.mask = mask;
                selections.add(sel);
            }
        }
        inputStream.skip(client.getRemainingRequestLength());
    }

    public void onClientDisconnected(XClient client) {
        selections.removeIf(sel -> sel.client == client);
    }

    private static boolean matches(Selection sel, int deviceId) {
        return sel.deviceId == XI_ALL_DEVICES
                || (sel.deviceId == XI_ALL_MASTER_DEVICES
                        && (deviceId == MASTER_POINTER_ID || deviceId == MASTER_KEYBOARD_ID))
                || sel.deviceId == deviceId;
    }

    public boolean hasRawSelections() {
        return !selections.isEmpty();
    }

    public void emitRawMotion(double dx, double dy) {
        if (dx == 0 && dy == 0) return;
        for (Selection sel : selections) {
            if ((sel.mask & RAW_MOTION_MASK) == 0 || !matches(sel, MASTER_POINTER_ID)) continue;
            sel.client.sendEvent(XIRawEvent.motion(MAJOR_OPCODE, MASTER_POINTER_ID, dx, dy));
        }
    }

    public void emitRawButton(int button, boolean pressed) {
        int bit = pressed ? RAW_BUTTON_PRESS_MASK : RAW_BUTTON_RELEASE_MASK;
        for (Selection sel : selections) {
            if ((sel.mask & bit) == 0 || !matches(sel, MASTER_POINTER_ID)) continue;
            sel.client.sendEvent(XIRawEvent.button(MAJOR_OPCODE, MASTER_POINTER_ID, button, pressed));
        }
    }
}
