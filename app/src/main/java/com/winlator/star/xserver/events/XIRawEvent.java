package com.winlator.star.xserver.events;

import com.winlator.star.xconnector.XOutputStream;
import com.winlator.star.xconnector.XStreamLock;

import java.io.IOException;

/**
 * XInput 2 raw device event (xXIRawEvent, XI2proto.h), carried as a GenericEvent: RawMotion with
 * X/Y deltas, or RawButtonPress/RawButtonRelease with the button number in detail. Raw events
 * carry movement as the device reported it, not a pointer position, which is what Wine turns into
 * WM_INPUT for games that read the mouse through raw input.
 */
public class XIRawEvent extends Event {
    public static final short RAW_BUTTON_PRESS = 15;
    public static final short RAW_BUTTON_RELEASE = 16;
    public static final short RAW_MOTION = 17;

    private final byte extensionOpcode;
    private final short evtype;
    private final int deviceId;
    private final int detail;
    private final double dx, dy;

    private XIRawEvent(byte extensionOpcode, short evtype, int deviceId, int detail, double dx, double dy) {
        super(35); // GenericEvent
        this.extensionOpcode = extensionOpcode;
        this.evtype = evtype;
        this.deviceId = deviceId;
        this.detail = detail;
        this.dx = dx;
        this.dy = dy;
    }

    public static XIRawEvent motion(byte extensionOpcode, int deviceId, double dx, double dy) {
        return new XIRawEvent(extensionOpcode, RAW_MOTION, deviceId, 0, dx, dy);
    }

    public static XIRawEvent button(byte extensionOpcode, int deviceId, int button, boolean pressed) {
        return new XIRawEvent(extensionOpcode, pressed ? RAW_BUTTON_PRESS : RAW_BUTTON_RELEASE,
                deviceId, button, 0, 0);
    }

    @Override
    public void send(short sequenceNumber, XOutputStream outputStream) throws IOException {
        boolean motion = evtype == RAW_MOTION;
        // Motion: a one-word valuator mask (axes 0 and 1) then the two values twice, as
        // axisvalues and axisvalues_raw (FP3232 each). Buttons carry no valuators.
        int extraWords = motion ? (4 + 2 * 8 + 2 * 8) / 4 : 0;
        try (XStreamLock lock = outputStream.lock()) {
            outputStream.writeByte(code);
            outputStream.writeByte(extensionOpcode);
            outputStream.writeShort(sequenceNumber);
            outputStream.writeInt(extraWords);
            outputStream.writeShort(evtype);
            outputStream.writeShort((short)deviceId);
            outputStream.writeInt((int)System.currentTimeMillis());
            outputStream.writeInt(detail);
            outputStream.writeShort((short)deviceId); // sourceid
            outputStream.writeShort((short)(motion ? 1 : 0)); // valuators_len (4-byte units)
            outputStream.writeInt(0); // flags
            outputStream.writePad(4);
            if (motion) {
                outputStream.writeInt(0x3); // valuators 0 (X) and 1 (Y)
                outputStream.writeFP3232(dx);
                outputStream.writeFP3232(dy);
                outputStream.writeFP3232(dx);
                outputStream.writeFP3232(dy);
            }
        }
    }
}
