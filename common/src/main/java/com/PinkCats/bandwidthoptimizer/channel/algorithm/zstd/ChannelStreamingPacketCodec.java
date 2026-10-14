package com.PinkCats.bandwidthoptimizer.channel.algorithm.zstd;

import com.PinkCats.bandwidthoptimizer.channel.algorithm.ChannelTransportPayloadLimits;
import io.netty.buffer.Unpooled;
import net.minecraft.network.FriendlyByteBuf;

// Streaming handler
final class ChannelStreamingPacketCodec {

    private static final int SINGLE_PACKET_COUNT = 1;
    static final int MAX_DECODED_PACKET_BATCH_BYTES = ChannelTransportPayloadLimits.MAX_STREAMING_FRAME_PAYLOAD_BYTES;

    private ChannelStreamingPacketCodec() {}

    static byte[] encodeFramedPacket(byte[] packetBytes) {
        int packetLength = packetBytes.length;
        int framedLength = framedPacketLength(packetLength);
        int batchLength = 1 + varIntSize(packetLength) + packetLength;
        byte[] result = new byte[framedLength];
        int offset = writeVarInt(result, 0, batchLength);
        result[offset++] = SINGLE_PACKET_COUNT;
        offset = writeVarInt(result, offset, packetLength);
        System.arraycopy(packetBytes, 0, result, offset, packetLength);
        return result;
    }

    static int framedPacketLength(int packetLength) {
        // Match the former ByteBuf capacity failure without wrapping int arithmetic or
        // trying to materialize an impossible intermediate batch first.
        long batchLength = 1L + varIntSize(packetLength) + packetLength;
        if (packetLength < 0 || batchLength > Integer.MAX_VALUE) {
            throw new IndexOutOfBoundsException("Channel streaming packet batch exceeds array capacity");
        }
        long frameLength = varIntSize((int) batchLength) + batchLength;
        if (frameLength > Integer.MAX_VALUE) {
            throw new IndexOutOfBoundsException("Channel streaming packet frame exceeds array capacity");
        }
        return (int) frameLength;
    }

    static byte[] decodeSinglePacketBatch(byte[] packetBatchBytes) {
        FriendlyByteBuf buffer = new FriendlyByteBuf(Unpooled.wrappedBuffer(packetBatchBytes));
        try {
            int packetCount = buffer.readVarInt();
            if (packetCount != SINGLE_PACKET_COUNT) {
                throw new IllegalStateException("Expected exactly one packet in channel transport batch, got " + packetCount);
            }

            int packetLength = buffer.readVarInt();
            if (packetLength < 0
                    || packetLength > MAX_DECODED_PACKET_BATCH_BYTES
                    || packetLength > buffer.readableBytes()) {
                throw new IllegalStateException("Channel transport packet length out of range: " + packetLength);
            }
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

    private static int varIntSize(int value) {
        int size = 1;
        while ((value & ~0x7F) != 0) { size++; value >>>= 7; }
        return size;
    }

    private static int writeVarInt(byte[] target, int offset, int value) {
        while ((value & ~0x7F) != 0) {
            target[offset++] = (byte) ((value & 0x7F) | 0x80);
            value >>>= 7;
        }
        target[offset++] = (byte) value;
        return offset;
    }

    record VarIntRead(int value, int nextIndex) {
    }
}
