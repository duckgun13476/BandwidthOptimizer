package com.PinkCats.bandwidthoptimizer.chunk.patch;

import com.PinkCats.bandwidthoptimizer.chunk.snapshot.lane.ChunkLanePacketSnapshot;
import io.netty.buffer.ByteBuf;
import io.netty.buffer.ByteBufUtil;
import io.netty.buffer.Unpooled;
import net.minecraft.network.FriendlyByteBuf;

import java.util.Arrays;

public final class ChunkGenericReplacePatchCodec {

    private ChunkGenericReplacePatchCodec() {}

    public static ChunkPatchBuilder.ChunkPatchBuildResult buildPatch(
            String semanticKey,
            ChunkLanePacketSnapshot basePacketSnapshot,
            byte[] targetPacketBytes
    ) {
        if (targetPacketBytes == null) {
            return ChunkPatchBuilder.ChunkPatchBuildResult.unavailable("missing_target_packet");
        }

        byte[] safeTargetPacketBytes = Arrays.copyOf(targetPacketBytes, targetPacketBytes.length);
        if (basePacketSnapshot != null && !basePacketSnapshot.hasOriginalPacketBytes()) {
            return ChunkPatchBuilder.ChunkPatchBuildResult.unavailable("base_packet_bytes_evicted");
        }
        byte[] basePacketBytes = basePacketSnapshot == null
                ? new byte[0]
                : basePacketSnapshot.copyOriginalPacketBytes();
        String basePayloadHash = basePacketSnapshot == null ? "" : basePacketSnapshot.payloadHash();

        int prefixLength = resolveCommonPrefixLength(basePacketBytes, safeTargetPacketBytes);
        int suffixLength = resolveCommonSuffixLength(basePacketBytes, safeTargetPacketBytes, prefixLength);
        int targetReplaceStart = prefixLength;
        int targetReplaceEndExclusive = safeTargetPacketBytes.length - suffixLength;
        int baseReplaceLength = basePacketBytes.length - prefixLength - suffixLength;
        byte[] replacementBytes = Arrays.copyOfRange(safeTargetPacketBytes, targetReplaceStart, targetReplaceEndExclusive);

        ChunkPatch chunkPatch = new ChunkPatch(
                ChunkPatchMode.GENERIC_REPLACE,
                semanticKey,
                basePayloadHash,
                safeTargetPacketBytes.length,
                encodePayload(prefixLength, baseReplaceLength, replacementBytes)
        );
        byte[] encodedPatchBytes = chunkPatch.encode();
        boolean beneficial = encodedPatchBytes.length < safeTargetPacketBytes.length;
        String reason = beneficial ? "patch_smaller_than_full" : "patch_not_smaller_than_full";
        if (basePacketSnapshot == null) {
            reason = beneficial ? "patch_from_empty_base" : "missing_base_packet";
        }
        return new ChunkPatchBuilder.ChunkPatchBuildResult(chunkPatch, encodedPatchBytes, beneficial, reason);
    }

    public static byte[] applyPatch(ChunkPatch chunkPatch, byte[] basePacketBytes) {
        if (chunkPatch == null) {
            throw new IllegalArgumentException("chunkPatch must not be null");
        }

        byte[] safeBasePacketBytes = basePacketBytes == null
                ? new byte[0]
                : Arrays.copyOf(basePacketBytes, basePacketBytes.length);
        int targetLength = ChunkPatchDecodeBounds.requireTargetLength(chunkPatch.targetLength());
        GenericReplacePayload payload = decodePayload(chunkPatch.copyPatchPayloadBytes(), targetLength);

        int baseSuffixStart = ChunkPatchDecodeBounds.checkedRangeEnd(
                payload.prefixLength(),
                payload.baseReplaceLength(),
                safeBasePacketBytes.length,
                "Chunk patch base range"
        );
        int suffixLength = safeBasePacketBytes.length - baseSuffixStart;
        int expectedTargetLength = ChunkPatchDecodeBounds.checkedTargetLength(
                payload.prefixLength(),
                payload.replacementBytes().length,
                suffixLength
        );
        if (expectedTargetLength != targetLength) {
            throw new IllegalStateException(
                    "Chunk patch target length mismatch. expected="
                            + expectedTargetLength
                            + ", target="
                            + targetLength
            );
        }

        byte[] targetPacketBytes = new byte[targetLength];
        System.arraycopy(safeBasePacketBytes, 0, targetPacketBytes, 0, payload.prefixLength());
        System.arraycopy(
                payload.replacementBytes(),
                0,
                targetPacketBytes,
                payload.prefixLength(),
                payload.replacementBytes().length
        );
        System.arraycopy(
                safeBasePacketBytes,
                baseSuffixStart,
                targetPacketBytes,
                payload.prefixLength() + payload.replacementBytes().length,
                suffixLength
        );
        return targetPacketBytes;
    }

