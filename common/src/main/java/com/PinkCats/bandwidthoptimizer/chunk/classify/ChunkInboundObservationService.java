package com.PinkCats.bandwidthoptimizer.chunk.classify;

import com.PinkCats.bandwidthoptimizer.Bandwidthoptimizer;
import com.PinkCats.bandwidthoptimizer.channel.ChannelTransportStateManager;
import com.PinkCats.bandwidthoptimizer.channel.algorithm.batch.ChannelTransportBatchManager;
import com.PinkCats.bandwidthoptimizer.channel.capture.ChannelCapturedFrame;
import com.PinkCats.bandwidthoptimizer.chunk.budget.ChunkClientCacheBudgetManager;
import com.PinkCats.bandwidthoptimizer.chunk.budget.ChunkClientTrimmedFullBaseStore;
import com.PinkCats.bandwidthoptimizer.chunk.classify.packet.ChunkPacketClassifier;
import com.PinkCats.bandwidthoptimizer.chunk.classify.packet.ChunkPacketDescriptor;
import com.PinkCats.bandwidthoptimizer.chunk.integration.ChunkRuntimeReferenceStore;
import com.PinkCats.bandwidthoptimizer.chunk.integration.transport.ChunkTransportBoundaryController;
import com.PinkCats.bandwidthoptimizer.chunk.integration.transport.ChunkTransportRuntimeConfig;
import com.PinkCats.bandwidthoptimizer.chunk.packet.ClientboundPlayPacketCodec;
import com.PinkCats.bandwidthoptimizer.chunk.persistent.ChunkPersistentClientCache;
import com.PinkCats.bandwidthoptimizer.chunk.snapshot.shadow.ChunkShadowSnapshotManager;
import com.PinkCats.bandwidthoptimizer.chunk.PeerState.ChunkPeerStateManager;
import com.PinkCats.bandwidthoptimizer.chunk.PeerState.ChunkPeerStateSnapshot;
import com.PinkCats.bandwidthoptimizer.debug.DebugRuntimeConfig;
import com.PinkCats.bandwidthoptimizer.debug.DiagnosticToolRegistry;
import io.netty.channel.ChannelHandlerContext;
import net.minecraft.network.protocol.Packet;
import net.minecraft.network.protocol.game.ClientboundLoginPacket;

import java.util.List;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

public final class ChunkInboundObservationService {

    private static final Set<String> CHANNEL_CLOSE_CLEANUP_REGISTERED = ConcurrentHashMap.newKeySet();

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
        if (!ChunkTransportRuntimeConfig.isEnabled()) {
            return;
        }

        registerChannelCloseCleanup(context);
        String protocolName = pendingFrame.protocolName();
        String channelId = com.PinkCats.bandwidthoptimizer.channel.ChannelIdentity.longText(context.channel());
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
            persistInboundFullChunkIfNeeded(protocolName, epoch, descriptor, encodedPacketBytes);
            observedChunkPacket = true;
        }

        if (observedChunkPacket) {
            ChunkClientCacheBudgetManager.enforceInboundBudget(context, "client_chunk_cache_budget");
        }
    }

    private static void registerChannelCloseCleanup(ChannelHandlerContext context) {
        if (context == null || context.channel() == null) {
            return;
        }
        String channelId = com.PinkCats.bandwidthoptimizer.channel.ChannelIdentity.longText(context.channel());
        if (channelId == null || channelId.isBlank() || !CHANNEL_CLOSE_CLEANUP_REGISTERED.add(channelId)) {
            return;
        }
        context.channel().closeFuture().addListener(future -> {
            CHANNEL_CLOSE_CLEANUP_REGISTERED.remove(channelId);
            ChunkRuntimeReferenceStore.clearChannel(channelId);
            ChunkShadowSnapshotManager.clearChannel(channelId);
            ChunkClientTrimmedFullBaseStore.clearChannel(channelId);
            if (BO_Diag_chunkInboundObservation()) {
                Bandwidthoptimizer.LOGGER.info(
                        "[BO:Diag:chunkInboundObservation] event=channel_close_cleanup channel={}, reason=runtime_cache_cleanup",
                        channelId
                );
            }
        });
    }

    // clear and save hash chunk
    private static void resetVelocityServerSwitchStateIfNeeded(
            ChannelHandlerContext context,
            String protocolName,
            Packet<?> packet
    ) {
        if (context == null || context.channel() == null || !(packet instanceof ClientboundLoginPacket)) {
            return;
        }

        String reason = "clientbound_login_server_switch_boundary";
        String channelId = com.PinkCats.bandwidthoptimizer.channel.ChannelIdentity.longText(context.channel());
        ChannelTransportStateManager.endProxyServerSwitchBoundary(context.channel(), reason);
        ChannelTransportBatchManager.clearChannelState(context.channel(), reason);
        ChannelTransportStateManager.clearSession(context.channel(), reason);
        ChunkTransportBoundaryController.resetChannelState(context, reason);
        ChunkRuntimeReferenceStore.clearChannel(channelId);
        ChunkShadowSnapshotManager.clearChannel(channelId);
        ChunkClientTrimmedFullBaseStore.clearChannel(channelId);
        ChunkPersistentClientCache.prepareForServerSwitch(context.channel(), reason);
        ChunkPersistentClientCache.sendManifestOnce(context.channel(), "persistent_client_cache_after_login");
        if (BO_Diag_chunkInboundObservation()) {
            Bandwidthoptimizer.LOGGER.info(
                    "[BO:Diag:chunkInboundObservation] event=velocity_switch_reset channel={}, protocol={}, packetClass={}, reason={}",
                    com.PinkCats.bandwidthoptimizer.channel.ChannelIdentity.longText(context.channel()),
                    protocolName,
                    packet.getClass().getName(),
                    reason
            );
        }
    }

    private static void persistInboundFullChunkIfNeeded(
            String protocolName,
            long epoch,
            ChunkPacketDescriptor descriptor,
            byte[] encodedPacketBytes
    ) {
        if (descriptor == null
                || descriptor.hotspotKind() != ChunkHotspotKind.FULL_CHUNK
                || encodedPacketBytes == null
                || encodedPacketBytes.length == 0) {
            return;
        }

        ChunkPersistentClientCache.storeInboundFullChunkAsync(
                protocolName,
                epoch,
                descriptor.packetClassName(),
                descriptor.hotspotKind(),
                descriptor.laneKind(),
                descriptor.coordinate(),
                encodedPacketBytes,
                "persistent_cache_from_direct_inbound_full"
        );
    }

    private static long readCurrentEpoch(ChannelHandlerContext context) {
        long mirroredInboundEpoch = ChunkTransportBoundaryController.readInboundChunkEpoch(context);
        if (mirroredInboundEpoch > 0L) {
            return mirroredInboundEpoch;
        }
        ChunkPeerStateSnapshot channelSnapshot = ChunkPeerStateManager.snapshotOutboundChannel(context);
        return channelSnapshot == null ? 0L : channelSnapshot.epoch();
    }

    private static boolean BO_Diag_chunkInboundObservation() {
        return DiagnosticToolRegistry.isEnabled(DiagnosticToolRegistry.Tool.CHUNK_INBOUND_OBSERVATION)
                || DebugRuntimeConfig.isDiagnoseEnabled();
    }
}
