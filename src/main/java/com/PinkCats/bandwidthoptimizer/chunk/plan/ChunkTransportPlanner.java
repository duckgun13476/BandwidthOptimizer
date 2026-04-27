package com.PinkCats.bandwidthoptimizer.chunk.plan;

import com.PinkCats.bandwidthoptimizer.chunk.classify.ChunkHotspotKind;
import com.PinkCats.bandwidthoptimizer.chunk.classify.packet.ChunkPacketCoordinate;
import com.PinkCats.bandwidthoptimizer.chunk.classify.packet.ChunkPacketDescriptor;
import com.PinkCats.bandwidthoptimizer.chunk.patch.ChunkPatchBuilder;
import com.PinkCats.bandwidthoptimizer.chunk.protocol.hotspot.ChunkHotspotFrame;
import com.PinkCats.bandwidthoptimizer.chunk.protocol.hotspot.ChunkHotspotFrameCodec;
import com.PinkCats.bandwidthoptimizer.chunk.protocol.hotspot.ChunkHotspotFrameOp;
import com.PinkCats.bandwidthoptimizer.chunk.snapshot.ChunkSnapshotFingerprint;
import com.PinkCats.bandwidthoptimizer.chunk.PeerState.ChunkPeerChunkStateSnapshot;
import com.PinkCats.bandwidthoptimizer.chunk.store.global.ChunkGlobalStoreObservation;

public final class ChunkTransportPlanner {

    private static final long MAX_DELTA_PACKETS_BEFORE_REFRESH = 64L;
    private static final long DELTA_BYTES_REFRESH_MULTIPLIER = 2L;
    private static final int ENVELOPE_MAGIC_BYTES = 8;
    private static final int UNAVAILABLE_ESTIMATED_BYTES = -1;
    public static final String WATCH_BOUNDARY_REUSE_PROBE_REASON =
            "reuse_cached_full_snapshot_after_watch_boundary";

    private ChunkTransportPlanner() {}

    // Planning which way chunk packet selects: full/ref/patch/bypass。
    public static ChunkPlanDecision planOutboundTransport(
            ChunkPacketDescriptor descriptor,
            ChunkSnapshotFingerprint snapshotFingerprint,
            ChunkPeerChunkStateSnapshot chunkSnapshot,
            ChunkGlobalStoreObservation storeObservation
    ) {
        return planOutboundTransport(
                descriptor,
                snapshotFingerprint,
                chunkSnapshot,
                storeObservation,
                ChunkPatchBuilder.ChunkPatchBuildResult.unavailable("patch_not_evaluated")
        );
    }

    public static ChunkPlanDecision planOutboundTransport(
            ChunkPacketDescriptor descriptor,
            ChunkSnapshotFingerprint snapshotFingerprint,
            ChunkPeerChunkStateSnapshot chunkSnapshot,
            ChunkGlobalStoreObservation storeObservation,
            ChunkPatchBuilder.ChunkPatchBuildResult patchBuildResult
    ) {
        if (descriptor == null || snapshotFingerprint == null || !descriptor.hasChunkCoordinate()) {
            return buildFallbackDecision(descriptor, snapshotFingerprint, "missing_chunk_or_snapshot_state");
        }

        long nextFullSnapshotVersion = resolvePublishedFullSnapshotVersion(chunkSnapshot, snapshotFingerprint);
        ChunkPlanCostEstimate costEstimate = estimatePlanCosts(
                descriptor,
                snapshotFingerprint,
                chunkSnapshot,
                patchBuildResult,
                nextFullSnapshotVersion
        );

        if (descriptor.hotspotKind() == ChunkHotspotKind.FULL_CHUNK) {
            return buildFullChunkDecision(
                    descriptor,
                    snapshotFingerprint,
                    chunkSnapshot,
                    storeObservation,
                    costEstimate,
                    nextFullSnapshotVersion
            );
        }

        return buildDeltaDecision(
                descriptor,
                snapshotFingerprint,
                chunkSnapshot,
                storeObservation,
                patchBuildResult,
                costEstimate
        );
    }


