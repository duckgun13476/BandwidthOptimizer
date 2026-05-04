package com.PinkCats.bandwidthoptimizer.chunk.PeerState;

import com.PinkCats.bandwidthoptimizer.Bandwidthoptimizer;
import com.PinkCats.bandwidthoptimizer.chunk.classify.packet.ChunkPacketCoordinate;
import com.PinkCats.bandwidthoptimizer.chunk.classify.packet.ChunkPacketDescriptor;
import com.PinkCats.bandwidthoptimizer.chunk.integration.ChunkRuntimeReferenceStore;
import com.PinkCats.bandwidthoptimizer.chunk.protocol.hotspot.ChunkHotspotFrame;
import com.PinkCats.bandwidthoptimizer.chunk.snapshot.shadow.ChunkShadowSnapshotManager;
import com.PinkCats.bandwidthoptimizer.chunk.snapshot.ChunkSnapshotFingerprint;
import com.PinkCats.bandwidthoptimizer.chunk.store.global.ChunkGlobalStoreObservation;
import com.PinkCats.bandwidthoptimizer.debug.DebugRuntimeConfig;
import com.PinkCats.bandwidthoptimizer.mixin.minecraft.ConnectionAccessor;
import com.PinkCats.bandwidthoptimizer.mixin.minecraft.ServerGamePacketListenerImplAccessor;
import io.netty.channel.Channel;
import io.netty.channel.ChannelHandlerContext;
import net.minecraft.network.Connection;
import net.minecraft.resources.ResourceKey;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.server.network.ServerGamePacketListenerImpl;
import net.minecraft.world.level.Level;

import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;

public final class ChunkPeerStateManager {

    private static final long FIRST_SCOPE_ID = 1L;
    private static final ConcurrentHashMap<String, ChunkPeerState> CHANNEL_STATES = new ConcurrentHashMap<>();
    private static final ConcurrentHashMap<UUID, PlayerScopeState> PLAYER_SCOPE_STATES = new ConcurrentHashMap<>();

    private ChunkPeerStateManager() {}

    public static ChunkPeerObservationSnapshot observeOutboundChunkPacket(
            ChannelHandlerContext context,
            ChunkPacketDescriptor descriptor,
            ChunkSnapshotFingerprint snapshotFingerprint,
            ChunkGlobalStoreObservation storeObservation
    ) {
        if (context == null || descriptor == null) {
            return null;
        }

        String channelId = context.channel().id().asLongText();
        ChunkPeerState state = CHANNEL_STATES.get(channelId);
        if (state == null) {
            return null;
        }

        ChunkPeerStateSnapshot stateSnapshot = state.snapshot();
        if (stateSnapshot.epoch() <= 0L) {
            return null;
        }

        ChunkPeerObservationSnapshot observation = state.recordObservation(descriptor, snapshotFingerprint, storeObservation);
        ChunkPeerStateSnapshot channelSnapshot = observation.channelState();
        if (shouldLogDiagnose() && state.shouldLogObservation()) {
            Bandwidthoptimizer.LOGGER.info(
                    "[ChunkHotspot][Observe] channel={}, epoch={}, observedPackets={}, encodedBytes={}, {}",
                    channelSnapshot.channelId(),
                    channelSnapshot.epoch(),
                    channelSnapshot.observedPacketCount(),
                    channelSnapshot.lastObservedEncodedBytes(),
                    descriptor.summaryText()
            );
        }

        ChunkPeerChunkStateSnapshot chunkSnapshot = observation.chunkState();
        if (chunkSnapshot != null && shouldLogDiagnose() && state.shouldLogObservation()) {
            Bandwidthoptimizer.LOGGER.info(
                    "[ChunkPeer][Chunk] channel={}, epoch={}, observedPackets={}, {}",
                    channelSnapshot.channelId(),
                    channelSnapshot.epoch(),
                    channelSnapshot.observedPacketCount(),
                    chunkSnapshot.summaryText()
            );
        }

        if (storeObservation != null && shouldLogDiagnose() && state.shouldLogObservation()) {
            Bandwidthoptimizer.LOGGER.info(
                    "[ChunkStore][Observe] channel={}, epoch={}, observedPackets={}, {}",
                    channelSnapshot.channelId(),
                    channelSnapshot.epoch(),
                    channelSnapshot.observedPacketCount(),
                    storeObservation.summaryText()
            );
        }
        return observation;
    }

