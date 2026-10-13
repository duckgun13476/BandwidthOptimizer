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
        long sourceGateSuppressedFrames,
        boolean watutStatsSupported,
        long watutClientSavedPayloadBytes,
        long watutClientSuppressedFrames,
        long watutClientSentPayloadBytes
) {
    private static final int WIRE_VERSION = 2;
    private static final int MAX_BYTES = 64;
    private static final int WATUT_EXTENSION = 0x57;

    public IdleGateStatePayload(int sequence, IdleGateMode mode, boolean hudVisible, long clientTimeMillis,
                                long saved, long outbound, long frames) {
        this(sequence, mode, hudVisible, clientTimeMillis, saved, outbound, frames, false, 0, 0, 0);
    }

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
            // v2 readers ignore this bounded trailing extension; the entire payload remains <=64 bytes.
            if (watutStatsSupported) {
                output.writeByte(WATUT_EXTENSION);
                output.writeLong(Math.max(watutClientSavedPayloadBytes, 0L));
                output.writeLong(Math.max(watutClientSuppressedFrames, 0L));
                output.writeLong(Math.max(watutClientSentPayloadBytes, 0L));
            }
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
            long saved = version >= 2 ? input.readLong() : 0L;
            long outbound = version >= 2 ? input.readLong() : 0L;
            long frames = version >= 2 ? input.readLong() : 0L;
            boolean supported = version == 2 && input.available() == 25 && input.readUnsignedByte() == WATUT_EXTENSION;
            return new IdleGateStatePayload(sequence, mode, hudVisible, clientTimeMillis, saved, outbound, frames,
                    supported, supported ? input.readLong() : 0L, supported ? input.readLong() : 0L,
                    supported ? input.readLong() : 0L);
        } catch (IOException exception) {
            return active();
        }
    }

    public static IdleGateStatePayload active() {
        return new IdleGateStatePayload(0, IdleGateMode.ACTIVE, true, System.currentTimeMillis(), 0L, 0L, 0L);
    }
}
