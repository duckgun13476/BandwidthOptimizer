package com.PinkCats.bandwidthoptimizer.chunk.plan;

import com.PinkCats.bandwidthoptimizer.chunk.classify.ChunkHotspotKind;
import com.PinkCats.bandwidthoptimizer.chunk.classify.ChunkPacketDescriptor;
import com.PinkCats.bandwidthoptimizer.chunk.snapshot.ChunkSnapshotFingerprint;
import com.PinkCats.bandwidthoptimizer.chunk.state.peer.ChunkPeerChunkStateSnapshot;
import com.PinkCats.bandwidthoptimizer.chunk.store.global.ChunkGlobalStoreObservation;

public final class ChunkTransportPlanner {

    private static final long MAX_DELTA_PACKETS_BEFORE_REFRESH = 64L;
    private static final long DELTA_BYTES_REFRESH_MULTIPLIER = 2L;

    private ChunkTransportPlanner() {}

    // Planning which way chunk packet selects: full/ref/patch/bypass。
    public static ChunkPlanDecision planOutboundTransport(
            ChunkPacketDescriptor descriptor,
            ChunkSnapshotFingerprint snapshotFingerprint,
            ChunkPeerChunkStateSnapshot chunkSnapshot,
            ChunkGlobalStoreObservation storeObservation
    ) {
        if (descriptor == null || snapshotFingerprint == null || !descriptor.hasChunkCoordinate()) {
            return buildFallbackDecision(descriptor, snapshotFingerprint, "missing_chunk_or_snapshot_state");
        }

        if (descriptor.hotspotKind() == ChunkHotspotKind.FULL_CHUNK) {
            return buildFullChunkDecision(descriptor, snapshotFingerprint, chunkSnapshot, storeObservation);
        }

        if (!hasKnownPublishedSnapshot(chunkSnapshot)) {
            return buildDecision(
                    ChunkPlanDecisionKind.BYPASS,
                    "delta_before_full_snapshot",
                    descriptor,
                    snapshotFingerprint,
                    chunkSnapshot,
                    storeObservation,
                    0L
            );
        }

        if (!hasAcknowledgedCurrentFullSnapshot(chunkSnapshot)) {
            return buildDecision(
                    ChunkPlanDecisionKind.BYPASS,
                    "delta_before_receiver_ack",
                    descriptor,
                    snapshotFingerprint,
                    chunkSnapshot,
                    storeObservation,
                    chunkSnapshot.fullSnapshotVersion()
            );
        }

        if (shouldForceRefresh(chunkSnapshot)) {
            return buildDecision(
                    ChunkPlanDecisionKind.PUBLISH_FULL,
                    buildRefreshReason(chunkSnapshot),
                    descriptor,
                    snapshotFingerprint,
                    chunkSnapshot,
                    storeObservation,
                    resolvePublishedFullSnapshotVersion(chunkSnapshot, snapshotFingerprint)
            );
        }

        return buildDecision(
                ChunkPlanDecisionKind.PUBLISH_PATCH,
                "delta_after_acknowledged_full_snapshot",
                descriptor,
                snapshotFingerprint,
                chunkSnapshot,
                storeObservation,
                chunkSnapshot.fullSnapshotVersion()
        );
    }

    private static ChunkPlanDecision buildFullChunkDecision(
            ChunkPacketDescriptor descriptor,
            ChunkSnapshotFingerprint snapshotFingerprint,
            ChunkPeerChunkStateSnapshot chunkSnapshot,
            ChunkGlobalStoreObservation storeObservation
    ) {
        if (shouldUseReference(chunkSnapshot, snapshotFingerprint)) {
            return buildDecision(
                    ChunkPlanDecisionKind.PUBLISH_REF,
                    "reuse_acknowledged_full_snapshot",
                    descriptor,
                    snapshotFingerprint,
                    chunkSnapshot,
                    storeObservation,
                    chunkSnapshot.fullSnapshotVersion()
            );
        }

        return buildDecision(
                ChunkPlanDecisionKind.PUBLISH_FULL,
                buildFullReason(chunkSnapshot, snapshotFingerprint),
                descriptor,
                snapshotFingerprint,
                chunkSnapshot,
                storeObservation,
                resolvePublishedFullSnapshotVersion(chunkSnapshot, snapshotFingerprint)
        );
    }