    private static ChunkPlanDecision buildFullChunkDecision(
            ChunkPacketDescriptor descriptor,
            ChunkSnapshotFingerprint snapshotFingerprint,
            ChunkPeerChunkStateSnapshot chunkSnapshot,
            ChunkGlobalStoreObservation storeObservation,
            ChunkPlanCostEstimate costEstimate,
            long nextFullSnapshotVersion
    ) {

        if (shouldUseWatchBoundaryReuseProbe(chunkSnapshot, snapshotFingerprint, costEstimate)) {
            return buildDecision(
                    ChunkPlanDecisionKind.PUBLISH_REF,
                    WATCH_BOUNDARY_REUSE_PROBE_REASON,
                    descriptor,
                    snapshotFingerprint,
                    chunkSnapshot,
                    storeObservation,
                    Math.max(chunkSnapshot == null ? 0L : chunkSnapshot.fullSnapshotVersion(), 0L),
                    costEstimate
            );
        }

        if (requiresFullReplayBeforeDelta(chunkSnapshot)) {
            return buildDecision(
                    ChunkPlanDecisionKind.PUBLISH_FULL,
                    buildFullReason(chunkSnapshot, snapshotFingerprint),
                    descriptor,
                    snapshotFingerprint,
                    chunkSnapshot,
                    storeObservation,
                    nextFullSnapshotVersion,
                    costEstimate
            );
        }

        if (shouldUseReference(chunkSnapshot, snapshotFingerprint)
                && costEstimate.refTransportBytes() > 0
                && costEstimate.refTransportBytes() < costEstimate.fullTransportBytes()) {
            return buildDecision(
                    ChunkPlanDecisionKind.PUBLISH_REF,
                    "reuse_acknowledged_full_snapshot",
                    descriptor,
                    snapshotFingerprint,
                    chunkSnapshot,
                    storeObservation,
                    Math.max(chunkSnapshot == null ? 0L : chunkSnapshot.fullSnapshotVersion(), 0L),
                    costEstimate
            );
        }

        if (shouldUseInFlightReferenceBeforeAck(chunkSnapshot, snapshotFingerprint)
                && costEstimate.refTransportBytes() > 0
                && costEstimate.refTransportBytes() < costEstimate.fullTransportBytes()) {
            return buildDecision(
                    ChunkPlanDecisionKind.PUBLISH_REF,
                    "reuse_inflight_full_snapshot_before_ack",
                    descriptor,
                    snapshotFingerprint,
                    chunkSnapshot,
                    storeObservation,
                    Math.max(chunkSnapshot == null ? 0L : chunkSnapshot.fullSnapshotVersion(), 0L),
                    costEstimate
            );
        }

        return buildDecision(
                ChunkPlanDecisionKind.PUBLISH_FULL,
                buildFullReason(chunkSnapshot, snapshotFingerprint),
                descriptor,
                snapshotFingerprint,
                chunkSnapshot,
                storeObservation,
                nextFullSnapshotVersion,
                costEstimate
        );
    }