    public static long bindPlayerDimensionScope(ServerPlayer player, String reason) {
        if (player == null || player.serverLevel() == null) {
            return 0L;
        }

        ResourceKey<Level> dimensionKey = player.serverLevel().dimension();
        long epoch = resolvePlayerScopeId(player.getUUID(), dimensionKey, reason);
        String channelId = readPlayerChannelId(player);
        if (epoch <= 0L || channelId == null || channelId.isBlank()) {
            return epoch;
        }

        ChunkPeerState state = CHANNEL_STATES.computeIfAbsent(channelId, ChunkPeerState::new);
        ChunkPeerStateSnapshot snapshot = state.setEpoch(epoch);
        if (shouldLogDiagnose()) {
            Bandwidthoptimizer.LOGGER.info(
                    "[ChunkPeer][Bind] player={}, uuid={}, reason={}, dimension={}, channel={}, epoch={}",
                    player.getGameProfile().getName(),
                    player.getUUID(),
                    reason,
                    dimensionKey.location(),
                    snapshot.channelId(),
                    snapshot.epoch()
            );
        }
        return snapshot.epoch();
    }

    public static void clearPlayerState(ServerPlayer player, String reason) {
        if (player == null)
            return;

        PlayerScopeState removedScopeState = PLAYER_SCOPE_STATES.remove(player.getUUID());
        String channelId = readPlayerChannelId(player);
        if (channelId != null && !channelId.isBlank()) {
            CHANNEL_STATES.remove(channelId);
            ChunkRuntimeReferenceStore.clearChannel(channelId);
            ChunkShadowSnapshotManager.clearChannel(channelId);
        }
        if (shouldLogDiagnose()) {
            Bandwidthoptimizer.LOGGER.info(
                    "[ChunkPeer][Lifecycle] player={}, uuid={}, reason={}, removedEpoch={}, removedChannelState={}",
                    player.getGameProfile().getName(),
                    player.getUUID(),
                    reason,
                    removedScopeState == null ? "<none>" : removedScopeState.summaryText(),
                    channelId == null || channelId.isBlank() ? "<none>" : channelId
            );
        }
    }


    public static ChunkPeerStateSnapshot snapshotOutboundChannel(ChannelHandlerContext context) {
        if (context == null)
            return null;
        ChunkPeerState state = CHANNEL_STATES.get(context.channel().id().asLongText());
        return state == null ? null : state.snapshot();
    }

    public static ChunkPeerChunkStateSnapshot snapshotOutboundChunk(ChannelHandlerContext context, ChunkPacketCoordinate coordinate) {
        if (context == null || coordinate == null || !coordinate.present()) {
            return null;
        }

        ChunkPeerState state = CHANNEL_STATES.get(context.channel().id().asLongText());
        return state == null ? null : state.snapshotChunk(coordinate);
    }

    public static ChunkPeerChunkStateSnapshot snapshotOutboundChunk(
            ChannelHandlerContext context,
            long scopeId,
            ChunkPacketCoordinate coordinate
    ) {
        if (context == null || coordinate == null || !coordinate.present()) {
            return null;
        }

        ChunkPeerState state = CHANNEL_STATES.get(context.channel().id().asLongText());
        return state == null ? null : state.snapshotChunk(scopeId, coordinate);
    }

    public static ChunkPeerChunkStateSnapshot snapshotPlayerChunk(ServerPlayer player, ChunkPacketCoordinate coordinate) {
        Channel channel = readPlayerChannel(player);
        if (channel == null || coordinate == null || !coordinate.present()) {
            return null;
        }

        ChunkPeerState state = CHANNEL_STATES.get(channel.id().asLongText());
        return state == null ? null : state.snapshotChunk(coordinate);
    }


