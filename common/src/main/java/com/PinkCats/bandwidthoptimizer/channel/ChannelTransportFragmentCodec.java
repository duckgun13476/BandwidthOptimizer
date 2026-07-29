package com.PinkCats.bandwidthoptimizer.channel;

import com.PinkCats.bandwidthoptimizer.channel.algorithm.ChannelTransportPayloadLimits;
import io.netty.buffer.Unpooled;
import net.minecraft.network.FriendlyByteBuf;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.zip.CRC32C;

/** Encodes complete BO frames into bounded outer payloads without touching the transport session. */
public final class ChannelTransportFragmentCodec {

    private static final int MAGIC_PACKET_ID = 0x1F_FFFF;
    private static final int FRAGMENT_FRAME_VERSION = 3;
    private static final int HEADER_SAFETY_BYTES = 64;
    private static final int MAX_FRAGMENT_COUNT = 16_384;

    private ChannelTransportFragmentCodec() {
    }

    public static List<byte[]> fragmentTransportFrame(byte[] transportFrameBytes, int payloadLimitBytes, int streamId) {
        byte[] safeFrameBytes = copyBytesOrEmpty(transportFrameBytes);
        validateFrameBytes(safeFrameBytes.length);
        if (payloadLimitBytes <= 0) {
            throw new IllegalArgumentException("fragment payload limit must be positive");
        }
        if (safeFrameBytes.length <= payloadLimitBytes) {
            return List.of(safeFrameBytes);
        }

        int fragmentBodyLimit = payloadLimitBytes - HEADER_SAFETY_BYTES;
        if (fragmentBodyLimit <= 0) {
            throw new IllegalArgumentException("fragment payload limit is too small: " + payloadLimitBytes);
        }
        int fragmentCount = divideRoundUp(safeFrameBytes.length, fragmentBodyLimit);
        if (fragmentCount <= 1 || fragmentCount > MAX_FRAGMENT_COUNT) {
            throw new IllegalArgumentException("fragment count out of range: " + fragmentCount);
        }

        int checksum = checksum(safeFrameBytes);
        List<byte[]> fragments = new ArrayList<>(fragmentCount);
        for (int index = 0; index < fragmentCount; index++) {
            int offset = index * fragmentBodyLimit;
            int length = Math.min(fragmentBodyLimit, safeFrameBytes.length - offset);
            byte[] fragmentBytes = Arrays.copyOfRange(safeFrameBytes, offset, offset + length);
            byte[] payloadBytes = encodeFragment(streamId, index, fragmentCount, safeFrameBytes.length, checksum, fragmentBytes);
            if (payloadBytes.length > payloadLimitBytes) {
                throw new IllegalStateException("fragment payload exceeds limit: " + payloadBytes.length + " > " + payloadLimitBytes);
            }
            fragments.add(payloadBytes);
        }
        return List.copyOf(fragments);
    }

    public static DecodedFragment tryDecodeFragment(byte[] payloadBytes) {
        if (payloadBytes == null || payloadBytes.length == 0) {
            return null;
        }

        FriendlyByteBuf buffer = new FriendlyByteBuf(Unpooled.wrappedBuffer(payloadBytes));
        try {
            if (buffer.readableBytes() < 1 || buffer.readVarInt() != MAGIC_PACKET_ID) {
                return null;
            }
            if (buffer.readableBytes() < 1 || buffer.readVarInt() != FRAGMENT_FRAME_VERSION) {
                return null;
            }

            int streamId = buffer.readVarInt();
            int fragmentIndex = buffer.readVarInt();
            int fragmentCount = buffer.readVarInt();
            int totalFrameBytes = buffer.readVarInt();
            int checksum = buffer.readInt();
            if (streamId < 0
                    || fragmentIndex < 0
                    || fragmentCount <= 1
                    || fragmentCount > MAX_FRAGMENT_COUNT
                    || fragmentIndex >= fragmentCount) {
                throw new IllegalStateException("fragment header index out of range");
            }
            validateFrameBytes(totalFrameBytes);
            if (!buffer.isReadable()) {
                throw new IllegalStateException("fragment payload is empty");
            }
            byte[] fragmentBytes = new byte[buffer.readableBytes()];
            buffer.readBytes(fragmentBytes);
            return new DecodedFragment(streamId, fragmentIndex, fragmentCount, totalFrameBytes, checksum, fragmentBytes);
        } finally {
            buffer.release();
        }
    }

    private static byte[] encodeFragment(
            int streamId,
            int fragmentIndex,
            int fragmentCount,
            int totalFrameBytes,
            int checksum,
            byte[] fragmentBytes
    ) {
        FriendlyByteBuf buffer = new FriendlyByteBuf(Unpooled.buffer());
        try {
            buffer.writeVarInt(MAGIC_PACKET_ID);
            buffer.writeVarInt(FRAGMENT_FRAME_VERSION);
            buffer.writeVarInt(streamId);
            buffer.writeVarInt(fragmentIndex);
            buffer.writeVarInt(fragmentCount);
            buffer.writeVarInt(totalFrameBytes);
            buffer.writeInt(checksum);
            buffer.writeBytes(fragmentBytes);
            byte[] payloadBytes = new byte[buffer.readableBytes()];
            buffer.getBytes(0, payloadBytes);
            return payloadBytes;
        } finally {
            buffer.release();
        }
    }

    private static int divideRoundUp(int value, int divisor) {
        return (value + divisor - 1) / divisor;
    }

    static int checksum(byte[] bytes) {
        CRC32C checksum = new CRC32C();
        checksum.update(bytes, 0, bytes.length);
        return (int) checksum.getValue();
    }

    static void validateFrameBytes(int frameBytes) {
        if (frameBytes <= 0 || frameBytes > ChannelTransportPayloadLimits.MAX_FRAGMENTED_TRANSPORT_FRAME_BYTES) {
            throw new IllegalArgumentException("fragmented transport frame bytes out of range: " + frameBytes);
        }
    }

    private static byte[] copyBytesOrEmpty(byte[] bytes) {
        return bytes == null ? new byte[0] : Arrays.copyOf(bytes, bytes.length);
    }

    public record DecodedFragment(
            int streamId,
            int fragmentIndex,
            int fragmentCount,
            int totalFrameBytes,
            int checksum,
            byte[] fragmentBytes
    ) {
        public DecodedFragment {
            fragmentBytes = fragmentBytes == null ? new byte[0] : Arrays.copyOf(fragmentBytes, fragmentBytes.length);
        }
    }
}