    private static ChunkPlanDecision buildDeltaDecision(
            ChunkPacketDescriptor descriptor,
            ChunkSnapshotFingerprint snapshotFingerprint,
            ChunkPeerChunkStateSnapshot chunkSnapshot,
            ChunkGlobalStoreObservation storeObservation,
            ChunkPatchBuilder.ChunkPatchBuildResult patchBuildResult,
            ChunkPlanCostEstimate costEstimate
    ) {
        long currentFullSnapshotVersion = chunkSnapshot == null ? 0L : chunkSnapshot.fullSnapshotVersion();
        if (!hasKnownPublishedSnapshot(chunkSnapshot)) {
            return buildDecision(
                    ChunkPlanDecisionKind.BYPASS,
                    "delta_before_full_snapshot",
                    descriptor,
                    snapshotFingerprint,
                    chunkSnapshot,
                    storeObservation,
                    0L,
                    costEstimate
            );
        }

        if (requiresFullReplayBeforeDelta(chunkSnapshot)) {
            return buildDecision(
                    ChunkPlanDecisionKind.BYPASS,
                    "delta_waiting_full_replay_after_watch_boundary",
                    descriptor,
                    snapshotFingerprint,
                    chunkSnapshot,
                    storeObservation,
                    currentFullSnapshotVersion,
                    costEstimate
            );
        }

        boolean receiverAcknowledgedCurrentFullSnapshot = hasAcknowledgedCurrentFullSnapshot(chunkSnapshot);
        boolean allowPatchBeforeAck = canAllowPatchBeforeAck(descriptor, chunkSnapshot);
        if (!receiverAcknowledgedCurrentFullSnapshot && !allowPatchBeforeAck) {
            return buildDecision(
                    ChunkPlanDecisionKind.BYPASS,
                    "delta_before_receiver_ack",
                    descriptor,
                    snapshotFingerprint,
                    chunkSnapshot,
                    storeObservation,
                    currentFullSnapshotVersion,
                    costEstimate
            );
        }

        if (shouldForceRefresh(chunkSnapshot)) {
            return buildDecision(
                    ChunkPlanDecisionKind.BYPASS,
                    buildRefreshBoundaryReason(chunkSnapshot),
                    descriptor,
                    snapshotFingerprint,
                    chunkSnapshot,
                    storeObservation,
                    currentFullSnapshotVersion,
                    costEstimate
            );
        }

        if (shouldUsePatch(descriptor, patchBuildResult, costEstimate)) {
            return buildDecision(
                    ChunkPlanDecisionKind.PUBLISH_PATCH,
                    buildPatchReason(descriptor, patchBuildResult, receiverAcknowledgedCurrentFullSnapshot),
                    descriptor,
                    snapshotFingerprint,
                    chunkSnapshot,
                    storeObservation,
                    currentFullSnapshotVersion,
                    costEstimate
            );
        }

        return buildDecision(
                ChunkPlanDecisionKind.BYPASS,
                buildDeltaBypassReason(
                        descriptor,
                        patchBuildResult,
                        costEstimate,
                        receiverAcknowledgedCurrentFullSnapshot,
                        allowPatchBeforeAck
                ),
                descriptor,
                snapshotFingerprint,
                chunkSnapshot,
                storeObservation,
                currentFullSnapshotVersion,
                costEstimate
        );
    }

