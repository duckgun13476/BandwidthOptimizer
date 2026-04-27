package com.PinkCats.bandwidthoptimizer.chunk.classify;

import com.PinkCats.bandwidthoptimizer.channel.capture.ChannelCapturedFrame;
import com.PinkCats.bandwidthoptimizer.chunk.budget.ChunkClientCacheBudgetManager;
import com.PinkCats.bandwidthoptimizer.chunk.classify.packet.ChunkPacketClassifier;
import com.PinkCats.bandwidthoptimizer.chunk.classify.packet.ChunkPacketDescriptor;
import com.PinkCats.bandwidthoptimizer.chunk.packet.ClientboundPlayPacketCodec;
import com.PinkCats.bandwidthoptimizer.chunk.snapshot.shadow.ChunkShadowSnapshotManager;
import com.PinkCats.bandwidthoptimizer.chunk.PeerState.ChunkPeerStateManager;
import com.PinkCats.bandwidthoptimizer.chunk.PeerState.ChunkPeerStateSnapshot;
import io.netty.channel.ChannelHandlerContext;
import net.minecraft.network.protocol.Packet;

import java.util.List;

public final class ChunkInboundObservationService {

    private ChunkInboundObservationService() {}


    public static void observeInboundDecodedPackets(
            ChannelHandlerContext context,
            ChannelCapturedFrame pendingFrame,
            List<Object> out,
            int outputSizeBeforeDecode
    ) {
        if (context == null || pendingFrame == null || out == null) {
            return;
        }

        String protocolName = pendingFrame.protocolName();
        long epoch = readCurrentEpoch(context);
        String channelId = context.channel().id().asLongText();
        boolean observedChunkPacket = false;
        for (int index = outputSizeBeforeDecode; index < out.size(); index++) {
            Object decodedObject = out.get(index);
            if (!(decodedObject instanceof Packet<?> packet)) {
                continue;
            }

            ChunkPacketDescriptor descriptor = ChunkPacketClassifier.classifyOutboundPlayPacket(protocolName, packet);
            if (descriptor == null) {
                continue;
            }

            byte[] encodedPacketBytes = ClientboundPlayPacketCodec.encodePacket(packet);
            ChunkShadowSnapshotManager.observeInboundPacket(
                    channelId,
                    epoch,
                    descriptor,
                    packet,
                    encodedPacketBytes
            );
            observedChunkPacket = true;
        }

        if (observedChunkPacket) {
            ChunkClientCacheBudgetManager.enforceInboundBudget(context, "client_chunk_cache_budget");
        }
    }

    private static long readCurrentEpoch(ChannelHandlerContext context) {
        ChunkPeerStateSnapshot channelSnapshot = ChunkPeerStateManager.snapshotOutboundChannel(context);
        return channelSnapshot == null ? 0L : channelSnapshot.epoch();
    }
}
