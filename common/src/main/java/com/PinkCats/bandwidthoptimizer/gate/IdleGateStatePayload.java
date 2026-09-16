package com.PinkCats.bandwidthoptimizer.gate;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;

public record IdleGateStatePayload(
        int sequence,
        IdleGateMode mode,
        boolean hudVisible,
        long clientTimeMillis,
        long sourceGateEstimatedSavedBytes,
        long sourceGateEstimatedOutboundSavedBytes,
        long sourceGateSuppressedFrames
) {
    private static final int WIRE_VERSION = 2;
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
            output.writeLong(Math.max(sourceGateEstimatedSavedBytes, 0L));
            output.writeLong(Math.max(sourceGateEstimatedOutboundSavedBytes, 0L));
            output.writeLong(Math.max(sourceGateSuppressedFrames, 0L));
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
            if (version != 1 && version != WIRE_VERSION) {
                return active();
            }
            int sequence = input.readInt();
            IdleGateMode mode = IdleGateMode.byId(input.readUnsignedByte());
            boolean hudVisible = input.readBoolean();
            long clientTimeMillis = input.readLong();
            return new IdleGateStatePayload(
                    sequence,
                    mode,
                    hudVisible,
                    clientTimeMillis,
                    version >= 2 ? input.readLong() : 0L,
                    version >= 2 ? input.readLong() : 0L,
                    version >= 2 ? input.readLong() : 0L);
        } catch (IOException exception) {
            return active();
        }
    }

    public static IdleGateStatePayload active() {
        return new IdleGateStatePayload(0, IdleGateMode.ACTIVE, true, System.currentTimeMillis(), 0L, 0L, 0L);
    }
}