    private static ChunkPlanDecision buildDecision(
            ChunkPlanDecisionKind decisionKind,
            String reason,
            ChunkPacketDescriptor descriptor,
            ChunkSnapshotFingerprint snapshotFingerprint,
            ChunkPeerChunkStateSnapshot chunkSnapshot,
            ChunkGlobalStoreObservation storeObservation,
            long fullSnapshotVersion,
            ChunkPlanCostEstimate costEstimate
    ) {
        ChunkPlanCostEstimate resolvedCostEstimate = costEstimate == null
                ? ChunkPlanCostEstimate.unavailable(snapshotFingerprint == null ? 0 : Math.max(snapshotFingerprint.encodedBytes(), 0))
                : costEstimate;
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
                Math.max(snapshotFingerprint.encodedBytes(), 0),
                resolvedCostEstimate.bypassBytes(),
                resolvedCostEstimate.fullTransportBytes(),
                resolvedCostEstimate.refTransportBytes(),
                resolvedCostEstimate.patchTransportBytes(),
                resolvedCostEstimate.selectedBytes(decisionKind)
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
        ChunkPlanCostEstimate costEstimate = ChunkPlanCostEstimate.unavailable(encodedBytes);
        return new ChunkPlanDecision(
                ChunkPlanDecisionKind.BYPASS,
                reason,
                false, 0L, 0L, 0L, 0L, 0L,
                false, 0L, "", "",
                payloadHash,
                payloadShortHash,
                laneName,
                chunkText,
                encodedBytes,
                costEstimate.bypassBytes(),
                costEstimate.fullTransportBytes(),
                costEstimate.refTransportBytes(),
                costEstimate.patchTransportBytes(),
                costEstimate.selectedBytes(ChunkPlanDecisionKind.BYPASS)
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

    private static boolean requiresFullReplayBeforeDelta(ChunkPeerChunkStateSnapshot chunkSnapshot) {
        return chunkSnapshot != null
                && chunkSnapshot.knownSnapshotPublished()
                && chunkSnapshot.fullReplayRequiredBeforeDelta();
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

    private static boolean shouldUseWatchBoundaryReuseProbe(
            ChunkPeerChunkStateSnapshot chunkSnapshot,
            ChunkSnapshotFingerprint snapshotFingerprint,
            ChunkPlanCostEstimate costEstimate
    ) {
        return requiresFullReplayBeforeDelta(chunkSnapshot)
                && sameSnapshotHash(chunkSnapshot, snapshotFingerprint)
                && costEstimate != null
                && costEstimate.refTransportBytes() > 0
                && (costEstimate.fullTransportBytes() <= 0
                || costEstimate.refTransportBytes() < costEstimate.fullTransportBytes());
    }

    private static boolean shouldUseInFlightReferenceBeforeAck(
            ChunkPeerChunkStateSnapshot chunkSnapshot,
            ChunkSnapshotFingerprint snapshotFingerprint
    ) {
        return hasKnownPublishedSnapshot(chunkSnapshot)
                && !hasAcknowledgedCurrentFullSnapshot(chunkSnapshot)
                && chunkSnapshot != null
                && !chunkSnapshot.fullReplayRequiredBeforeDelta()
                && chunkSnapshot.fullSnapshotVersion() > 0L
                && sameSnapshotHash(chunkSnapshot, snapshotFingerprint);
    }

    private static boolean shouldUsePatch(
            ChunkPacketDescriptor descriptor,
            ChunkPatchBuilder.ChunkPatchBuildResult patchBuildResult,
            ChunkPlanCostEstimate costEstimate
    ) {
        return isPatchLaneEnabled(descriptor)
                && patchBuildResult != null
                && patchBuildResult.patch() != null
                && costEstimate != null
                && costEstimate.patchTransportBytes() > 0
                && costEstimate.patchTransportBytes() < costEstimate.bypassBytes()
                && costEstimate.patchTransportBytes() < costEstimate.fullTransportBytes();
    }

    private static String buildDeltaBypassReason(
            ChunkPacketDescriptor descriptor,
            ChunkPatchBuilder.ChunkPatchBuildResult patchBuildResult,
            ChunkPlanCostEstimate costEstimate,
            boolean receiverAcknowledgedCurrentFullSnapshot,
            boolean allowPatchBeforeAck
    ) {
        if (!isPatchLaneEnabled(descriptor)) {
            return "patch_lane_not_enabled_yet";
        }

        if (!receiverAcknowledgedCurrentFullSnapshot && allowPatchBeforeAck) {
            return buildPatchBeforeAckBypassReason(descriptor, patchBuildResult, costEstimate);
        }

        if (patchBuildResult == null || patchBuildResult.patch() == null) {
            if (patchBuildResult == null || patchBuildResult.reason() == null || patchBuildResult.reason().isBlank()) {
                return "delta_patch_unavailable";
            }
            return patchBuildResult.reason();
        }

        if (costEstimate == null || costEstimate.patchTransportBytes() <= 0) {
            return "delta_patch_estimate_unavailable";
        }

        if (costEstimate.patchTransportBytes() >= costEstimate.bypassBytes()) {
            return "patch_not_smaller_than_bypass";
        }

        if (costEstimate.patchTransportBytes() >= costEstimate.fullTransportBytes()) {
            return "patch_not_smaller_than_transport_full";
        }

        if (patchBuildResult.reason() == null || patchBuildResult.reason().isBlank()) {
            return "delta_patch_unavailable";
        }
        return patchBuildResult.reason();
    }


    private static String buildPatchReason(
            ChunkPacketDescriptor descriptor,
            ChunkPatchBuilder.ChunkPatchBuildResult patchBuildResult,
            boolean receiverAcknowledgedCurrentFullSnapshot
    ) {
        if (!receiverAcknowledgedCurrentFullSnapshot) {
            return resolvePatchReasonPrefix(descriptor) + "_patch_after_published_full_before_ack";
        }
        if (patchBuildResult == null || patchBuildResult.reason() == null || patchBuildResult.reason().isBlank()) {
            return "patch_transport_smaller_than_bypass";
        }
        return patchBuildResult.reason();
    }

    private static boolean canAllowPatchBeforeAck(
            ChunkPacketDescriptor descriptor,
            ChunkPeerChunkStateSnapshot chunkSnapshot
    ) {
        return descriptor != null
                && isPatchLaneEnabled(descriptor)
                && hasKnownPublishedSnapshot(chunkSnapshot)
                && chunkSnapshot != null
                && chunkSnapshot.fullSnapshotVersion() > 0L;
    }

    private static String buildPatchBeforeAckBypassReason(
            ChunkPacketDescriptor descriptor,
            ChunkPatchBuilder.ChunkPatchBuildResult patchBuildResult,
            ChunkPlanCostEstimate costEstimate
    ) {
        String reasonPrefix = resolvePatchReasonPrefix(descriptor);
        if (patchBuildResult == null || patchBuildResult.patch() == null) {
            if (patchBuildResult == null || patchBuildResult.reason() == null || patchBuildResult.reason().isBlank()) {
                return reasonPrefix + "_patch_unavailable_before_ack";
            }
            return patchBuildResult.reason();
        }

        if (costEstimate == null || costEstimate.patchTransportBytes() <= 0) {
            return reasonPrefix + "_patch_estimate_unavailable_before_ack";
        }

        if (costEstimate.patchTransportBytes() >= costEstimate.bypassBytes()) {
            return reasonPrefix + "_patch_not_smaller_than_bypass_before_ack";
        }

        if (costEstimate.patchTransportBytes() >= costEstimate.fullTransportBytes()) {
            return reasonPrefix + "_patch_not_smaller_than_transport_full_before_ack";
        }

        return reasonPrefix + "_patch_waiting_preconditions_before_ack";
    }


    private static String resolvePatchReasonPrefix(ChunkPacketDescriptor descriptor) {
        if (descriptor != null && descriptor.hotspotKind() == ChunkHotspotKind.SECTION_BLOCKS_UPDATE) {
            return "section";
        }
        if (descriptor != null && descriptor.hotspotKind() == ChunkHotspotKind.LIGHT_UPDATE) {
            return "light";
        }
        if (descriptor != null && descriptor.hotspotKind() == ChunkHotspotKind.BLOCK_UPDATE) {
            return "block";
        }
        if (descriptor != null && descriptor.hotspotKind() == ChunkHotspotKind.BLOCK_ENTITY_UPDATE) {
            return "block_entity";
        }
        return "delta";
    }

    private static boolean shouldForceRefresh(ChunkPeerChunkStateSnapshot chunkSnapshot) {
        if (chunkSnapshot == null) {
            return false;
        }

        if (chunkSnapshot.deltaPacketCountSinceFullSnapshot() >= MAX_DELTA_PACKETS_BEFORE_REFRESH) {
            return true;
        }

        int lastFullSnapshotBytes = Math.max(chunkSnapshot.lastFullSnapshotEncodedBytes(), 0);
        if (lastFullSnapshotBytes <= 0) {
            return false;
        }

        return chunkSnapshot.deltaBytesSinceFullSnapshot() >= lastFullSnapshotBytes * DELTA_BYTES_REFRESH_MULTIPLIER;
    }

    private static String buildRefreshBoundaryReason(ChunkPeerChunkStateSnapshot chunkSnapshot) {
        if (chunkSnapshot != null
                && chunkSnapshot.deltaPacketCountSinceFullSnapshot() >= MAX_DELTA_PACKETS_BEFORE_REFRESH) {
            return "delta_packet_budget_exceeded_wait_full_chunk_refresh";
        }
        return "delta_byte_budget_exceeded_wait_full_chunk_refresh";
    }
   
    private static String buildFullReason(
            ChunkPeerChunkStateSnapshot chunkSnapshot,
            ChunkSnapshotFingerprint snapshotFingerprint
    ) {
        if (!hasKnownPublishedSnapshot(chunkSnapshot) || chunkSnapshot.fullSnapshotVersion() <= 0L) {
            return "initial_full_snapshot";
        }

        if (chunkSnapshot != null && chunkSnapshot.fullReplayRequiredBeforeDelta()) {
            if (sameSnapshotHash(chunkSnapshot, snapshotFingerprint)) {
                return "await_receiver_ack_after_watch_boundary";
            }
            return "refresh_full_snapshot_after_watch_boundary";
        }

        if (!hasAcknowledgedCurrentFullSnapshot(chunkSnapshot)
                && sameSnapshotHash(chunkSnapshot, snapshotFingerprint)) {
            return "await_receiver_ack_for_full_snapshot";
        }

        if (sameSnapshotHash(chunkSnapshot, snapshotFingerprint)) {
            return "resend_full_snapshot";
        }

        return "refresh_full_snapshot";
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
        if (hotspotKind == null || chunkSnapshot == null) {
            return 0L;
        }

        if (hotspotKind == ChunkHotspotKind.LIGHT_UPDATE) {
            return chunkSnapshot.lightLaneVersion();
        }

        if (hotspotKind == ChunkHotspotKind.SECTION_BLOCKS_UPDATE) {
            return chunkSnapshot.sectionBlocksLaneVersion();
        }

        if (hotspotKind == ChunkHotspotKind.BLOCK_UPDATE) {
            return chunkSnapshot.blockLaneVersion();
        }

        if (hotspotKind == ChunkHotspotKind.BLOCK_ENTITY_UPDATE) {
            return chunkSnapshot.blockEntityLaneVersion();
        }

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

    private static ChunkPlanCostEstimate estimatePlanCosts(
            ChunkPacketDescriptor descriptor,
            ChunkSnapshotFingerprint snapshotFingerprint,
            ChunkPeerChunkStateSnapshot chunkSnapshot,
            ChunkPatchBuilder.ChunkPatchBuildResult patchBuildResult,
            long nextFullSnapshotVersion
    ) {
        int bypassBytes = snapshotFingerprint == null ? 0 : Math.max(snapshotFingerprint.encodedBytes(), 0);
        long stableFullSnapshotVersion = Math.max(chunkSnapshot == null ? 0L : chunkSnapshot.fullSnapshotVersion(), 0L);
        long fullCandidateSnapshotVersion = descriptor.hotspotKind() == ChunkHotspotKind.FULL_CHUNK
                ? Math.max(nextFullSnapshotVersion, 0L)
                : stableFullSnapshotVersion;
        int fullTransportBytes = estimateTransportBytes(
                descriptor,
                snapshotFingerprint,
                chunkSnapshot,
                ChunkPlanDecisionKind.PUBLISH_FULL,
                fullCandidateSnapshotVersion,
                bypassBytes,
                "plan_cost_full"
        );
        int refTransportBytes = descriptor.hotspotKind() == ChunkHotspotKind.FULL_CHUNK
                ? estimateTransportBytes(
                descriptor,
                snapshotFingerprint,
                chunkSnapshot,
                ChunkPlanDecisionKind.PUBLISH_REF,
                stableFullSnapshotVersion,
                0,
                "plan_cost_ref"
        )
                : UNAVAILABLE_ESTIMATED_BYTES;
        int patchTransportBytes = isPatchLaneEnabled(descriptor)
                && patchBuildResult != null
                && patchBuildResult.patch() != null
                ? estimateTransportBytes(
                descriptor,
                snapshotFingerprint,
                chunkSnapshot,
                ChunkPlanDecisionKind.PUBLISH_PATCH,
                stableFullSnapshotVersion,
                patchBuildResult.encodedPatchBytesLength(),
                "plan_cost_patch"
        )
                : UNAVAILABLE_ESTIMATED_BYTES;
        return new ChunkPlanCostEstimate(
                bypassBytes,
                fullTransportBytes,
                refTransportBytes,
                patchTransportBytes
        );
    }

    private static boolean isPatchLaneEnabled(ChunkPacketDescriptor descriptor) {
        return descriptor != null
                && (descriptor.hotspotKind() == ChunkHotspotKind.LIGHT_UPDATE
                || descriptor.hotspotKind() == ChunkHotspotKind.SECTION_BLOCKS_UPDATE
                || descriptor.hotspotKind() == ChunkHotspotKind.BLOCK_UPDATE
                || descriptor.hotspotKind() == ChunkHotspotKind.BLOCK_ENTITY_UPDATE);
    }

    private static int estimateTransportBytes(
            ChunkPacketDescriptor descriptor,
            ChunkSnapshotFingerprint snapshotFingerprint,
            ChunkPeerChunkStateSnapshot chunkSnapshot,
            ChunkPlanDecisionKind decisionKind,
            long fullSnapshotVersion,
            int payloadBytes,
            String reason
    ) {
        if (descriptor == null || descriptor.hotspotKind() == null || descriptor.laneKind() == null) {
            return UNAVAILABLE_ESTIMATED_BYTES;
        }

        ChunkHotspotFrame frame = new ChunkHotspotFrame(
                ChunkHotspotFrameCodec.PROTOCOL_VERSION,
                mapOperation(decisionKind),
                chunkSnapshot == null ? 0L : chunkSnapshot.epoch(),
                chunkSnapshot == null ? 0L : chunkSnapshot.lastObservedChannelPacketCount(),
                descriptor.protocolName() == null ? "PLAY" : descriptor.protocolName(),
                descriptor.packetClassName() == null ? "<unknown>" : descriptor.packetClassName(),
                descriptor.hotspotKind(),
                descriptor.laneKind(),
                descriptor.coordinate() == null ? ChunkPacketCoordinate.unknown() : descriptor.coordinate(),
                decisionKind == ChunkPlanDecisionKind.PUBLISH_REF ? 0 : safeEncodedBytes(snapshotFingerprint),
                Math.max(fullSnapshotVersion, 0L),
                resolveLaneVersion(descriptor.hotspotKind(), chunkSnapshot),
                resolveBaseSnapshotHash(decisionKind, chunkSnapshot, snapshotFingerprint),
                snapshotFingerprint == null || snapshotFingerprint.hashHex() == null ? "" : snapshotFingerprint.hashHex(),
                chunkSnapshot == null ? 0L : chunkSnapshot.deltaBytesSinceFullSnapshot(),
                reason == null ? "" : reason
        );
        byte[] frameBytes = ChunkHotspotFrameCodec.encodeFrame(frame);
        int safePayloadBytes = Math.max(payloadBytes, 0);
        return ENVELOPE_MAGIC_BYTES
                + computeVarIntBytes(frameBytes.length)
                + frameBytes.length
                + computeVarIntBytes(safePayloadBytes)
                + safePayloadBytes;
    }

    private static String resolveBaseSnapshotHash(
            ChunkPlanDecisionKind decisionKind,
            ChunkPeerChunkStateSnapshot chunkSnapshot,
            ChunkSnapshotFingerprint snapshotFingerprint
    ) {
        if (decisionKind == ChunkPlanDecisionKind.PUBLISH_FULL) {
            return snapshotFingerprint == null || snapshotFingerprint.hashHex() == null ? "" : snapshotFingerprint.hashHex();
        }
        return chunkSnapshot == null || chunkSnapshot.knownSnapshotHash() == null ? "" : chunkSnapshot.knownSnapshotHash();
    }

    private static ChunkHotspotFrameOp mapOperation(ChunkPlanDecisionKind decisionKind) {
        if (decisionKind == ChunkPlanDecisionKind.PUBLISH_FULL) {
            return ChunkHotspotFrameOp.PUBLISH_FULL;
        }
        if (decisionKind == ChunkPlanDecisionKind.PUBLISH_REF) {
            return ChunkHotspotFrameOp.PUBLISH_REF;
        }
        return ChunkHotspotFrameOp.PUBLISH_PATCH;
    }

    private static int safeEncodedBytes(ChunkSnapshotFingerprint snapshotFingerprint) {
        return snapshotFingerprint == null ? 0 : Math.max(snapshotFingerprint.encodedBytes(), 0);
    }

    private static int computeVarIntBytes(int value) {
        int remaining = value;
        int byteCount = 1;
        while ((remaining & -128) != 0) {
            remaining >>>= 7;
            byteCount++;
        }
        return byteCount;
    }

    private record ChunkPlanCostEstimate(
            int bypassBytes,
            int fullTransportBytes,
            int refTransportBytes,
            int patchTransportBytes
    ) {

        private static ChunkPlanCostEstimate unavailable(int bypassBytes) {
            return new ChunkPlanCostEstimate(
                    Math.max(bypassBytes, 0),
                    UNAVAILABLE_ESTIMATED_BYTES,
                    UNAVAILABLE_ESTIMATED_BYTES,
                    UNAVAILABLE_ESTIMATED_BYTES
            );
        }

        private int selectedBytes(ChunkPlanDecisionKind decisionKind) {
            if (decisionKind == ChunkPlanDecisionKind.PUBLISH_FULL) {
                return this.fullTransportBytes;
            }
            if (decisionKind == ChunkPlanDecisionKind.PUBLISH_REF) {
                return this.refTransportBytes;
            }
            if (decisionKind == ChunkPlanDecisionKind.PUBLISH_PATCH) {
                return this.patchTransportBytes;
            }
            return this.bypassBytes;
        }
    }
}
