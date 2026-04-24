package com.PinkCats.bandwidthoptimizer.chunk.classify;

import com.PinkCats.bandwidthoptimizer.chunk.plan.ChunkPlanDecision;
import com.PinkCats.bandwidthoptimizer.chunk.plan.ChunkPlanPreviewService;
import com.PinkCats.bandwidthoptimizer.chunk.plan.ChunkTransportPlanner;
import com.PinkCats.bandwidthoptimizer.chunk.patch.ChunkPatchBuilder;
import com.PinkCats.bandwidthoptimizer.chunk.protocol.ChunkProtocolPreviewService;
import com.PinkCats.bandwidthoptimizer.chunk.snapshot.ChunkShadowSnapshot;
import com.PinkCats.bandwidthoptimizer.chunk.snapshot.ChunkShadowSnapshotManager;
import com.PinkCats.bandwidthoptimizer.chunk.snapshot.ChunkSnapshotFingerprint;
import com.PinkCats.bandwidthoptimizer.chunk.snapshot.ChunkSnapshotFingerprintService;
import com.PinkCats.bandwidthoptimizer.chunk.state.peer.ChunkPeerChunkStateSnapshot;
import com.PinkCats.bandwidthoptimizer.chunk.state.peer.ChunkPeerObservationSnapshot;
import com.PinkCats.bandwidthoptimizer.chunk.state.peer.ChunkPeerStateSnapshot;
import com.PinkCats.bandwidthoptimizer.chunk.state.peer.ChunkPeerStateManager;
import com.PinkCats.bandwidthoptimizer.chunk.store.global.ChunkGlobalSnapshotStore;
import com.PinkCats.bandwidthoptimizer.chunk.store.global.ChunkGlobalStoreObservation;
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
        ChunkPeerChunkStateSnapshot chunkSnapshotBeforeObserve =
                ChunkPeerStateManager.snapshotOutboundChunk(context, descriptor.coordinate());
        ChunkShadowSnapshot localChunkSnapshotBeforeObserve =
                ChunkShadowSnapshotManager.snapshotChunk(context.channel().id().asLongText(), descriptor.coordinate());
        ChunkPatchBuilder.ChunkPatchBuildResult patchBuildResult =
                ChunkPatchBuilder.buildPatchFromSnapshot(
                        localChunkSnapshotBeforeObserve,
                        descriptor,
                        packet,
                        encodedPacketBytes,
                        snapshotFingerprint
                );
        ChunkGlobalStoreObservation storeObservation =
                ChunkGlobalSnapshotStore.observeOutboundSnapshot(descriptor, snapshotFingerprint);
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
        ChunkShadowSnapshotManager.observeOutboundPacket(
                context,
                channelSnapshot == null ? 0L : channelSnapshot.epoch(),
                descriptor,
                packet,
                encodedPacketBytes
        );
        ChunkPlanPreviewService.previewOutboundObservation(observation, descriptor, decision);
        ChunkProtocolPreviewService.previewOutboundObservation(observation, descriptor, decision);
    }
}
