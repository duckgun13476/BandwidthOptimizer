package com.PinkCats.bandwidthoptimizer.chunk.plan;

import com.PinkCats.bandwidthoptimizer.Bandwidthoptimizer;
import com.PinkCats.bandwidthoptimizer.chunk.classify.ChunkHotspotKind;
import com.PinkCats.bandwidthoptimizer.chunk.classify.ChunkPacketDescriptor;
import com.PinkCats.bandwidthoptimizer.chunk.snapshot.ChunkSnapshotFingerprint;
import com.PinkCats.bandwidthoptimizer.chunk.state.peer.ChunkPeerChunkStateSnapshot;
import com.PinkCats.bandwidthoptimizer.chunk.state.peer.ChunkPeerObservationSnapshot;
import com.PinkCats.bandwidthoptimizer.chunk.state.peer.ChunkPeerStateSnapshot;
import com.PinkCats.bandwidthoptimizer.chunk.store.global.ChunkGlobalStoreObservation;

public final class ChunkPlanPreviewService {

    private static final long MAX_DELTA_PACKETS_BEFORE_REFRESH = 64L;
    private static final long DELTA_BYTES_REFRESH_MULTIPLIER = 2L;

    private ChunkPlanPreviewService() {}

    // decision-making white-boxify
    public static ChunkPlanDecision previewOutboundObservation(
            ChunkPeerObservationSnapshot observation,
            ChunkPacketDescriptor descriptor
    ) {
        ChunkPeerStateSnapshot channelSnapshot = observation == null ? null : observation.channelState();
        if (channelSnapshot == null || descriptor == null) {
            return null;
        }

        ChunkPlanDecision decision = buildDecision(
                descriptor,
                observation.chunkState(),
                observation.snapshotFingerprint(),
                observation.storeObservation()
        );
        if (shouldLog(channelSnapshot)) {
            Bandwidthoptimizer.LOGGER.info(
                    "[ChunkPlan][Preview] channel={}, epoch={}, observedPackets={}, {}",
                    channelSnapshot.channelId(),
                    channelSnapshot.epoch(),
                    channelSnapshot.observedPacketCount(),
                    decision.summaryText()
            );
        }
        return decision;
    }


    private static ChunkPlanDecision buildDecision(
            ChunkPacketDescriptor descriptor,
            ChunkPeerChunkStateSnapshot chunkSnapshot,
            ChunkSnapshotFingerprint snapshotFingerprint,
            ChunkGlobalStoreObservation storeObservation
    ) {
        if (descriptor == null || !descriptor.hasChunkCoordinate() || chunkSnapshot == null || snapshotFingerprint == null) {
            return buildFallbackDecision(descriptor, snapshotFingerprint, "missing_chunk_or_snapshot_state");
        }

        if (descriptor.hotspotKind() == ChunkHotspotKind.FULL_CHUNK) {
            return buildFullChunkDecision(chunkSnapshot, snapshotFingerprint, storeObservation, descriptor);
        }

        if (!chunkSnapshot.knownSnapshotPublished()) {
            return buildDecision(
                    ChunkPlanDecisionKind.BYPASS,
                    "delta_before_full_snapshot",
                    chunkSnapshot,
                    snapshotFingerprint,
                    storeObservation,
                    descriptor
            );
        }

        if (shouldForceRefresh(chunkSnapshot)) {
            return buildDecision(
                    ChunkPlanDecisionKind.PUBLISH_FULL,
                    buildRefreshReason(chunkSnapshot),
                    chunkSnapshot,
                    snapshotFingerprint,
                    storeObservation,
                    descriptor
            );
        }

        return buildDecision(
                ChunkPlanDecisionKind.PUBLISH_PATCH,
                "delta_after_known_snapshot",
                chunkSnapshot,
                snapshotFingerprint,
                storeObservation,
                descriptor
        );
    }






    // Full chunk scenery
    private static ChunkPlanDecision buildFullChunkDecision(
            ChunkPeerChunkStateSnapshot chunkSnapshot,
            ChunkSnapshotFingerprint snapshotFingerprint,
            ChunkGlobalStoreObservation storeObservation,
            ChunkPacketDescriptor descriptor
    ) {
        boolean knownHashMatchesCurrent = snapshotFingerprint.hashHex().equals(chunkSnapshot.knownSnapshotHash());
        boolean repeatedObservation = chunkSnapshot.totalObservedPacketCount() > 1L;
        if (knownHashMatchesCurrent && repeatedObservation && storeObservation != null && storeObservation.existingBeforeObserve()) {
            return buildDecision(
                    ChunkPlanDecisionKind.PUBLISH_REF,
                    "reuse_known_full_snapshot",
                    chunkSnapshot,
                    snapshotFingerprint,
                    storeObservation,
                    descriptor
            );
        }

        String reason = chunkSnapshot.fullSnapshotVersion() <= 1L
                ? "initial_full_snapshot"
                : "refresh_full_snapshot";
        return buildDecision(
                ChunkPlanDecisionKind.PUBLISH_FULL,
                reason,
                chunkSnapshot,
                snapshotFingerprint,
                storeObservation,
                descriptor
        );
    }

