package com.PinkCats.bandwidthoptimizer.chunk.plan;

import com.PinkCats.bandwidthoptimizer.Bandwidthoptimizer;
import com.PinkCats.bandwidthoptimizer.chunk.classify.ChunkHotspotKind;
import com.PinkCats.bandwidthoptimizer.chunk.classify.packet.ChunkPacketDescriptor;
import com.PinkCats.bandwidthoptimizer.chunk.PeerState.ChunkPeerObservationSnapshot;
import com.PinkCats.bandwidthoptimizer.chunk.PeerState.ChunkPeerStateSnapshot;
import com.PinkCats.bandwidthoptimizer.debug.DebugRuntimeConfig;
import com.PinkCats.bandwidthoptimizer.experient.ExperientChunkHotspotPathRuntimeConfig;

public final class ChunkPlanPreviewService {

    private ChunkPlanPreviewService() {}

    // decision-making white-boxify
    public static void previewOutboundObservation(
            ChunkPeerObservationSnapshot observation,
            ChunkPacketDescriptor descriptor,
            ChunkPlanDecision decision
    ) {
        ChunkPeerStateSnapshot channelSnapshot = observation == null ? null : observation.channelState();
        if (channelSnapshot == null || descriptor == null || decision == null) {
            return;
        }

        if (!DebugRuntimeConfig.isDiagnoseEnabled()) {
            return;
        }
        if (shouldLog(channelSnapshot, decision)) {
            Bandwidthoptimizer.LOGGER.info(
                    "[ChunkPlan][Preview] channel={}, epoch={}, observedPackets={}, {}",
                    channelSnapshot.channelId(),
                    channelSnapshot.epoch(),
                    channelSnapshot.observedPacketCount(),
                    decision.summaryText()
            );
        }
    }


    private static boolean shouldLog(ChunkPeerStateSnapshot snapshot, ChunkPlanDecision decision) {
        if (snapshot == null || decision == null) {
            return false;
        }
        if (ExperientChunkHotspotPathRuntimeConfig.isTwoPointReuseMode()
                && "full".equals(decision.laneName())
                && decision.decisionKind() == ChunkPlanDecisionKind.PUBLISH_FULL) {
            return true;
        }
        if (decision.decisionKind() == ChunkPlanDecisionKind.PUBLISH_REF
                || decision.decisionKind() == ChunkPlanDecisionKind.PUBLISH_PATCH) {
            return true;
        }
        if (decision.reason().contains("budget") || decision.reason().contains("receiver_ack")) {
            return true;
        }
        long observedPacketCount = snapshot.observedPacketCount();
        return observedPacketCount <= 10L || observedPacketCount % 100L == 0L;
    }
}
