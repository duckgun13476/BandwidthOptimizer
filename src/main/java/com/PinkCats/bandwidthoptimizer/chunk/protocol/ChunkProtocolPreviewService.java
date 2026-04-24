package com.PinkCats.bandwidthoptimizer.chunk.protocol;

import com.PinkCats.bandwidthoptimizer.Bandwidthoptimizer;
import com.PinkCats.bandwidthoptimizer.chunk.classify.ChunkPacketDescriptor;
import com.PinkCats.bandwidthoptimizer.chunk.plan.ChunkPlanDecision;
import com.PinkCats.bandwidthoptimizer.chunk.plan.ChunkPlanDecisionKind;
import com.PinkCats.bandwidthoptimizer.chunk.state.peer.ChunkPeerObservationSnapshot;
import com.PinkCats.bandwidthoptimizer.chunk.state.peer.ChunkPeerStateSnapshot;

public final class ChunkProtocolPreviewService {

    private ChunkProtocolPreviewService() {}

    public static void previewOutboundObservation(
            ChunkPeerObservationSnapshot observation,
            ChunkPacketDescriptor descriptor,
            ChunkPlanDecision decision
    ) {
        ChunkPeerStateSnapshot snapshot = observation == null ? null : observation.channelState();
        if (snapshot == null
                || descriptor == null
                || decision == null
                || decision.decisionKind() == ChunkPlanDecisionKind.BYPASS
                || !shouldLogPreview(snapshot)) {
            return;
        }

        try {
            ChunkHotspotFrame frame = buildFrame(snapshot, descriptor, decision);
            byte[] encodedFrameBytes = ChunkHotspotFrameCodec.encodeFrame(frame);
            ChunkHotspotFrame decodedFrame = ChunkHotspotFrameCodec.decodeFrame(encodedFrameBytes);
            boolean matches = frame.equals(decodedFrame);
            Bandwidthoptimizer.LOGGER.info(
                    "[ChunkProtocol][Preview] channel={}, epoch={}, observedPackets={}, frameBytes={}, match={}, {}",
                    snapshot.channelId(),
                    snapshot.epoch(),
                    snapshot.observedPacketCount(),
                    encodedFrameBytes.length,
                    matches,
                    frame.summaryText()
            );
            if (!matches) {
                Bandwidthoptimizer.LOGGER.error(
                        "[ChunkProtocol][PreviewMismatch] original={}, decoded={}",
                        frame.summaryText(),
                        decodedFrame.summaryText()
                );
            }
        } catch (Throwable throwable) {
            Bandwidthoptimizer.LOGGER.error(
                    "[ChunkProtocol][PreviewError] channel={}, observedPackets={}, packetClass={}, message={}",
                    snapshot.channelId(),
                    snapshot.observedPacketCount(),
                    descriptor.packetClassName(),
                    throwable.getMessage(),
                    throwable
            );
        }
    }

    private static ChunkHotspotFrame buildFrame(
            ChunkPeerStateSnapshot snapshot,
            ChunkPacketDescriptor descriptor,
            ChunkPlanDecision decision
    ) {
        return new ChunkHotspotFrame(
                ChunkHotspotFrameCodec.PROTOCOL_VERSION,
                mapOperation(decision.decisionKind()),
                snapshot.epoch(),
                snapshot.observedPacketCount(),
                descriptor.protocolName(),
                descriptor.packetClassName(),
                descriptor.hotspotKind(),
                descriptor.laneKind(),
                descriptor.coordinate(),
                decision.encodedBytes(),
                decision.fullSnapshotVersion(),
                decision.laneVersion(),
                decision.knownSnapshotHash(),
                decision.currentPayloadHash(),
                decision.deltaBytesSinceFullSnapshot(),
                decision.reason()
        );
    }


    private static ChunkHotspotFrameOp mapOperation(ChunkPlanDecisionKind decisionKind) {
        if (decisionKind == ChunkPlanDecisionKind.PUBLISH_FULL)
            return ChunkHotspotFrameOp.PUBLISH_FULL;
        if (decisionKind == ChunkPlanDecisionKind.PUBLISH_REF)
            return ChunkHotspotFrameOp.PUBLISH_REF;
        return ChunkHotspotFrameOp.PUBLISH_PATCH;
    }

    // 这个函数限制协议预演日志频率，保持与其它 chunk 观察日志一致，避免登录阶段刷屏。
    private static boolean shouldLogPreview(ChunkPeerStateSnapshot snapshot) {
        long observedPacketCount = snapshot.observedPacketCount();
        return observedPacketCount <= 5L || observedPacketCount % 100L == 0L;
    }
}
