package com.winlator.star.xserver.extensions;

import static com.winlator.star.xserver.XClientRequestHandler.RESPONSE_CODE_SUCCESS;

import com.winlator.star.xconnector.XInputStream;
import com.winlator.star.xconnector.XOutputStream;
import com.winlator.star.xconnector.XStreamLock;
import com.winlator.star.xserver.XClient;
import com.winlator.star.xserver.errors.BadImplementation;
import com.winlator.star.xserver.errors.XRequestError;

import java.io.IOException;

/**
 * X Generic Event Extension (XGE) 1.0: one QueryVersion request. XInput 2 events travel as
 * GenericEvents; GameNative found libXi wants this advertised before it turns XI2 on in some
 * paths. Registered only with XInput 2, and only when asked for (BANNER_X11_XGE=1) until a device
 * test shows whether Wine needs it.
 */
public class GenericEventExtension implements Extension {
    public static final byte MAJOR_OPCODE = -110; // -109 is XInput2Extension in this tree

    @Override
    public String getName() {
        return "Generic Event Extension";
    }

    @Override
    public byte getMajorOpcode() {
        return MAJOR_OPCODE;
    }

    @Override
    public byte getFirstErrorId() {
        return 0;
    }

    @Override
    public byte getFirstEventId() {
        return 0;
    }

    @Override
    public void handleRequest(XClient client, XInputStream inputStream, XOutputStream outputStream) throws IOException, XRequestError {
        if (client.getRequestData() != 0) { // QueryVersion is the only request
            inputStream.skip(client.getRemainingRequestLength());
            throw new BadImplementation();
        }
        int clientMajor = inputStream.readShort() & 0xffff;
        int clientMinor = inputStream.readShort() & 0xffff;
        inputStream.skip(client.getRemainingRequestLength());
        int major = Math.min(clientMajor, 1);
        int minor = major < 1 ? clientMinor : 0;

        try (XStreamLock lock = outputStream.lock()) {
            outputStream.writeByte(RESPONSE_CODE_SUCCESS);
            outputStream.writeByte((byte)0);
            outputStream.writeShort(client.getSequenceNumber());
            outputStream.writeInt(0);
            outputStream.writeShort((short)major);
            outputStream.writeShort((short)minor);
            outputStream.writePad(20);
        }
    }
}