    private static byte[] encodePayload(int prefixLength, int baseReplaceLength, byte[] replacementBytes) {
        ByteBuf byteBuf = Unpooled.buffer();
        try {
            FriendlyByteBuf friendlyByteBuf = new FriendlyByteBuf(byteBuf);
            friendlyByteBuf.writeVarInt(Math.max(prefixLength, 0));
            friendlyByteBuf.writeVarInt(Math.max(baseReplaceLength, 0));
            byte[] safeReplacementBytes = replacementBytes == null ? new byte[0] : replacementBytes;
            friendlyByteBuf.writeVarInt(safeReplacementBytes.length);
            friendlyByteBuf.writeBytes(safeReplacementBytes);
            return ByteBufUtil.getBytes(byteBuf);
        } finally {
            byteBuf.release();
        }
    }

    private static GenericReplacePayload decodePayload(byte[] payloadBytes, int maximumReplacementBytes) {
        ByteBuf byteBuf = Unpooled.wrappedBuffer(payloadBytes == null ? new byte[0] : payloadBytes);
        try {
            FriendlyByteBuf friendlyByteBuf = new FriendlyByteBuf(byteBuf);
            int prefixLength = friendlyByteBuf.readVarInt();
            int baseReplaceLength = friendlyByteBuf.readVarInt();
            byte[] replacementBytes = ChunkPatchDecodeBounds.readReplacementBytes(
                    friendlyByteBuf,
                    maximumReplacementBytes
            );
            if (friendlyByteBuf.isReadable()) {
                throw new IllegalStateException("Generic replace patch left extra bytes: " + friendlyByteBuf.readableBytes());
            }
            return new GenericReplacePayload(prefixLength, baseReplaceLength, replacementBytes);
        } finally {
            byteBuf.release();
        }
    }

    private static int resolveCommonPrefixLength(byte[] basePacketBytes, byte[] targetPacketBytes) {
        int maxCommonLength = Math.min(basePacketBytes.length, targetPacketBytes.length);
        int prefixLength = 0;
        while (prefixLength < maxCommonLength && basePacketBytes[prefixLength] == targetPacketBytes[prefixLength]) {
            prefixLength++;
        }
        return prefixLength;
    }

    private static int resolveCommonSuffixLength(byte[] basePacketBytes, byte[] targetPacketBytes, int prefixLength) {
        int baseRemainingLength = basePacketBytes.length - prefixLength;
        int targetRemainingLength = targetPacketBytes.length - prefixLength;
        int maxCommonLength = Math.min(baseRemainingLength, targetRemainingLength);
        int suffixLength = 0;
        while (suffixLength < maxCommonLength) {
            int baseIndex = basePacketBytes.length - 1 - suffixLength;
            int targetIndex = targetPacketBytes.length - 1 - suffixLength;
            if (basePacketBytes[baseIndex] != targetPacketBytes[targetIndex]) {
                break;
            }
            suffixLength++;
        }
        return suffixLength;
    }

    private record GenericReplacePayload(
            int prefixLength,
            int baseReplaceLength,
            byte[] replacementBytes
    ) {

        private GenericReplacePayload {
            replacementBytes = replacementBytes == null ? new byte[0] : Arrays.copyOf(replacementBytes, replacementBytes.length);
        }
    }
}
