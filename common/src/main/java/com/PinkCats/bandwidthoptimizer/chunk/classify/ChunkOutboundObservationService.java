package com.PinkCats.bandwidthoptimizer.chunk.classify;

import com.PinkCats.bandwidthoptimizer.Bandwidthoptimizer;
import com.PinkCats.bandwidthoptimizer.chunk.classify.packet.ChunkPacketClassifier;
import com.PinkCats.bandwidthoptimizer.chunk.classify.packet.ChunkPacketDescriptor;
import com.PinkCats.bandwidthoptimizer.chunk.plan.ChunkPlanDecision;
import com.PinkCats.bandwidthoptimizer.chunk.plan.ChunkPlanPreviewService;
import com.PinkCats.bandwidthoptimizer.chunk.plan.ChunkTransportPlanner;
import com.PinkCats.bandwidthoptimizer.chunk.patch.ChunkPatchBuilder;
import com.PinkCats.bandwidthoptimizer.chunk.protocol.ChunkProtocolPreviewService;
import com.PinkCats.bandwidthoptimizer.chunk.snapshot.shadow.ChunkShadowSnapshot;
import com.PinkCats.bandwidthoptimizer.chunk.snapshot.shadow.ChunkShadowSnapshotManager;
import com.PinkCats.bandwidthoptimizer.chunk.snapshot.ChunkSnapshotFingerprint;
import com.PinkCats.bandwidthoptimizer.chunk.snapshot.ChunkSnapshotFingerprintService;
import com.PinkCats.bandwidthoptimizer.chunk.PeerState.ChunkPeerChunkStateSnapshot;
import com.PinkCats.bandwidthoptimizer.chunk.PeerState.ChunkPeerObservationSnapshot;
import com.PinkCats.bandwidthoptimizer.chunk.PeerState.ChunkPeerStateSnapshot;
import com.PinkCats.bandwidthoptimizer.chunk.PeerState.ChunkPeerStateManager;
import com.PinkCats.bandwidthoptimizer.chunk.store.global.ChunkGlobalSnapshotStore;
import com.PinkCats.bandwidthoptimizer.chunk.store.global.ChunkGlobalStoreObservation;
import com.PinkCats.bandwidthoptimizer.experimental.hotspot.ExperientChunkHotspotFullChunkTracker;
import com.PinkCats.bandwidthoptimizer.experimental.hotspot.ExperientChunkHotspotPathRuntimeConfig;
import io.netty.channel.ChannelHandlerContext;
import net.minecraft.network.protocol.Packet;

public final class ChunkOutboundObservationService {

    private ChunkOutboundObservationService() {
    }

