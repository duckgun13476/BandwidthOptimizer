package com.PinkCats.bandwidthoptimizer.chunk.PeerState;

import com.PinkCats.bandwidthoptimizer.Bandwidthoptimizer;
import com.PinkCats.bandwidthoptimizer.chunk.classify.packet.ChunkPacketCoordinate;
import com.PinkCats.bandwidthoptimizer.chunk.classify.packet.ChunkPacketDescriptor;
import com.PinkCats.bandwidthoptimizer.chunk.integration.ChunkRuntimeReferenceStore;
import com.PinkCats.bandwidthoptimizer.chunk.protocol.hotspot.ChunkHotspotFrame;
import com.PinkCats.bandwidthoptimizer.chunk.snapshot.shadow.ChunkShadowSnapshotManager;
import com.PinkCats.bandwidthoptimizer.chunk.snapshot.ChunkSnapshotFingerprint;
import com.PinkCats.bandwidthoptimizer.chunk.store.global.ChunkGlobalStoreObservation;
import io.netty.channel.Channel;
import io.netty.channel.ChannelHandlerContext;
import net.minecraft.server.level.ServerPlayer;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

public final class ChunkPeerStateManager {

    private static final ConcurrentHashMap<String, ChunkPeerState> CHANNEL_STATES = new ConcurrentHashMap<>();
    private static final ConcurrentHashMap<UUID, Long> PLAYER_EPOCHS = new ConcurrentHashMap<>();

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
        ChunkPeerState state = CHANNEL_STATES.computeIfAbsent(channelId, ChunkPeerState::new);
        ChunkPeerObservationSnapshot observation = state.recordObservation(descriptor, snapshotFingerprint, storeObservation);
        ChunkPeerStateSnapshot channelSnapshot = observation.channelState();
        if (state.shouldLogObservation()) {
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
        if (chunkSnapshot != null && state.shouldLogObservation()) {
            Bandwidthoptimizer.LOGGER.info(
                    "[ChunkPeer][Chunk] channel={}, epoch={}, observedPackets={}, {}",
                    channelSnapshot.channelId(),
                    channelSnapshot.epoch(),
                    channelSnapshot.observedPacketCount(),
                    chunkSnapshot.summaryText()
            );
        }

        if (storeObservation != null && state.shouldLogObservation()) {
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

    public static long bumpPlayerEpoch(ServerPlayer player, String reason) {
        if (player == null) {
            return 0L;
        }

        long epoch = PLAYER_EPOCHS.merge(player.getUUID(), 1L, Long::sum);
        Bandwidthoptimizer.LOGGER.info(
                "[ChunkPeer][Lifecycle] player={}, uuid={}, reason={}, epoch={}",
                player.getGameProfile().getName(),
                player.getUUID(),
                reason,
                epoch
        );
        return epoch;
    }

    public static void attachPlayerEpochToChannel(ServerPlayer player, String reason) {
        if (player == null) {
            return;
        }

        long epoch = PLAYER_EPOCHS.getOrDefault(player.getUUID(), 0L);
        String channelId = readPlayerChannelId(player);
        if (epoch <= 0L || channelId == null || channelId.isBlank()) {
            return;
        }

        ChunkPeerState state = CHANNEL_STATES.computeIfAbsent(channelId, ChunkPeerState::new);
        ChunkPeerStateSnapshot snapshot = state.setEpoch(epoch);
        Bandwidthoptimizer.LOGGER.info(
                "[ChunkPeer][Bind] player={}, uuid={}, reason={}, channel={}, epoch={}",
                player.getGameProfile().getName(),
                player.getUUID(),
                reason,
                snapshot.channelId(),
                snapshot.epoch()
        );
    }

    public static void clearPlayerState(ServerPlayer player, String reason) {
        if (player == null)
            return;

        Long removedEpoch = PLAYER_EPOCHS.remove(player.getUUID());
        String channelId = readPlayerChannelId(player);
        if (channelId != null && !channelId.isBlank()) {
            CHANNEL_STATES.remove(channelId);
            ChunkRuntimeReferenceStore.clearChannel(channelId);
            ChunkShadowSnapshotManager.clearChannel(channelId);
        }
        Bandwidthoptimizer.LOGGER.info(
                "[ChunkPeer][Lifecycle] player={}, uuid={}, reason={}, removedEpoch={}, removedChannelState={}",
                player.getGameProfile().getName(),
                player.getUUID(),
                reason,
                removedEpoch == null ? "<none>" : removedEpoch,
                channelId == null || channelId.isBlank() ? "<none>" : channelId
        );
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
                : state.acknowledgeChunk(frame.coordinate(), frame.fullSnapshotVersion(), frame.payloadHash());
        logControlUpdate("Ack", context, frame, chunkSnapshot);
        return chunkSnapshot;
    }

    public static ChunkPeerChunkStateSnapshot negativeAcknowledgeOutboundChunk(ChannelHandlerContext context, ChunkHotspotFrame frame) {
        if (context == null || frame == null || frame.coordinate() == null || !frame.coordinate().present()) {
            return null;
        }

        ChunkPeerState state = CHANNEL_STATES.get(context.channel().id().asLongText());
        ChunkPeerChunkStateSnapshot chunkSnapshot = state == null ? null : state.negativeAcknowledgeChunk(frame.coordinate());
        logControlUpdate("Nack", context, frame, chunkSnapshot);
        return chunkSnapshot;
    }

    public static ChunkPeerChunkStateSnapshot invalidateOutboundChunk(ChannelHandlerContext context, ChunkHotspotFrame frame) {
        if (context == null || frame == null || frame.coordinate() == null || !frame.coordinate().present()) {
            return null;
        }

        ChunkPeerState state = CHANNEL_STATES.get(context.channel().id().asLongText());
        ChunkPeerChunkStateSnapshot chunkSnapshot = state == null ? null : state.invalidateChunk(frame.coordinate());
        ChunkShadowSnapshotManager.invalidateChunk(context.channel().id().asLongText(), frame.coordinate());
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
        ChunkPeerChunkStateSnapshot chunkSnapshot = state == null ? null : state.invalidateChunk(coordinate);
        ChunkShadowSnapshotManager.invalidateChunk(channel.id().asLongText(), coordinate);
        Bandwidthoptimizer.LOGGER.info(
                "[ChunkPeer][LifecycleInvalidate] player={}, uuid={}, channel={}, reason={}, chunk={}, state={}",
                player.getGameProfile().getName(),
                player.getUUID(),
                channel.id().asLongText(),
                reason,
                coordinate.logText(),
                chunkSnapshot == null ? "<missing>" : chunkSnapshot.summaryText()
        );
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
        ChunkPeerChunkStateSnapshot chunkSnapshot = state == null ? null : state.markChunkAwaitingFullReplay(coordinate);
        Bandwidthoptimizer.LOGGER.info(
                "[ChunkPeer][LifecycleRetain] player={}, uuid={}, channel={}, reason={}, chunk={}, state={}",
                player.getGameProfile().getName(),
                player.getUUID(),
                channel.id().asLongText(),
                reason,
                coordinate.logText(),
                chunkSnapshot == null ? "<missing>" : chunkSnapshot.summaryText()
        );
        return chunkSnapshot;
    }

    public static Channel findPlayerChannel(ServerPlayer player) {
        return readPlayerChannel(player);
    }

    private static String readPlayerChannelId(ServerPlayer player) {
        Channel channel = readPlayerChannel(player);
        return channel == null ? null : channel.id().asLongText();
    }

    private static Channel readPlayerChannel(ServerPlayer player) {
        Object listener = readObjectField(player, "connection");
        if (listener == null)
            return null;

        Object connection = readObjectMethod(listener, "getConnection");
        if (connection == null)
            connection = readObjectField(listener, "connection");

        if (connection == null)
            return null;

        return readTypedField(connection, Channel.class, "channel");
    }

    private static Object readObjectMethod(Object target, String methodName) {
        Method method = findNoArgMethod(target == null ? null : target.getClass(), methodName);
        if (method == null)
            return null;

        try {
            return method.invoke(target);
        } catch (ReflectiveOperationException ignored) {
            return null;
        }
    }

    // Class prevent problem
    private static Object readObjectField(Object target, String fieldName) {
        Field field = findField(target == null ? null : target.getClass(), fieldName);
        if (field == null)
            return null;

        try {
            return field.get(target);
        } catch (ReflectiveOperationException ignored) {
            return null;
        }
    }


    private static <T> T readTypedField(Object target, Class<T> expectedType, String fieldName) {
        Field field = findField(target == null ? null : target.getClass(), fieldName);
        if (field == null || expectedType == null || !expectedType.isAssignableFrom(field.getType())) {
            return null;
        }

        try {
            Object value = field.get(target);
            if (expectedType.isInstance(value)) {
                return expectedType.cast(value);
            }
        } catch (ReflectiveOperationException ignored) {
        }
        return null;
    }


    private static Method findNoArgMethod(Class<?> type, String methodName) {
        if (type == null || methodName == null || methodName.isBlank())
            return null;

        for (Method method : type.getMethods()) {
            if (method.getParameterCount() == 0 && methodName.equals(method.getName())) {
                method.setAccessible(true);
                return method;
            }
        }

        Class<?> currentType = type;
        while (currentType != null) {
            for (Method method : currentType.getDeclaredMethods()) {
                if (method.getParameterCount() == 0 && methodName.equals(method.getName())) {
                    method.setAccessible(true);
                    return method;
                }
            }
            currentType = currentType.getSuperclass();
        }
        return null;
    }

    private static Field findField(Class<?> type, String fieldName) {
        if (type == null || fieldName == null || fieldName.isBlank())
            return null;

        Class<?> currentType = type;
        while (currentType != null) {
            try {
                Field field = currentType.getDeclaredField(fieldName);
                field.setAccessible(true);
                return field;
            } catch (NoSuchFieldException ignored) {
                currentType = currentType.getSuperclass();
            }
        }
        return null;
    }

    private static void logControlUpdate(
            String label,
            ChannelHandlerContext context,
            ChunkHotspotFrame frame,
            ChunkPeerChunkStateSnapshot chunkSnapshot
    ) {
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

    private static String shortenHash(String hashHex) {
        if (hashHex == null || hashHex.isBlank()) {
            return "<none>";
        }
        return hashHex.length() <= 12 ? hashHex : hashHex.substring(0, 12);
    }
}
