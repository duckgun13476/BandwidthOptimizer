package com.PinkCats.bandwidthoptimizer.chunk.classify;

import com.PinkCats.bandwidthoptimizer.Bandwidthoptimizer;
import com.PinkCats.bandwidthoptimizer.channel.ChannelTransportStateManager;
import com.PinkCats.bandwidthoptimizer.channel.algorithm.batch.ChannelTransportBatchManager;
import com.PinkCats.bandwidthoptimizer.channel.capture.ChannelCapturedFrame;
import com.PinkCats.bandwidthoptimizer.chunk.budget.ChunkClientCacheBudgetManager;
import com.PinkCats.bandwidthoptimizer.chunk.classify.packet.ChunkPacketClassifier;
import com.PinkCats.bandwidthoptimizer.chunk.classify.packet.ChunkPacketDescriptor;
import com.PinkCats.bandwidthoptimizer.chunk.integration.transport.ChunkTransportBoundaryController;
import com.PinkCats.bandwidthoptimizer.chunk.packet.ClientboundPlayPacketCodec;
import com.PinkCats.bandwidthoptimizer.chunk.snapshot.shadow.ChunkShadowSnapshotManager;
import com.PinkCats.bandwidthoptimizer.chunk.PeerState.ChunkPeerStateManager;
import com.PinkCats.bandwidthoptimizer.chunk.PeerState.ChunkPeerStateSnapshot;
import com.PinkCats.bandwidthoptimizer.debug.DebugRuntimeConfig;
import io.netty.channel.ChannelHandlerContext;
import net.minecraft.network.protocol.Packet;
import net.minecraft.network.protocol.game.ClientboundLoginPacket;

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
        String channelId = context.channel().id().asLongText();
        boolean observedChunkPacket = false;
        for (int index = outputSizeBeforeDecode; index < out.size(); index++) {
            Object decodedObject = out.get(index);
            if (!(decodedObject instanceof Packet<?> packet)) {
                continue;
            }

            resetVelocityServerSwitchStateIfNeeded(context, protocolName, packet);
            ChunkTransportBoundaryController.observeInboundPacket(context, protocolName, packet);
            long epoch = readCurrentEpoch(context);
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

    // 在客户端收到新的 JoinGame 时清理只属于当前后端的活跃状态，保留可按 hash 校验的 chunk 缓存内容。
    private static void resetVelocityServerSwitchStateIfNeeded(
            ChannelHandlerContext context,
            String protocolName,
            Packet<?> packet
    ) {
        if (context == null || context.channel() == null || !(packet instanceof ClientboundLoginPacket)) {
            return;
        }

        String reason = "clientbound_login_server_switch_boundary";
        ChannelTransportBatchManager.clearChannelState(context.channel(), reason);
        ChannelTransportStateManager.clearSession(context.channel(), reason);
        ChunkTransportBoundaryController.resetChannelState(context, reason);
        if (DebugRuntimeConfig.isDiagnoseEnabled()) {
            Bandwidthoptimizer.LOGGER.info(
                    "[ChunkTransport][VelocitySwitch][Reset] channel={}, protocol={}, packetClass={}, reason={}",
                    context.channel().id().asLongText(),
                    protocolName,
                    packet.getClass().getName(),
                    reason
            );
        }
    }

    private static long readCurrentEpoch(ChannelHandlerContext context) {
        long mirroredInboundEpoch = ChunkTransportBoundaryController.readInboundChunkEpoch(context);
        if (mirroredInboundEpoch > 0L) {
            return mirroredInboundEpoch;
        }
        ChunkPeerStateSnapshot channelSnapshot = ChunkPeerStateManager.snapshotOutboundChannel(context);
        return channelSnapshot == null ? 0L : channelSnapshot.epoch();
    }
}