    // Observer for chunk's snapshot、peer state、planner、protocol preview.
    public static void observeOutboundPacket(
            ChannelHandlerContext context,
            String protocolName,
            Packet<?> packet,
            byte[] encodedPacketBytes
    ) {
        ChunkPacketDescriptor descriptor = ChunkPacketClassifier.classifyOutboundPlayPacket(protocolName, packet);
        if (descriptor == null) {
            return;
        }

        ChunkSnapshotFingerprint snapshotFingerprint =
                ChunkSnapshotFingerprintService.fingerprintOutboundPacket(encodedPacketBytes);
        ChunkPeerStateSnapshot channelSnapshotBeforeObserve = ChunkPeerStateManager.snapshotOutboundChannel(context);
        if (channelSnapshotBeforeObserve == null || channelSnapshotBeforeObserve.epoch() <= 0L) {
            return;
        }

        long currentScopeId = channelSnapshotBeforeObserve.epoch();
        ChunkPeerChunkStateSnapshot chunkSnapshotBeforeObserve =
                ChunkPeerStateManager.snapshotOutboundChunk(context, currentScopeId, descriptor.coordinate());
        ChunkShadowSnapshot localChunkSnapshotBeforeObserve =
                ChunkShadowSnapshotManager.snapshotChunk(com.PinkCats.bandwidthoptimizer.channel.ChannelIdentity.longText(context.channel()), currentScopeId, descriptor.coordinate());
        recordTwoPointFullChunkProgress(context, descriptor);
        logTwoPointFullChunkBeforePlan(
                context,
                descriptor,
                snapshotFingerprint,
                chunkSnapshotBeforeObserve,
                localChunkSnapshotBeforeObserve
        );
        ChunkPatchBuilder.ChunkPatchBuildResult patchBuildResult =
                ChunkPatchBuilder.buildPatchFromSnapshot(
                        localChunkSnapshotBeforeObserve,
                        descriptor,
                        packet,
                        encodedPacketBytes,
                        snapshotFingerprint
                );
        ChunkGlobalStoreObservation storeObservation =
                ChunkGlobalSnapshotStore.observeOutboundSnapshot(descriptor, snapshotFingerprint, encodedPacketBytes);
        ChunkPlanDecision decision = ChunkTransportPlanner.planOutboundTransport(
                descriptor,
                snapshotFingerprint,
                chunkSnapshotBeforeObserve,
                storeObservation,
                patchBuildResult
        );
        ChunkPeerObservationSnapshot observation = ChunkPeerStateManager.observeOutboundChunkPacket(
                context,
                descriptor,
                snapshotFingerprint,
                storeObservation
        );
        ChunkPeerStateSnapshot channelSnapshot = observation == null ? null : observation.channelState();
        if (channelSnapshot == null || channelSnapshot.epoch() <= 0L) {
            return;
        }
        ChunkShadowSnapshotManager.observeOutboundPacket(
                context,
                channelSnapshot.epoch(),
                descriptor,
                packet,
                encodedPacketBytes
        );
        ChunkPlanPreviewService.previewOutboundObservation(observation, descriptor, decision);
        ChunkProtocolPreviewService.previewOutboundObservation(observation, descriptor, decision);
    }


    private static void logTwoPointFullChunkBeforePlan(
            ChannelHandlerContext context,
            ChunkPacketDescriptor descriptor,
            ChunkSnapshotFingerprint snapshotFingerprint,
            ChunkPeerChunkStateSnapshot chunkSnapshotBeforeObserve,
            ChunkShadowSnapshot localChunkSnapshotBeforeObserve
    ) {
        if (!com.PinkCats.bandwidthoptimizer.experimental.hotspot.ExperientChunkHotspotPathRuntimeConfig.isTwoPointReuseMode()
                || context == null
                || descriptor == null
                || descriptor.hotspotKind() != ChunkHotspotKind.FULL_CHUNK) {
            return;
        }

        Bandwidthoptimizer.LOGGER.info(
                "[ChunkTwoPoint][BeforePlan] channel={}, chunk={}, payloadHash={}, encodedBytes={}, peerState={}, localShadow={}",
                com.PinkCats.bandwidthoptimizer.channel.ChannelIdentity.longText(context.channel()),
                descriptor.coordinate().logText(),
                snapshotFingerprint == null ? "<none>" : snapshotFingerprint.shortHash(),
                snapshotFingerprint == null ? 0 : Math.max(snapshotFingerprint.encodedBytes(), 0),
                chunkSnapshotBeforeObserve == null ? "<missing>" : chunkSnapshotBeforeObserve.summaryText(),
                localChunkSnapshotBeforeObserve == null ? "<missing>" : localChunkSnapshotBeforeObserve.summaryText()
        );
    }

    private static void recordTwoPointFullChunkProgress(ChannelHandlerContext context, ChunkPacketDescriptor descriptor) {
        if (!com.PinkCats.bandwidthoptimizer.experimental.hotspot.ExperientChunkHotspotPathRuntimeConfig.isTwoPointReuseMode()
                || descriptor == null
                || descriptor.hotspotKind() != ChunkHotspotKind.FULL_CHUNK) {
            return;
        }
        com.PinkCats.bandwidthoptimizer.experimental.hotspot.ExperientChunkHotspotFullChunkTracker.recordOutboundFullChunk(context);
    }
}