    public static ChunkPeerChunkStateSnapshot acknowledgeOutboundChunk(ChannelHandlerContext context, ChunkHotspotFrame frame) {
        if (context == null || frame == null || frame.coordinate() == null || !frame.coordinate().present()) {
            return null;
        }

        ChunkPeerState state = CHANNEL_STATES.get(context.channel().id().asLongText());
        ChunkPeerChunkStateSnapshot chunkSnapshot = state == null
                ? null
                : state.acknowledgeChunk(frame.epoch(), frame.coordinate(), frame.fullSnapshotVersion(), frame.payloadHash());
        logControlUpdate("Ack", context, frame, chunkSnapshot);
        return chunkSnapshot;
    }

    public static ChunkPeerChunkStateSnapshot negativeAcknowledgeOutboundChunk(ChannelHandlerContext context, ChunkHotspotFrame frame) {
        if (context == null || frame == null || frame.coordinate() == null || !frame.coordinate().present()) {
            return null;
        }

        ChunkPeerState state = CHANNEL_STATES.get(context.channel().id().asLongText());
        ChunkPeerChunkStateSnapshot chunkSnapshot = state == null ? null : state.negativeAcknowledgeChunk(frame.epoch(), frame.coordinate());
        logControlUpdate("Nack", context, frame, chunkSnapshot);
        return chunkSnapshot;
    }

    public static ChunkPeerChunkStateSnapshot invalidateOutboundChunk(ChannelHandlerContext context, ChunkHotspotFrame frame) {
        if (context == null || frame == null || frame.coordinate() == null || !frame.coordinate().present()) {
            return null;
        }

        ChunkPeerState state = CHANNEL_STATES.get(context.channel().id().asLongText());
        ChunkPeerChunkStateSnapshot chunkSnapshot = state == null ? null : state.invalidateChunk(frame.epoch(), frame.coordinate());
        ChunkShadowSnapshotManager.invalidateChunk(context.channel().id().asLongText(), frame.epoch(), frame.coordinate());
        logControlUpdate("Invalidate", context, frame, chunkSnapshot);
        return chunkSnapshot;
    }


    public static ChunkPeerChunkStateSnapshot invalidatePlayerChunk(
            ServerPlayer player,
            ChunkPacketCoordinate coordinate,
            String reason
    ) {
        Channel channel = readPlayerChannel(player);
        if (channel == null || coordinate == null || !coordinate.present()) {
            return null;
        }

        ChunkPeerState state = CHANNEL_STATES.get(channel.id().asLongText());
        long scopeId = state == null ? 0L : state.snapshot().epoch();
        ChunkPeerChunkStateSnapshot chunkSnapshot = state == null ? null : state.invalidateChunk(scopeId, coordinate);
        ChunkShadowSnapshotManager.invalidateChunk(channel.id().asLongText(), scopeId, coordinate);
        if (shouldLogDiagnose()) {
            Bandwidthoptimizer.LOGGER.info(
                    "[ChunkPeer][LifecycleInvalidate] player={}, uuid={}, channel={}, reason={}, chunk={}, state={}",
                    player.getGameProfile().getName(),
                    player.getUUID(),
                    channel.id().asLongText(),
                    reason,
                    coordinate.logText(),
                    chunkSnapshot == null ? "<missing>" : chunkSnapshot.summaryText()
            );
        }
        return chunkSnapshot;
    }

    public static ChunkPeerChunkStateSnapshot retainPlayerChunkForWatchBoundary(
            ServerPlayer player,
            ChunkPacketCoordinate coordinate,
            String reason
    ) {
        Channel channel = readPlayerChannel(player);
        if (channel == null || coordinate == null || !coordinate.present()) {
            return null;
        }

        ChunkPeerState state = CHANNEL_STATES.get(channel.id().asLongText());
        long scopeId = state == null ? 0L : state.snapshot().epoch();
        ChunkPeerChunkStateSnapshot chunkSnapshot = state == null ? null : state.markChunkAwaitingFullReplay(scopeId, coordinate);
        if (shouldLogDiagnose()) {
            Bandwidthoptimizer.LOGGER.info(
                    "[ChunkPeer][LifecycleRetain] player={}, uuid={}, channel={}, reason={}, chunk={}, state={}",
                    player.getGameProfile().getName(),
                    player.getUUID(),
                    channel.id().asLongText(),
                    reason,
                    coordinate.logText(),
                    chunkSnapshot == null ? "<missing>" : chunkSnapshot.summaryText()
            );
        }
        return chunkSnapshot;
    }

