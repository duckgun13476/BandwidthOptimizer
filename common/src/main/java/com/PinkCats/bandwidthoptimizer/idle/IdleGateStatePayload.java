package com.PinkCats.bandwidthoptimizer.idle;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;

public record IdleGateStatePayload(
        int sequence,
        IdleGateMode mode,
        boolean hudVisible,
        long clientTimeMillis
) {
    private static final int WIRE_VERSION = 1;
    private static final int MAX_BYTES = 64;

    public IdleGateStatePayload {
        mode = mode == null ? IdleGateMode.ACTIVE : mode;
    }

    public byte[] toBytes() {
        try {
            ByteArrayOutputStream bytes = new ByteArrayOutputStream(16);
            DataOutputStream output = new DataOutputStream(bytes);
            output.writeByte(WIRE_VERSION);
            output.writeInt(sequence);
            output.writeByte(mode.id());
            output.writeBoolean(hudVisible);
            output.writeLong(clientTimeMillis);
            output.flush();
            return bytes.toByteArray();
        } catch (IOException exception) {
            throw new IllegalStateException("Failed to encode idle gate state", exception);
        }
    }

    public static IdleGateStatePayload fromBytes(byte[] bytes) {
        if (bytes == null || bytes.length <= 0 || bytes.length > MAX_BYTES) {
            return active();
        }
        try {
            DataInputStream input = new DataInputStream(new ByteArrayInputStream(bytes));
            int version = input.readUnsignedByte();
            if (version != WIRE_VERSION) {
                return active();
            }
            return new IdleGateStatePayload(
                    input.readInt(),
                    IdleGateMode.byId(input.readUnsignedByte()),
                    input.readBoolean(),
                    input.readLong());
        } catch (IOException exception) {
            return active();
        }
    }

    public static IdleGateStatePayload active() {
        return new IdleGateStatePayload(0, IdleGateMode.ACTIVE, true, System.currentTimeMillis());
    }
}
