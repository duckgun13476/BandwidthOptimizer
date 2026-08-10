package com.PinkCats.bandwidthoptimizer.channel;

import io.netty.buffer.Unpooled;
import net.minecraft.network.FriendlyByteBuf;

/** Internal recovery control carried by the existing BO transport payload. */
public final class ChannelTransportStreamingControlCodec {

    private static final int MAGIC_PACKET_ID = 0x1F_FFFF;
    private static final int CONTROL_FRAME_VERSION = 5;
    private static final int RECOVERY_REQUEST = 1;
    private static final int EPOCH_COMPLETE = 2;
    private static final int EPOCH_OK = 3;
    private static final int EPOCH_RESET = 4;

    private ChannelTransportStreamingControlCodec() {
    }

    public static byte[] encodeRecoveryRequest(int epoch, int expectedSequence) {
        if (epoch <= 0 || expectedSequence <= 0) {
            throw new IllegalArgumentException("Invalid streaming recovery point");
        }
        FriendlyByteBuf buffer = new FriendlyByteBuf(Unpooled.buffer());
        try {
            buffer.writeVarInt(MAGIC_PACKET_ID);
            buffer.writeVarInt(CONTROL_FRAME_VERSION);
            buffer.writeVarInt(RECOVERY_REQUEST);
            buffer.writeVarInt(epoch);
            buffer.writeVarInt(expectedSequence);
            byte[] bytes = new byte[buffer.readableBytes()];
            buffer.getBytes(0, bytes);
            return bytes;
        } finally {
            buffer.release();
        }
    }

    public static RecoveryRequest tryDecodeRecoveryRequest(byte[] frameBytes) {
        ControlMessage message = tryDecodeControlMessage(frameBytes);
        return message instanceof RecoveryRequest request ? request : null;
    }

    public static byte[] encodeEpochComplete(int epoch, int lastSequence) {
        return encodeControl(EPOCH_COMPLETE, epoch, lastSequence);
    }

    public static byte[] encodeEpochOk(int epoch, int lastSequence) {
        return encodeControl(EPOCH_OK, epoch, lastSequence);
    }

    public static byte[] encodeEpochReset(int epoch) {
        return encodeControl(EPOCH_RESET, epoch, 0);
    }

    public static ControlMessage tryDecodeControlMessage(byte[] frameBytes) {
        if (frameBytes == null || frameBytes.length == 0) {
            return null;
        }
        FriendlyByteBuf buffer = new FriendlyByteBuf(Unpooled.wrappedBuffer(frameBytes));
        try {
            if (buffer.readVarInt() != MAGIC_PACKET_ID || buffer.readVarInt() != CONTROL_FRAME_VERSION) {
                return null;
            }
            int type = buffer.readVarInt();
            if (type < RECOVERY_REQUEST || type > EPOCH_RESET) {
                throw new IllegalStateException("Unsupported streaming control request");
            }
            int epoch = buffer.readVarInt();
            int sequence = buffer.readVarInt();
            if (epoch <= 0 || (type != EPOCH_RESET && sequence <= 0) || (type == EPOCH_RESET && sequence != 0) || buffer.isReadable()) {
                throw new IllegalStateException("Invalid streaming recovery request");
            }
            return switch (type) {
                case RECOVERY_REQUEST -> new RecoveryRequest(epoch, sequence);
                case EPOCH_COMPLETE -> new EpochComplete(epoch, sequence);
                case EPOCH_OK -> new EpochOk(epoch, sequence);
                case EPOCH_RESET -> new EpochReset(epoch);
                default -> throw new IllegalStateException("Unsupported streaming control request");
            };
        } finally {
            buffer.release();
        }
    }

    private static byte[] encodeControl(int type, int epoch, int sequence) {
        if (epoch <= 0 || (type != EPOCH_RESET && sequence <= 0) || (type == EPOCH_RESET && sequence != 0)) {
            throw new IllegalArgumentException("Invalid streaming control point");
        }
        FriendlyByteBuf buffer = new FriendlyByteBuf(Unpooled.buffer());
        try {
            buffer.writeVarInt(MAGIC_PACKET_ID);
            buffer.writeVarInt(CONTROL_FRAME_VERSION);
            buffer.writeVarInt(type);
            buffer.writeVarInt(epoch);
            buffer.writeVarInt(sequence);
            byte[] bytes = new byte[buffer.readableBytes()];
            buffer.getBytes(0, bytes);
            return bytes;
        } finally {
            buffer.release();
        }
    }

    public interface ControlMessage {
    }

    public record RecoveryRequest(int epoch, int expectedSequence) implements ControlMessage {
    }

    public record EpochComplete(int epoch, int lastSequence) implements ControlMessage {
    }

    public record EpochOk(int epoch, int lastSequence) implements ControlMessage {
    }

    public record EpochReset(int epoch) implements ControlMessage {
    }
}