    public static Channel findPlayerChannel(ServerPlayer player) {
        return readPlayerChannel(player);
    }

    private static long resolvePlayerScopeId(UUID playerId, ResourceKey<Level> dimensionKey, String reason) {
        if (playerId == null || dimensionKey == null) {
            return 0L;
        }

        PlayerScopeState scopeState = PLAYER_SCOPE_STATES.computeIfAbsent(playerId, ignored -> new PlayerScopeState());
        return scopeState.bindScope(dimensionKey, reason);
    }

    private static String readPlayerChannelId(ServerPlayer player) {
        Channel channel = readPlayerChannel(player);
        return channel == null ? null : channel.id().asLongText();
    }

    private static Channel readPlayerChannel(ServerPlayer player) {
        if (player == null) {
            return null;
        }

        ServerGamePacketListenerImpl listener = player.connection;
        Connection connection = ((ServerGamePacketListenerImplAccessor) listener).bandwidthoptimizer$getConnection();
        if (connection == null) {
            return null;
        }

        return ((ConnectionAccessor) connection).bandwidthoptimizer$getChannel();
    }

    private static void logControlUpdate(
            String label,
            ChannelHandlerContext context,
            ChunkHotspotFrame frame,
            ChunkPeerChunkStateSnapshot chunkSnapshot
    ) {
        if (!shouldLogDiagnose()) {
            return;
        }
        Bandwidthoptimizer.LOGGER.info(
                "[ChunkPeer][{}] channel={}, epoch={}, chunk={}, fullVersion={}, payloadHash={}, state={}",
                label,
                context.channel().id().asLongText(),
                frame.epoch(),
                frame.coordinate().logText(),
                frame.fullSnapshotVersion(),
                shortenHash(frame.payloadHash()),
                chunkSnapshot == null ? "<missing>" : chunkSnapshot.summaryText()
        );
    }

    private static boolean shouldLogDiagnose() {
        return DebugRuntimeConfig.isDiagnoseEnabled();
    }

    private static String shortenHash(String hashHex) {
        if (hashHex == null || hashHex.isBlank()) {
            return "<none>";
        }
        return hashHex.length() <= 12 ? hashHex : hashHex.substring(0, 12);
    }

    private static final class PlayerScopeState {

        private final AtomicLong nextScopeId = new AtomicLong(FIRST_SCOPE_ID);
        private ResourceKey<Level> preparedRespawnDimension;
        private long preparedRespawnScopeId;


        private synchronized long bindScope(ResourceKey<Level> dimensionKey, String reason) {
            if (dimensionKey == null) {
                return 0L;
            }

            if (isPreparedRespawnFollowup(reason)
                    && this.preparedRespawnScopeId > 0L
                    && dimensionKey.equals(this.preparedRespawnDimension)) {
                long reusedPreparedScopeId = this.preparedRespawnScopeId;
                clearPreparedRespawnScope();
                return reusedPreparedScopeId;
            }

            long assignedScopeId = this.nextScopeId.getAndIncrement();
            if (isPrepareRespawnBoundary(reason)) {
                this.preparedRespawnDimension = dimensionKey;
                this.preparedRespawnScopeId = assignedScopeId;
            } else {
                clearPreparedRespawnScope();
            }
            return assignedScopeId;
        }

        private void clearPreparedRespawnScope() {
            this.preparedRespawnDimension = null;
            this.preparedRespawnScopeId = 0L;
        }

        private static boolean isPrepareRespawnBoundary(String reason) {
            return "prepare_client_respawn_boundary".equals(reason);
        }

        private static boolean isPreparedRespawnFollowup(String reason) {
            return "respawn_same_dimension_rebind".equals(reason)
                    || "respawn_dimension_scope".equals(reason)
                    || "dimension_change".equals(reason);
        }

        private String summaryText() {
            return "nextScopeId=" + this.nextScopeId.get()
                    + ", preparedRespawnScopeId=" + this.preparedRespawnScopeId
                    + ", preparedRespawnDimension="
                    + (this.preparedRespawnDimension == null ? "<none>" : this.preparedRespawnDimension.location());
        }
    }
}
