package com.PinkCats.bandwidthoptimizer.chunk.patch;

import com.PinkCats.bandwidthoptimizer.channel.algorithm.ChannelTransportPayloadLimits;
import net.minecraft.network.FriendlyByteBuf;

final class ChunkPatchDecodeBounds {

    static final int MAX_TARGET_BYTES = ChannelTransportPayloadLimits.MAX_SINGLE_PACKET_BYTES;
    static final int MAX_SECTION_ENTRIES = 4096;
    private static final int MAX_HASH_BYTES = 64;

    private ChunkPatchDecodeBounds() {}

    static int requireTargetLength(int length) {
        if (length < 0 || length > MAX_TARGET_BYTES) {
            throw new IllegalStateException("Chunk patch target length out of range: " + length);
        }
        return length;
    }

    static byte[] readHashBytes(FriendlyByteBuf buffer) {
        return readBytes(buffer, "hash", MAX_HASH_BYTES);
    }

    static byte[] readPayloadBytes(FriendlyByteBuf buffer) {
        return readBytes(buffer, "payload", MAX_TARGET_BYTES);
    }

    static byte[] readReplacementBytes(FriendlyByteBuf buffer, int maximumBytes) {
        return readBytes(buffer, "replacement", Math.max(maximumBytes, 0));
    }

    static int checkedRangeEnd(int firstLength, int secondLength, int limit, String label) {
        if (firstLength < 0 || secondLength < 0 || limit < 0) {
            throw new IllegalStateException(label + " contains a negative length");
        }
        long end = (long) firstLength + secondLength;
        if (end > limit) {
            throw new IllegalStateException(label + " exceeds its base length");
        }
        return (int) end;
    }

    static int checkedTargetLength(int prefixLength, int replacementLength, int suffixLength) {
        if (prefixLength < 0 || replacementLength < 0 || suffixLength < 0) {
            throw new IllegalStateException("Chunk patch target contains a negative length");
        }
        long length = (long) prefixLength + replacementLength + suffixLength;
        if (length > MAX_TARGET_BYTES) {
            throw new IllegalStateException("Chunk patch target exceeds the packet limit");
        }
        return (int) length;
    }

    private static byte[] readBytes(FriendlyByteBuf buffer, String field, int maximumBytes) {
        int length = buffer.readVarInt();
        if (length < 0 || length > maximumBytes || length > buffer.readableBytes()) {
            throw new IllegalStateException("Chunk patch " + field + " length out of range: " + length);
        }
        byte[] bytes = new byte[length];
        buffer.readBytes(bytes);
        return bytes;
    }
}