    // Bypass scenery
    private static ChunkPlanDecision buildFallbackDecision(
            ChunkPacketDescriptor descriptor,
            ChunkSnapshotFingerprint snapshotFingerprint,
            String reason
    ) {
        String laneName = descriptor == null ? "<unknown>" : descriptor.laneKind().logName();
        String chunkText = descriptor == null || descriptor.coordinate() == null
                ? "<unknown>"
                : descriptor.coordinate().logText();
        String payloadHash = snapshotFingerprint == null ? "" : snapshotFingerprint.hashHex();
        String payloadShortHash = snapshotFingerprint == null ? "" : snapshotFingerprint.shortHash();
        return new ChunkPlanDecision(
                ChunkPlanDecisionKind.BYPASS,
                reason, false,
                0L, 0L, 0L, 0L, 0L, false, 0L,
                "", "",
                payloadHash,
                payloadShortHash,
                laneName,
                chunkText,
                snapshotFingerprint == null ? 0 : Math.max(snapshotFingerprint.encodedBytes(), 0)
        );
    }

    private static ChunkPlanDecision buildDecision(
            ChunkPlanDecisionKind decisionKind,
            String reason,
            ChunkPeerChunkStateSnapshot chunkSnapshot,
            ChunkSnapshotFingerprint snapshotFingerprint,
            ChunkGlobalStoreObservation storeObservation,
            ChunkPacketDescriptor descriptor
    ) {
        return new ChunkPlanDecision(
                decisionKind,
                reason,
                chunkSnapshot.knownSnapshotPublished(),
                chunkSnapshot.fullSnapshotVersion(),
                chunkSnapshot.mutationVersion(),
                resolveLaneVersion(descriptor.hotspotKind(), chunkSnapshot),
                chunkSnapshot.deltaPacketCountSinceFullSnapshot(),
                chunkSnapshot.deltaBytesSinceFullSnapshot(),
                storeObservation != null && storeObservation.existingBeforeObserve(),
                storeObservation == null ? 0L : storeObservation.observationCount(),
                chunkSnapshot.knownSnapshotHash(),
                chunkSnapshot.knownSnapshotShortHash(),
                snapshotFingerprint.hashHex(),
                snapshotFingerprint.shortHash(),
                descriptor.laneKind().logName(),
                chunkSnapshot.chunkKey().logText(),
                Math.max(snapshotFingerprint.encodedBytes(), 0)
        );
    }

    // 这个函数根据 delta 预算是否膨胀来判断是否应该回退到强制 full refresh，避免 replay 型历史无限累积。
    private static boolean shouldForceRefresh(ChunkPeerChunkStateSnapshot chunkSnapshot) {
        if (chunkSnapshot == null)
            return false;

        if (chunkSnapshot.deltaPacketCountSinceFullSnapshot() >= MAX_DELTA_PACKETS_BEFORE_REFRESH)
            return true;


        int lastFullSnapshotBytes = Math.max(chunkSnapshot.lastFullSnapshotEncodedBytes(), 0);
        if (lastFullSnapshotBytes <= 0)
            return false;

        return chunkSnapshot.deltaBytesSinceFullSnapshot() >= lastFullSnapshotBytes * DELTA_BYTES_REFRESH_MULTIPLIER;
    }


    private static String buildRefreshReason(ChunkPeerChunkStateSnapshot chunkSnapshot) {
        if (chunkSnapshot.deltaPacketCountSinceFullSnapshot() >= MAX_DELTA_PACKETS_BEFORE_REFRESH)
            return "delta_packet_budget_exceeded";

        return "delta_byte_budget_exceeded";
    }


    private static long resolveLaneVersion(ChunkHotspotKind hotspotKind, ChunkPeerChunkStateSnapshot chunkSnapshot) {
        if (hotspotKind == null || chunkSnapshot == null)
            return 0L;

        if (hotspotKind == ChunkHotspotKind.LIGHT_UPDATE)
            return chunkSnapshot.lightLaneVersion();

        if (hotspotKind == ChunkHotspotKind.SECTION_BLOCKS_UPDATE)
            return chunkSnapshot.sectionBlocksLaneVersion();

        if (hotspotKind == ChunkHotspotKind.BLOCK_UPDATE)
            return chunkSnapshot.blockLaneVersion();

        if (hotspotKind == ChunkHotspotKind.BLOCK_ENTITY_UPDATE)
            return chunkSnapshot.blockEntityLaneVersion();

        return 0L;
    }

    private static boolean shouldLog(ChunkPeerStateSnapshot snapshot) {
        long observedPacketCount = snapshot.observedPacketCount();
        return observedPacketCount <= 5L || observedPacketCount % 100L == 0L;
    }
}
