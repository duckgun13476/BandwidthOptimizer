package com.PinkCats.bandwidthoptimizer.chunk.patch;

import com.PinkCats.bandwidthoptimizer.chunk.classify.ChunkHotspotKind;
import com.PinkCats.bandwidthoptimizer.chunk.classify.ChunkPacketDescriptor;
import com.PinkCats.bandwidthoptimizer.chunk.snapshot.ChunkLanePacketSnapshot;
import com.PinkCats.bandwidthoptimizer.chunk.snapshot.ChunkLaneSnapshot;
import com.PinkCats.bandwidthoptimizer.chunk.snapshot.ChunkShadowSnapshot;
import com.PinkCats.bandwidthoptimizer.chunk.snapshot.ChunkSnapshotFingerprint;
import com.PinkCats.bandwidthoptimizer.chunk.snapshot.ChunkSnapshotSemanticKeyResolver;
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
        ChunkLanePacketSnapshot basePacketSnapshot = resolveBasePacketSnapshot(chunkSnapshot, descriptor, semanticKey);
        return buildPatch(descriptor, semanticKey, basePacketSnapshot, targetPacketBytes);
    }




    //-----------------------------------------------------


    public static ChunkPatchBuildResult buildPatch(
            ChunkPacketDescriptor descriptor,
            String semanticKey,
            ChunkLanePacketSnapshot basePacketSnapshot,
            byte[] targetPacketBytes
    ) {
        if (targetPacketBytes == null) {
            return ChunkPatchBuildResult.unavailable("missing_target_packet");
        }

        ChunkPatchBuildResult genericPatchResult =
                ChunkGenericReplacePatchCodec.buildPatch(semanticKey, basePacketSnapshot, targetPacketBytes);
        if (descriptor == null || descriptor.hotspotKind() != ChunkHotspotKind.SECTION_BLOCKS_UPDATE) {
            return genericPatchResult;
        }

        ChunkPatchBuildResult sectionPatchResult =
                SectionBlocksChunkPatchCodec.buildPatch(semanticKey, basePacketSnapshot, targetPacketBytes);
        return preferSmallerPatch(sectionPatchResult, genericPatchResult);
    }

    private static ChunkLanePacketSnapshot resolveBasePacketSnapshot(
            ChunkShadowSnapshot chunkSnapshot,
            ChunkPacketDescriptor descriptor,
            String semanticKey
    ) {
        if (chunkSnapshot == null || descriptor == null) {
            return null;
        }

        ChunkLaneSnapshot laneSnapshot = chunkSnapshot.laneSnapshot(descriptor.laneKind());
        if (laneSnapshot == null) {
            return null;
        }
        return laneSnapshot.packet(semanticKey);
    }

    private static ChunkPatchBuildResult preferSmallerPatch(
            ChunkPatchBuildResult preferredPatchResult,
            ChunkPatchBuildResult fallbackPatchResult
    ) {
        if (preferredPatchResult == null || preferredPatchResult.patch() == null) {
            return fallbackPatchResult == null
                    ? ChunkPatchBuildResult.unavailable("patch_not_available")
                    : fallbackPatchResult;
        }
        if (fallbackPatchResult == null || fallbackPatchResult.patch() == null) {
            return preferredPatchResult;
        }
        return preferredPatchResult.encodedPatchBytesLength() <= fallbackPatchResult.encodedPatchBytesLength()
                ? preferredPatchResult
                : fallbackPatchResult;
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
