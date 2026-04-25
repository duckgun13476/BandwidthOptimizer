package com.PinkCats.bandwidthoptimizer.chunk.plan;

import com.PinkCats.bandwidthoptimizer.Bandwidthoptimizer;
import com.PinkCats.bandwidthoptimizer.chunk.classify.ChunkPacketDescriptor;
import com.PinkCats.bandwidthoptimizer.chunk.state.peer.ChunkPeerObservationSnapshot;
import com.PinkCats.bandwidthoptimizer.chunk.state.peer.ChunkPeerStateSnapshot;

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
