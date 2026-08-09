package com.PinkCats.bandwidthoptimizer.channel;

import io.netty.buffer.Unpooled;
import net.minecraft.network.FriendlyByteBuf;

/** Internal recovery control carried by the existing BO transport payload. */
public final class ChannelTransportStreamingControlCodec {

    private static final int MAGIC_PACKET_ID = 0x1F_FFFF;
    private static final int CONTROL_FRAME_VERSION = 4;
    private static final int RECOVERY_REQUEST = 1;

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
        if (frameBytes == null || frameBytes.length == 0) {
            return null;
        }
        FriendlyByteBuf buffer = new FriendlyByteBuf(Unpooled.wrappedBuffer(frameBytes));
        try {
            if (buffer.readVarInt() != MAGIC_PACKET_ID || buffer.readVarInt() != CONTROL_FRAME_VERSION) {
                return null;
            }
            if (buffer.readVarInt() != RECOVERY_REQUEST) {
                throw new IllegalStateException("Unsupported streaming control request");
            }
            int epoch = buffer.readVarInt();
            int expectedSequence = buffer.readVarInt();
            if (epoch <= 0 || expectedSequence <= 0 || buffer.isReadable()) {
                throw new IllegalStateException("Invalid streaming recovery request");
            }
            return new RecoveryRequest(epoch, expectedSequence);
        } finally {
            buffer.release();
        }
    }

    public record RecoveryRequest(int epoch, int expectedSequence) {
    }
}