    private static ChunkPlanDecision buildDecision(
            ChunkPlanDecisionKind decisionKind,
            String reason,
            ChunkPacketDescriptor descriptor,
            ChunkSnapshotFingerprint snapshotFingerprint,
            ChunkPeerChunkStateSnapshot chunkSnapshot,
            ChunkGlobalStoreObservation storeObservation,
            long fullSnapshotVersion
    ) {
        return new ChunkPlanDecision(
                decisionKind,
                reason,
                hasKnownPublishedSnapshot(chunkSnapshot),
                Math.max(fullSnapshotVersion, 0L),
                chunkSnapshot == null ? 0L : chunkSnapshot.mutationVersion(),
                resolveLaneVersion(descriptor.hotspotKind(), chunkSnapshot),
                chunkSnapshot == null ? 0L : chunkSnapshot.deltaPacketCountSinceFullSnapshot(),
                chunkSnapshot == null ? 0L : chunkSnapshot.deltaBytesSinceFullSnapshot(),
                storeObservation != null && storeObservation.existingBeforeObserve(),
                storeObservation == null ? 0L : storeObservation.observationCount(),
                chunkSnapshot == null ? "" : chunkSnapshot.knownSnapshotHash(),
                chunkSnapshot == null ? "" : chunkSnapshot.knownSnapshotShortHash(),
                snapshotFingerprint.hashHex(),
                snapshotFingerprint.shortHash(),
                descriptor.laneKind().logName(),
                descriptor.coordinate().logText(),
                Math.max(snapshotFingerprint.encodedBytes(), 0)
        );
    }

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
        int encodedBytes = snapshotFingerprint == null ? 0 : Math.max(snapshotFingerprint.encodedBytes(), 0);
        return new ChunkPlanDecision(
                ChunkPlanDecisionKind.BYPASS,
                reason, false,
                0L, 0L, 0L, 0L, 0L, false,
                0L, "", "",
                payloadHash,
                payloadShortHash,
                laneName,
                chunkText,
                encodedBytes
        );
    }

    private static boolean hasAcknowledgedCurrentFullSnapshot(ChunkPeerChunkStateSnapshot chunkSnapshot) {
        return chunkSnapshot != null
                && chunkSnapshot.receiverSnapshotAcknowledged()
                && chunkSnapshot.fullSnapshotVersion() > 0L
                && chunkSnapshot.fullSnapshotVersion() == chunkSnapshot.acknowledgedSnapshotVersion()
                && chunkSnapshot.knownSnapshotHash() != null
                && chunkSnapshot.knownSnapshotHash().equals(chunkSnapshot.acknowledgedSnapshotHash());
    }

    private static boolean shouldUseReference(
            ChunkPeerChunkStateSnapshot chunkSnapshot,
            ChunkSnapshotFingerprint snapshotFingerprint
    ) {
        return hasKnownPublishedSnapshot(chunkSnapshot)
                && hasAcknowledgedCurrentFullSnapshot(chunkSnapshot)
                && snapshotFingerprint != null
                && snapshotFingerprint.hashHex().equals(chunkSnapshot.knownSnapshotHash());
    }


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

    // null Reason helper
    private static String buildFullReason(
            ChunkPeerChunkStateSnapshot chunkSnapshot,
            ChunkSnapshotFingerprint snapshotFingerprint
    ) {
        if (!hasKnownPublishedSnapshot(chunkSnapshot) || chunkSnapshot.fullSnapshotVersion() <= 0L)
            return "initial_full_snapshot";

        if (!hasAcknowledgedCurrentFullSnapshot(chunkSnapshot)
                && sameSnapshotHash(chunkSnapshot, snapshotFingerprint))
            return "await_receiver_ack_for_full_snapshot";

        if (sameSnapshotHash(chunkSnapshot, snapshotFingerprint))
            return "resend_full_snapshot";

        return "refresh_full_snapshot";
    }

    // refresh Reason helper
    private static String buildRefreshReason(ChunkPeerChunkStateSnapshot chunkSnapshot) {
        if (chunkSnapshot != null
                && chunkSnapshot.deltaPacketCountSinceFullSnapshot() >= MAX_DELTA_PACKETS_BEFORE_REFRESH) {
            return "delta_packet_budget_exceeded";
        }

        return "delta_byte_budget_exceeded";
    }



    private static long resolvePublishedFullSnapshotVersion(
            ChunkPeerChunkStateSnapshot chunkSnapshot,
            ChunkSnapshotFingerprint snapshotFingerprint
    ) {
        if (snapshotFingerprint == null || snapshotFingerprint.hashHex() == null || snapshotFingerprint.hashHex().isBlank()) {
            return chunkSnapshot == null ? 0L : chunkSnapshot.fullSnapshotVersion();
        }

        if (!hasKnownPublishedSnapshot(chunkSnapshot)) {
            return 1L;
        }

        if (snapshotFingerprint.hashHex().equals(chunkSnapshot.knownSnapshotHash())) {
            return Math.max(chunkSnapshot.fullSnapshotVersion(), 1L);
        }

        return chunkSnapshot.fullSnapshotVersion() + 1L;
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


    private static boolean hasKnownPublishedSnapshot(ChunkPeerChunkStateSnapshot chunkSnapshot) {
        return chunkSnapshot != null && chunkSnapshot.knownSnapshotPublished();
    }


    private static boolean sameSnapshotHash(
            ChunkPeerChunkStateSnapshot chunkSnapshot,
            ChunkSnapshotFingerprint snapshotFingerprint
    ) {
        return chunkSnapshot != null
                && snapshotFingerprint != null
                && snapshotFingerprint.hashHex() != null
                && snapshotFingerprint.hashHex().equals(chunkSnapshot.knownSnapshotHash());
    }
}
