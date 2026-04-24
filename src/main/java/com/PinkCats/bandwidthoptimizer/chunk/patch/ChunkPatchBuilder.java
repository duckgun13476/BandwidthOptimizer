package com.PinkCats.bandwidthoptimizer.chunk.patch;

import com.PinkCats.bandwidthoptimizer.chunk.classify.ChunkPacketDescriptor;
import com.PinkCats.bandwidthoptimizer.chunk.snapshot.ChunkLanePacketSnapshot;
import com.PinkCats.bandwidthoptimizer.chunk.snapshot.ChunkLaneSnapshot;
import com.PinkCats.bandwidthoptimizer.chunk.snapshot.ChunkShadowSnapshot;
import com.PinkCats.bandwidthoptimizer.chunk.snapshot.ChunkSnapshotSemanticKeyResolver;
import com.PinkCats.bandwidthoptimizer.chunk.snapshot.ChunkSnapshotFingerprint;
import net.minecraft.network.protocol.Packet;

import java.util.Arrays;

public final class ChunkPatchBuilder {

    private ChunkPatchBuilder() {}


    public static ChunkPatchBuildResult buildPatchFromSnapshot(
            ChunkShadowSnapshot chunkSnapshot,
            ChunkPacketDescriptor descriptor,
            Packet<?> packet,
            byte[] targetPacketBytes,
            ChunkSnapshotFingerprint targetFingerprint
    ) {
        if (descriptor == null || packet == null || targetPacketBytes == null || targetFingerprint == null) {
            return ChunkPatchBuildResult.unavailable("missing_patch_inputs");
        }

        String semanticKey = ChunkSnapshotSemanticKeyResolver.resolveSemanticKey(descriptor, packet);
        ChunkLanePacketSnapshot basePacketSnapshot = null;
        if (chunkSnapshot != null) {
            ChunkLaneSnapshot laneSnapshot = chunkSnapshot.laneSnapshot(descriptor.laneKind());
            if (laneSnapshot != null) {
                basePacketSnapshot = laneSnapshot.packet(semanticKey);
            }
        }
        return buildPatch(semanticKey, basePacketSnapshot, targetPacketBytes, targetFingerprint);
    }




    //-----------------------------------------------------


    public static ChunkPatchBuildResult buildPatch(
            String semanticKey,
            ChunkLanePacketSnapshot basePacketSnapshot,
            byte[] targetPacketBytes,
            ChunkSnapshotFingerprint targetFingerprint
    ) {
        if (targetPacketBytes == null || targetFingerprint == null) {
            return ChunkPatchBuildResult.unavailable("missing_target_packet");
        }

        byte[] safeTargetPacketBytes = Arrays.copyOf(targetPacketBytes, targetPacketBytes.length);
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
                semanticKey,
                basePayloadHash,
                targetFingerprint.hashHex(),
                safeTargetPacketBytes.length,
                prefixLength,
                baseReplaceLength,
                replacementBytes
        );
        byte[] encodedPatchBytes = chunkPatch.encode();
        boolean beneficial = encodedPatchBytes.length < safeTargetPacketBytes.length;
        String reason = beneficial ? "patch_smaller_than_full" : "patch_not_smaller_than_full";
        if (basePacketSnapshot == null) {
            reason = beneficial ? "patch_from_empty_base" : "missing_base_packet";
        }
        return new ChunkPatchBuildResult(chunkPatch, encodedPatchBytes, beneficial, reason);
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

    public record ChunkPatchBuildResult(
            ChunkPatch patch,
            byte[] encodedPatchBytes,
            boolean beneficial,
            String reason
    ) {
        public ChunkPatchBuildResult {
            encodedPatchBytes = encodedPatchBytes == null
                    ? new byte[0]
                    : Arrays.copyOf(encodedPatchBytes, encodedPatchBytes.length);
            reason = reason == null ? "" : reason;
        }

        public byte[] copyEncodedPatchBytes() {
            return Arrays.copyOf(this.encodedPatchBytes, this.encodedPatchBytes.length);
        }

        public int encodedPatchBytesLength() {
            return this.encodedPatchBytes.length;
        }

        public static ChunkPatchBuildResult unavailable(String reason) {
            return new ChunkPatchBuildResult(null, new byte[0], false, reason);
        }
    }
}
