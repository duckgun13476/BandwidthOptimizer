package com.PinkCats.bandwidthoptimizer.channel.algorithm.zstd;

import io.netty.buffer.Unpooled;
import net.minecraft.network.FriendlyByteBuf;

// Streaming handler
final class ChannelStreamingPacketCodec {

    private static final int SINGLE_PACKET_COUNT = 1;

    private ChannelStreamingPacketCodec() {}

    static byte[] encodeFramedPacket(byte[] packetBytes) {
        byte[] packetBatchBytes = encodeSinglePacketBatch(packetBytes);
        FriendlyByteBuf buffer = new FriendlyByteBuf(Unpooled.buffer());
        try {
            buffer.writeVarInt(packetBatchBytes.length);
            buffer.writeBytes(packetBatchBytes);
            return copyReadableBytes(buffer);
        } finally {
            buffer.release();
        }
    }

    static byte[] decodeSinglePacketBatch(byte[] packetBatchBytes) {
        FriendlyByteBuf buffer = new FriendlyByteBuf(Unpooled.wrappedBuffer(packetBatchBytes));
        try {
            int packetCount = buffer.readVarInt();
            if (packetCount != SINGLE_PACKET_COUNT) {
                throw new IllegalStateException("Expected exactly one packet in channel transport batch, got " + packetCount);
            }

            int packetLength = buffer.readVarInt();
            byte[] packetBytes = new byte[packetLength];
            buffer.readBytes(packetBytes);
            if (buffer.isReadable()) {
                throw new IllegalStateException("Channel transport batch left unexpected trailing bytes: " + buffer.readableBytes());
            }
            return packetBytes;
        } finally {
            buffer.release();
        }
    }

    static VarIntRead tryReadVarInt(byte[] data) {
        int value = 0;
        int bitShift = 0;
        int index = 0;
        while (index < data.length) {
            int nextByte = data[index++] & 0xFF;
            value |= (nextByte & 0x7F) << bitShift;
            if ((nextByte & 0x80) == 0) {
                return new VarIntRead(value, index);
            }
            bitShift += 7;
            if (bitShift > 28) {
                throw new IllegalArgumentException("VarInt too large in channel streaming packet frame");
            }
        }
        return null;
    }

    private static byte[] encodeSinglePacketBatch(byte[] packetBytes) {
        FriendlyByteBuf buffer = new FriendlyByteBuf(Unpooled.buffer());
        try {
            buffer.writeVarInt(SINGLE_PACKET_COUNT);
            buffer.writeVarInt(packetBytes.length);
            buffer.writeBytes(packetBytes);
            return copyReadableBytes(buffer);
        } finally {
            buffer.release();
        }
    }

    private static byte[] copyReadableBytes(FriendlyByteBuf buffer) {
        byte[] bytes = new byte[buffer.readableBytes()];
        buffer.getBytes(0, bytes);
        return bytes;
    }

    record VarIntRead(int value, int nextIndex) {
    }
}
