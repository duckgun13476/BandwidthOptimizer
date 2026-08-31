package com.PinkCats.bandwidthoptimizer.gate.integration.create;

import com.PinkCats.bandwidthoptimizer.debug.DiagnosticLog;

import com.PinkCats.bandwidthoptimizer.Bandwidthoptimizer;
import com.PinkCats.bandwidthoptimizer.Config;
import com.PinkCats.bandwidthoptimizer.chunk.PeerState.ChunkPeerStateManager;
import com.PinkCats.bandwidthoptimizer.integration.minecraft.BlockEntityTypeKeyCompat;
import com.PinkCats.bandwidthoptimizer.integration.minecraft.ServerPlayerLevelCompat;
import com.PinkCats.bandwidthoptimizer.integration.sable.SableDynamicStructureCompat;
import com.PinkCats.bandwidthoptimizer.integration.valkyrienskies.ValkyrienSkiesDynamicStructureCompat;
import com.PinkCats.bandwidthoptimizer.debug.DiagnosticToolRegistry;
import com.PinkCats.bandwidthoptimizer.gate.recovery.IdleGateRecoveryRegistry;
import com.PinkCats.bandwidthoptimizer.integration.minecraft.NbtCompoundCompat;
import io.netty.channel.Channel;
import io.netty.channel.ChannelHandlerContext;
import net.minecraft.core.BlockPos;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.Tag;
import net.minecraft.network.protocol.Packet;
import net.minecraft.network.protocol.PacketFlow;
import net.minecraft.network.protocol.game.ClientboundBlockEntityDataPacket;
import net.minecraft.network.protocol.game.ClientboundPlayerPositionPacket;
import net.minecraft.network.protocol.game.ClientboundSetChunkCacheCenterPacket;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;

import java.util.ArrayList;
import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;

public final class CreateBlockEntityUpdateGate {

    private static final ConcurrentHashMap<UUID, PlayerState> PLAYER_STATES = new ConcurrentHashMap<>();
    private static final ConcurrentHashMap<String, UUID> CHANNEL_PLAYERS = new ConcurrentHashMap<>();
    private static final java.util.Set<Packet<?>> FORCED_PACKETS =
            Collections.synchronizedSet(Collections.newSetFromMap(new IdentityHashMap<>()));
    private static final Map<Packet<?>, PreparedDynamicTarget> PREPARED_DYNAMIC_TARGETS = new IdentityHashMap<>();
    private static final int MAX_PREPARED_DYNAMIC_TARGETS = 1024;
    private static final long PREPARED_DYNAMIC_TARGET_TTL_NANOS = TimeUnit.SECONDS.toNanos(2L);
    private static final long PREPARED_DYNAMIC_TARGET_PRUNE_INTERVAL_NANOS = TimeUnit.MILLISECONDS.toNanos(250L);
    private static long nextPreparedDynamicTargetPruneNanos;
    private static final AtomicLong DELAYED_COUNT = new AtomicLong();
    private static final AtomicLong SUPERSEDED_COUNT = new AtomicLong();
    private static final AtomicLong RELEASED_COUNT = new AtomicLong();
    private static final AtomicLong DROPPED_COUNT = new AtomicLong();
    private static final AtomicLong DELAYED_BYTES = new AtomicLong();
    private static final AtomicLong SUPERSEDED_SAVED_BYTES = new AtomicLong();
    private static final AtomicLong RELEASED_BYTES = new AtomicLong();
    private static final AtomicLong DROPPED_SAVED_BYTES = new AtomicLong();
    private static final AtomicLong LAST_CREATE_CONTRAPTION_FALLBACK_WARN_NANOS = new AtomicLong();
    private static final long CREATE_CONTRAPTION_FALLBACK_WARN_INTERVAL_NANOS = TimeUnit.MINUTES.toNanos(5L);
    private CreateBlockEntityUpdateGate() {}

    public static void bindPlayer(ServerPlayer player) {
        if (player == null) {
            return;
        }
        Channel channel = ChunkPeerStateManager.findPlayerChannel(player);
        String channelId = channel == null ? "" : com.PinkCats.bandwidthoptimizer.channel.ChannelIdentity.longText(channel);
        PlayerState state = PLAYER_STATES.computeIfAbsent(player.getUUID(), ignored -> new PlayerState());
        String previousChannelId = state.channelId();
        if (previousChannelId != null && !previousChannelId.isBlank() && !previousChannelId.equals(channelId)) {
            CHANNEL_PLAYERS.remove(previousChannelId, player.getUUID());
        }
        state.bind(player, channelId);
        if (!channelId.isBlank()) {
            CHANNEL_PLAYERS.put(channelId, player.getUUID());
        }
    }

    public static void clearPlayer(ServerPlayer player, String reason) {
        if (player == null) {
            return;
        }
        IdleGateRecoveryRegistry.discard(player);
        PlayerState state = PLAYER_STATES.remove(player.getUUID());
        if (state != null) {
            String channelId = state.channelId();
            if (channelId != null && !channelId.isBlank()) {
                CHANNEL_PLAYERS.remove(channelId, player.getUUID());
            }
            PendingDropStats dropped = state.clearPending();
            if (!dropped.isEmpty()) {
                recordDropped(dropped);
                logDiagnose("event=create_update_clear player={}, reason={}, dropped={}",
                        player.getName().getString(),
                        reason == null ? "" : reason,
                        dropped.count());
            }
        }
    }

    public static void dropPendingChunk(ServerPlayer player, ChunkPos chunkPos, String reason) {
        if (player == null || chunkPos == null) {
            return;
        }
        PlayerState state = PLAYER_STATES.get(player.getUUID());
        if (state == null) {
            return;
        }
        PendingDropStats dropped = state.dropChunk(
                com.PinkCats.bandwidthoptimizer.integration.minecraft.ChunkCoordinateCompat.x(chunkPos),
                com.PinkCats.bandwidthoptimizer.integration.minecraft.ChunkCoordinateCompat.z(chunkPos)
        );
        if (!dropped.isEmpty()) {
            recordDropped(dropped);
            logDiagnose("event=create_update_drop_chunk player={}, chunk=({}, {}), reason={}, dropped={}",
                    player.getName().getString(),
                    com.PinkCats.bandwidthoptimizer.integration.minecraft.ChunkCoordinateCompat.x(chunkPos),
                    com.PinkCats.bandwidthoptimizer.integration.minecraft.ChunkCoordinateCompat.z(chunkPos),
                    reason == null ? "" : reason,
                    dropped.count());
        }
    }

    public static boolean tryDelayOutboundPacket(
            ChannelHandlerContext context,
            String protocolName,
            PacketFlow packetFlow,
            Packet<?> packet,
            byte[] originalPacketBytes
    ) {
        if (!isEnabled()
                || context == null
                || packet == null
                || packetFlow != PacketFlow.CLIENTBOUND
                || protocolName == null
                || !"PLAY".equalsIgnoreCase(protocolName)) {
            return false;
        }
        if (consumeForcedPacket(packet)) {
            return false;
        }
        if (!(packet instanceof ClientboundBlockEntityDataPacket blockEntityDataPacket)) {
            return false;
        }
        String blockEntityTypeKey = BlockEntityTypeKeyCompat.keyOf(blockEntityDataPacket.getType());
        if (!isCreateBlockEntity(blockEntityTypeKey)) {
            return false;
        }

        ServerPlayer player = resolvePlayer(context);
        if (player == null) {
            return false;
        }
        PlayerState state = PLAYER_STATES.computeIfAbsent(player.getUUID(), ignored -> new PlayerState());
        state.bind(player, com.PinkCats.bandwidthoptimizer.channel.ChannelIdentity.longText(context.channel()));
        long nowNanos = System.nanoTime();
        boolean chunkBootstrapActive = state.isChunkBootstrapActive(nowNanos);
        if (!shouldGateCreateBlockEntity(blockEntityTypeKey, chunkBootstrapActive)) {
            return false;
        }
        PendingKey key = PendingKey.of(blockEntityTypeKey, blockEntityDataPacket.getPos());
        boolean soundCritical = state.rememberSoundStateAndShouldFlush(key, blockEntityDataPacket.getTag());
        DynamicTarget dynamicTarget = resolveDynamicTargetForEncoder(
                player,
                packet,
                blockEntityDataPacket.getPos(),
                blockEntityTypeKey);
        if (dynamicTarget.forceImmediate()
                || CreateGateViewPolicy.shouldSendImmediately(
                        player,
                        dynamicTarget.points(),
                        allowLookDirectionForGatedUpdate(blockEntityTypeKey, chunkBootstrapActive))
                || soundCritical && isWithinSoundSendDistance(player, dynamicTarget.target(), blockEntityTypeKey)) {
            recordDropped(state.forgetPending(key));
            removePreparedDynamicTarget(packet);
            return false;
        }

        int originalRawBytes = lengthOf(originalPacketBytes);
        boolean accepted = state.rememberLatest(key, packet, originalRawBytes, dynamicTarget);
        if (!accepted) {
            removePreparedDynamicTarget(packet);
            return false;
        }
        removePreparedDynamicTarget(packet);
        long delayed = DELAYED_COUNT.incrementAndGet();
        DELAYED_BYTES.addAndGet(originalRawBytes);
        if (shouldLogSample(delayed)) {
            logDiagnose("event=create_update_delay player={}, type={}, pos={}, delayed={}, superseded={}, released={}, dropped={}",
                    player.getName().getString(),
                    blockEntityTypeKey,
                    blockEntityDataPacket.getPos(),
                    delayed,
                    SUPERSEDED_COUNT.get(),
                    RELEASED_COUNT.get(),
                    DROPPED_COUNT.get());
        }
        return true;
    }

    // Move distant Create updates before they enter the Netty send queue.
    public static boolean tryDelayConnectionSend(Channel channel, Packet<?> packet, Object listener) {
        if (IdleGateRecoveryRegistry.tryCapture(channel, packet, listener)) {
            return true;
        }
        if (!isEnabled()
                || channel == null
                || packet == null
                || isForcedPacket(packet)
                || !(packet instanceof ClientboundBlockEntityDataPacket blockEntityDataPacket)) {
            return false;
        }
        String blockEntityTypeKey = BlockEntityTypeKeyCompat.keyOf(blockEntityDataPacket.getType());
        if (!isCreateBlockEntity(blockEntityTypeKey)) {
            return false;
        }

        ServerPlayer player = resolvePlayer(channel);
        if (player == null) {
            return false;
        }

        PlayerState state = PLAYER_STATES.computeIfAbsent(player.getUUID(), ignored -> new PlayerState());
        state.bind(player, com.PinkCats.bandwidthoptimizer.channel.ChannelIdentity.longText(channel));
        long nowNanos = System.nanoTime();
        boolean chunkBootstrapActive = state.isChunkBootstrapActive(nowNanos);
        if (!shouldGateCreateBlockEntity(blockEntityTypeKey, chunkBootstrapActive)) {
            return false;
        }
        if (listener != null && !isMovingContraptionController(blockEntityTypeKey)) {
            return false;
        }
        DynamicTarget dynamicTarget = resolveDynamicTargetForConnectionSend(
                player,
                blockEntityDataPacket.getPos(),
                blockEntityTypeKey);
        if (listener != null) {
            rememberPreparedDynamicTarget(packet, dynamicTarget);
            return false;
        }
        boolean delayed = tryRememberDelayedBlockEntity(
                player,
                state,
                blockEntityDataPacket,
                blockEntityTypeKey,
                packet,
                estimateBlockEntityDataPacketBytes(blockEntityDataPacket),
                dynamicTarget);
        if (!delayed && isMovingContraptionController(blockEntityTypeKey)) {
            rememberPreparedDynamicTarget(packet, dynamicTarget);
        }
        return delayed;
    }

    // Start bootstrap gating before packets enter Netty.
    public static void observeConnectionSend(Channel channel, Packet<?> packet) {
        if (!isEnabled()
                || channel == null
                || !isChunkBootstrapBoundaryPacket(packet)) {
            return;
        }
        ServerPlayer player = resolvePlayer(channel);
        if (player == null) {
            return;
        }
        PlayerState state = PLAYER_STATES.computeIfAbsent(player.getUUID(), ignored -> new PlayerState());
        state.bind(player, com.PinkCats.bandwidthoptimizer.channel.ChannelIdentity.longText(channel));
        state.markChunkBootstrap(System.nanoTime(), CreateGateQueueConfig.chunkBootstrapNanos());
    }

    public static void observeOutboundPacket(
            ChannelHandlerContext context,
            String protocolName,
            PacketFlow packetFlow,
            Packet<?> packet
    ) {
        if (!isEnabled()
                || context == null
                || packetFlow != PacketFlow.CLIENTBOUND
                || protocolName == null
                || !"PLAY".equalsIgnoreCase(protocolName)
                || !isChunkBootstrapBoundaryPacket(packet)) {
            return;
        }
        ServerPlayer player = resolvePlayer(context);
        if (player == null) {
            return;
        }
        PlayerState state = PLAYER_STATES.computeIfAbsent(player.getUUID(), ignored -> new PlayerState());
        state.bind(player, com.PinkCats.bandwidthoptimizer.channel.ChannelIdentity.longText(context.channel()));
        state.markChunkBootstrap(System.nanoTime(), CreateGateQueueConfig.chunkBootstrapNanos());
    }

    // TP commands can enqueue the position packet after Create has already filled the send path.
    public static void markCommandTeleportBootstrap(ServerPlayer player) {
        if (!isEnabled() || player == null) {
            return;
        }
        PlayerState state = PLAYER_STATES.computeIfAbsent(player.getUUID(), ignored -> new PlayerState());
        state.bind(player, currentChannelId(player));
        state.markChunkBootstrap(System.nanoTime(), CreateGateQueueConfig.chunkBootstrapNanos());
    }

    public static void onServerTick() {
        if (!isEnabled() || PLAYER_STATES.isEmpty()) {
            return;
        }
        long nowNanos = System.nanoTime();
        for (PlayerState state : PLAYER_STATES.values()) {
            ServerPlayer player = state.player();
            if (player == null || player.connection == null) {
                continue;
            }
            List<PendingUpdate> readyUpdates = state.drainReady(player, nowNanos);
            for (PendingUpdate pendingUpdate : readyUpdates) {
                sendForced(player, pendingUpdate.packet());
                long released = RELEASED_COUNT.incrementAndGet();
                RELEASED_BYTES.addAndGet(pendingUpdate.rawBytes());
                if (shouldLogSample(released)) {
                    logDiagnose("event=create_update_release player={}, type={}, pos={}, reason={}, rawBytes={}, superseded={}",
                            player.getName().getString(),
                            pendingUpdate.key().typeKey(),
                            pendingUpdate.key().pos(),
                            pendingUpdate.releaseReason(),
                            pendingUpdate.rawBytes(),
                            pendingUpdate.supersededCount());
                }
            }
        }
    }

    public static void resetStats() {
        DELAYED_COUNT.set(0L);
        SUPERSEDED_COUNT.set(0L);
        RELEASED_COUNT.set(0L);
        DROPPED_COUNT.set(0L);
        DELAYED_BYTES.set(0L);
        SUPERSEDED_SAVED_BYTES.set(0L);
        RELEASED_BYTES.set(0L);
        DROPPED_SAVED_BYTES.set(0L);
    }

    public static Snapshot snapshotStats() {
        return new Snapshot(
                DELAYED_COUNT.get(),
                SUPERSEDED_COUNT.get(),
                RELEASED_COUNT.get(),
                DROPPED_COUNT.get(),
                DELAYED_BYTES.get(),
                SUPERSEDED_SAVED_BYTES.get(),
                RELEASED_BYTES.get(),
                DROPPED_SAVED_BYTES.get());
    }

    private static void recordDropped(PendingDropStats dropped) {
        if (dropped == null || dropped.isEmpty()) {
            return;
        }
        DROPPED_COUNT.addAndGet(dropped.count());
        DROPPED_SAVED_BYTES.addAndGet(dropped.rawBytes());
    }

    private static void recordSuperseded(PendingUpdate existing) {
        if (existing == null) {
            return;
        }
        SUPERSEDED_COUNT.incrementAndGet();
        SUPERSEDED_SAVED_BYTES.addAndGet(existing.rawBytes());
    }

    // Share deferred Create update accounting between send and encode gates.
    private static boolean tryRememberDelayedBlockEntity(
            ServerPlayer player,
            PlayerState state,
            ClientboundBlockEntityDataPacket blockEntityDataPacket,
            String blockEntityTypeKey,
            Packet<?> packet,
            int originalRawBytes,
            DynamicTarget dynamicTarget
    ) {
        long nowNanos = System.nanoTime();
        boolean chunkBootstrapActive = state.isChunkBootstrapActive(nowNanos);
        PendingKey key = PendingKey.of(blockEntityTypeKey, blockEntityDataPacket.getPos());
        boolean soundCritical = state.rememberSoundStateAndShouldFlush(key, blockEntityDataPacket.getTag());
        if (dynamicTarget == null) {
            dynamicTarget = forceImmediateTarget(blockEntityDataPacket.getPos());
        }
        if (dynamicTarget.forceImmediate()
                || CreateGateViewPolicy.shouldSendImmediately(
                        player,
                        dynamicTarget.points(),
                        allowLookDirectionForGatedUpdate(blockEntityTypeKey, chunkBootstrapActive))
                || soundCritical && isWithinSoundSendDistance(player, dynamicTarget.target(), blockEntityTypeKey)) {
            recordDropped(state.forgetPending(key));
            return false;
        }

        boolean accepted = state.rememberLatest(key, packet, originalRawBytes, dynamicTarget);
        if (!accepted) {
            return false;
        }
        long delayed = DELAYED_COUNT.incrementAndGet();
        DELAYED_BYTES.addAndGet(originalRawBytes);
        if (shouldLogSample(delayed)) {
            logDiagnose("event=create_update_delay player={}, type={}, pos={}, delayed={}, superseded={}, released={}, dropped={}",
                    player.getName().getString(),
                    blockEntityTypeKey,
                    blockEntityDataPacket.getPos(),
                    delayed,
                    SUPERSEDED_COUNT.get(),
                    RELEASED_COUNT.get(),
                    DROPPED_COUNT.get());
        }
        return true;
    }

    private static ServerPlayer resolvePlayer(ChannelHandlerContext context) {
        if (context == null || context.channel() == null) {
            return null;
        }
        return resolvePlayer(context.channel());
    }

    private static ServerPlayer resolvePlayer(Channel channel) {
        return resolveBoundPlayer(channel);
    }

    public static ServerPlayer resolveBoundPlayer(Channel channel) {
        if (channel == null) {
            return null;
        }
        UUID playerId = CHANNEL_PLAYERS.get(com.PinkCats.bandwidthoptimizer.channel.ChannelIdentity.longText(channel));
        if (playerId == null) {
            return null;
        }
        PlayerState state = PLAYER_STATES.get(playerId);
        return state == null ? null : state.player();
    }

    private static String currentChannelId(ServerPlayer player) {
        Channel channel = ChunkPeerStateManager.findPlayerChannel(player);
        return channel == null ? "" : com.PinkCats.bandwidthoptimizer.channel.ChannelIdentity.longText(channel);
    }

    public static boolean shouldBypassTransparentTransport(
            ChannelHandlerContext context,
            String protocolName,
            PacketFlow packetFlow,
            Packet<?> packet
    ) {
        if (!isEnabled()
                || context == null
                || packet == null
                || packetFlow != PacketFlow.CLIENTBOUND
                || protocolName == null
                || !"PLAY".equalsIgnoreCase(protocolName)
                || !(packet instanceof ClientboundBlockEntityDataPacket blockEntityDataPacket)) {
            return false;
        }
        String blockEntityTypeKey = BlockEntityTypeKeyCompat.keyOf(blockEntityDataPacket.getType());
        if (!isVisibleRawBypassController(blockEntityTypeKey)) {
            return false;
        }
        ServerPlayer player = resolvePlayer(context);
        if (player == null) {
            return false;
        }
        DynamicTarget dynamicTarget = resolveDynamicTargetForEncoder(
                player,
                packet,
                blockEntityDataPacket.getPos(),
                blockEntityTypeKey);
        try {
            return dynamicTarget.forceImmediate() || CreateGateViewPolicy.shouldSendImmediately(player, dynamicTarget.points(), true);
        } finally {
            removePreparedDynamicTarget(packet);
        }
    }

    public static boolean shouldBypassCreateGateDelay(
            ChannelHandlerContext context,
            String protocolName,
            PacketFlow packetFlow,
            Packet<?> packet
    ) {
        return shouldBypassTransparentTransport(context, protocolName, packetFlow, packet);
    }

    private static boolean isMovingContraptionController(String typeKey) {
        return CreateGateTypePolicy.isMovingContraptionController(typeKey);
    }

    private static boolean isVisibleRawBypassController(String typeKey) {
        return CreateGateTypePolicy.isVisibleRawBypassController(typeKey);
    }

    private static boolean allowLookDirectionForGatedUpdate(String typeKey, boolean chunkBootstrapActive) {
        return CreateGateTypePolicy.allowLookDirectionForGatedUpdate(typeKey, chunkBootstrapActive);
    }

    // Keep interactive controls out of delayed merging.
    private static boolean shouldGateCreateBlockEntity(String typeKey, boolean chunkBootstrapActive) {
        return CreateGateTypePolicy.shouldGate(typeKey);
    }

    private static boolean isSoundClassifiedBlockEntity(String typeKey) {
        return CreateGateTypePolicy.isSoundClassified(typeKey);
    }

    private static boolean isCreateBlockEntity(String typeKey) {
        return CreateGateTypePolicy.isCreateBlockEntity(typeKey);
    }

    // PlayerPosition starts the TP chunk bootstrap earlier than the cache-center control packet.
    private static boolean isChunkBootstrapBoundaryPacket(Packet<?> packet) {
        return packet instanceof ClientboundSetChunkCacheCenterPacket
                || packet instanceof ClientboundPlayerPositionPacket;
    }

    private static void sendForced(ServerPlayer player, Packet<?> packet) {
        if (player == null || packet == null || player.connection == null) {
            return;
        }
        FORCED_PACKETS.add(packet);
        player.connection.send(packet);
    }

    public static void sendRecoveryPacket(ServerPlayer player, Packet<?> packet) {
        sendForced(player, packet);
    }

    private static boolean consumeForcedPacket(Packet<?> packet) {
        if (packet == null) {
            return false;
        }
        return FORCED_PACKETS.remove(packet);
    }

    private static boolean isForcedPacket(Packet<?> packet) {
        return packet != null && FORCED_PACKETS.contains(packet);
    }

    static boolean isEnabled() {
        return Boolean.parseBoolean(System.getProperty(
                Config.RuntimeProperty.Create.CREATE_BLOCK_ENTITY_UPDATE_GATE_ENABLED,
                Boolean.toString(Config.RuntimeProperty.Create.DEFAULT_CREATE_BLOCK_ENTITY_UPDATE_GATE_ENABLED)));
    }

    private static boolean isWithinSoundSendDistance(ServerPlayer player, Vec3 target, String typeKey) {
        if (player == null || target == null || typeKey == null) {
            return true;
        }
        double soundDistance = soundSendDistanceBlocks(typeKey);
        return player.getEyePosition().distanceToSqr(target) <= soundDistance * soundDistance;
    }

    private static DynamicTarget resolveDynamicTargetForEncoder(
            ServerPlayer player,
            Packet<?> packet,
            BlockPos pos,
            String typeKey
    ) {
        if (isMovingContraptionController(typeKey)) {
            DynamicTarget preparedTarget = readPreparedDynamicTarget(packet);
            return preparedTarget == null ? forceImmediateTarget(pos) : preparedTarget;
        }
        return resolveDynamicTargetOnServerThread(player, pos, typeKey);
    }

    private static DynamicTarget resolveDynamicTargetForConnectionSend(
            ServerPlayer player,
            BlockPos pos,
            String typeKey
    ) {
        return isMovingContraptionController(typeKey)
                ? (isServerThread(player)
                ? resolveDynamicTargetOnServerThread(player, pos, typeKey)
                : forceImmediateTarget(pos))
                : resolveDynamicTargetOnServerThread(player, pos, typeKey);
    }

    private static DynamicTarget resolveDynamicTargetOnServerThread(ServerPlayer player, BlockPos pos, String typeKey) {
        Vec3 vanillaTarget = centerOf(pos);
        Vec3[] vanillaPoints = cornersOf(pos);
        if (isMovingContraptionController(typeKey)) {
            DynamicTarget createTarget = resolveCreateContraptionTarget(player, pos);
            if (createTarget != null) {
                return createTarget;
            }
            warnCreateContraptionFallback(player, typeKey, pos);
            return new DynamicTarget(vanillaTarget, vanillaPoints, true, false);
        }
        SableDynamicStructureCompat.DynamicTarget sableTarget =
                SableDynamicStructureCompat.resolveTarget(player, pos, vanillaTarget);
        if (sableTarget.forceImmediate()) {
            return new DynamicTarget(sableTarget.target(), vanillaPoints, true, false);
        }
        if (sableTarget.transformed()) {
            ResolvedPoints resolvedPoints = resolveSablePoints(player, pos, vanillaPoints);
            return new DynamicTarget(sableTarget.target(), resolvedPoints.points(), resolvedPoints.forceImmediate(), true);
        }
        ValkyrienSkiesDynamicStructureCompat.DynamicTarget valkyrienSkiesTarget =
                ValkyrienSkiesDynamicStructureCompat.resolveTarget(player, pos, vanillaTarget);
        if (valkyrienSkiesTarget.forceImmediate()) {
            return new DynamicTarget(valkyrienSkiesTarget.target(), vanillaPoints, true, false);
        }
        if (valkyrienSkiesTarget.transformed()) {
            ResolvedPoints resolvedPoints = resolveValkyrienSkiesPoints(player, pos, vanillaPoints);
            return new DynamicTarget(
                    valkyrienSkiesTarget.target(),
                    resolvedPoints.points(),
                    resolvedPoints.forceImmediate(),
                    true);
        }
        return new DynamicTarget(vanillaTarget, vanillaPoints, false, false);
    }

    private static boolean isServerThread(ServerPlayer player) {
        Level level = ServerPlayerLevelCompat.serverLevel(player);
        return level != null && level.getServer() != null && level.getServer().isSameThread();
    }

    private static DynamicTarget forceImmediateTarget(BlockPos pos) {
        Vec3 target = centerOf(pos);
        return new DynamicTarget(target, cornersOf(pos), true, false);
    }

    private static void rememberPreparedDynamicTarget(Packet<?> packet, DynamicTarget dynamicTarget) {
        if (packet == null || dynamicTarget == null) {
            return;
        }
        long nowNanos = System.nanoTime();
        synchronized (PREPARED_DYNAMIC_TARGETS) {
            if (nowNanos >= nextPreparedDynamicTargetPruneNanos
                    || PREPARED_DYNAMIC_TARGETS.size() >= MAX_PREPARED_DYNAMIC_TARGETS) {
                prunePreparedDynamicTargets(nowNanos);
                nextPreparedDynamicTargetPruneNanos = nowNanos + PREPARED_DYNAMIC_TARGET_PRUNE_INTERVAL_NANOS;
            }
            PREPARED_DYNAMIC_TARGETS.put(packet, new PreparedDynamicTarget(dynamicTarget, nowNanos));
        }
    }

    private static DynamicTarget readPreparedDynamicTarget(Packet<?> packet) {
        if (packet == null) {
            return null;
        }
        long nowNanos = System.nanoTime();
        synchronized (PREPARED_DYNAMIC_TARGETS) {
            PreparedDynamicTarget prepared = PREPARED_DYNAMIC_TARGETS.get(packet);
            if (prepared == null || nowNanos - prepared.preparedAtNanos() > PREPARED_DYNAMIC_TARGET_TTL_NANOS) {
                PREPARED_DYNAMIC_TARGETS.remove(packet);
                return null;
            }
            return prepared.dynamicTarget();
        }
    }

    private static void removePreparedDynamicTarget(Packet<?> packet) {
        if (packet != null) {
            synchronized (PREPARED_DYNAMIC_TARGETS) {
                PREPARED_DYNAMIC_TARGETS.remove(packet);
            }
        }
    }

    private static void prunePreparedDynamicTargets(long nowNanos) {
        int removalsNeeded = Math.max(PREPARED_DYNAMIC_TARGETS.size() - MAX_PREPARED_DYNAMIC_TARGETS + 1, 0);
        Iterator<Map.Entry<Packet<?>, PreparedDynamicTarget>> iterator = PREPARED_DYNAMIC_TARGETS.entrySet().iterator();
        while (iterator.hasNext()) {
            Map.Entry<Packet<?>, PreparedDynamicTarget> entry = iterator.next();
            if (nowNanos - entry.getValue().preparedAtNanos() > PREPARED_DYNAMIC_TARGET_TTL_NANOS
                    || removalsNeeded > 0) {
                iterator.remove();
                if (removalsNeeded > 0) {
                    removalsNeeded--;
                }
            }
        }
    }

    private static DynamicTarget resolveCreateContraptionTarget(ServerPlayer player, BlockPos pos) {
        if (player == null || pos == null) {
            return null;
        }
        Level level = ServerPlayerLevelCompat.serverLevel(player);
        if (level == null) {
            return null;
        }
        BlockEntity blockEntity = level.getBlockEntity(pos);
        List<Entity> contraptions = new ArrayList<>();
        CreateContraptionReferenceResolver.Resolution resolution = resolveCreateContraptionReferences(blockEntity);
        if (resolution == null) {
            return null;
        }
        for (Object reference : resolution.references()) {
            collectCreateContraption(contraptions, reference);
        }
        if (contraptions.isEmpty() && resolution.requiresNearbyScan()) {
            contraptions.addAll(scanNearbyCreateContraptions(level, pos));
        }
        AABB bounds = null;
        for (Entity entity : contraptions) {
            if (!isUsableCreateContraption(entity)) {
                continue;
            }
            AABB entityBounds = entity.getBoundingBox();
            if (entityBounds == null) {
                continue;
            }
            bounds = bounds == null ? entityBounds : union(bounds, entityBounds);
        }
        if (bounds == null) {
            return null;
        }
        return new DynamicTarget(centerOf(bounds), cornersOf(bounds), false, true);
    }

    static CreateContraptionReferenceResolver.Resolution resolveCreateContraptionReferences(BlockEntity blockEntity) {
        if (blockEntity == null) {
            return null;
        }
        return CreateContraptionReferenceResolver.resolve(blockEntity, BlockEntityTypeKeyCompat.keyOf(blockEntity.getType()));
    }

    private static List<Entity> scanNearbyCreateContraptions(Level level, BlockPos pos) {
        if (level == null || pos == null) {
            return List.of();
        }
        AABB searchBox = new AABB(
                pos.getX() - 128.0D,
                pos.getY() - 128.0D,
                pos.getZ() - 128.0D,
                pos.getX() + 129.0D,
                pos.getY() + 129.0D,
                pos.getZ() + 129.0D);
        return level.getEntities((Entity) null, searchBox, entity ->
                isUsableCreateContraption(entity) && isCreateContraptionNearController(entity, pos));
    }

    private static boolean isCreateContraptionNearController(Entity entity, BlockPos pos) {
        if (entity == null || pos == null) {
            return false;
        }
        Object anchor = CreateContraptionReferenceResolver.readAnchorVector(entity);
        Vec3 target = anchor instanceof Vec3 anchorVec ? anchorVec : entity.position();
        return target.distanceToSqr(centerOf(pos)) <= 128.0D * 128.0D;
    }

    private static boolean isUsableCreateContraption(Entity entity) {
        return entity != null && !entity.isRemoved() && isCreateContraptionEntity(entity);
    }

    private static boolean isCreateContraptionEntity(Entity entity) {
        Class<?> type = entity == null ? null : entity.getClass();
        while (type != null) {
            if ("com.simibubi.create.content.contraptions.AbstractContraptionEntity".equals(type.getName())) {
                return true;
            }
            type = type.getSuperclass();
        }
        return false;
    }

    private static void collectCreateContraption(List<Entity> contraptions, Object value) {
        if (contraptions == null || value == null) {
            return;
        }
        if (value instanceof java.lang.ref.Reference<?> reference) {
            collectCreateContraption(contraptions, reference.get());
            return;
        }
        if (value instanceof Entity entity && isUsableCreateContraption(entity) && !contraptions.contains(entity)) {
            contraptions.add(entity);
        }
    }

    private static AABB union(AABB first, AABB second) {
        return new AABB(
                Math.min(first.minX, second.minX),
                Math.min(first.minY, second.minY),
                Math.min(first.minZ, second.minZ),
                Math.max(first.maxX, second.maxX),
                Math.max(first.maxY, second.maxY),
                Math.max(first.maxZ, second.maxZ));
    }

    private static Vec3 centerOf(AABB bounds) {
        if (bounds == null) {
            return null;
        }
        return new Vec3(
                (bounds.minX + bounds.maxX) * 0.5D,
                (bounds.minY + bounds.maxY) * 0.5D,
                (bounds.minZ + bounds.maxZ) * 0.5D);
    }

    private static Vec3[] cornersOf(AABB bounds) {
        if (bounds == null) {
            return new Vec3[0];
        }
        return new Vec3[] {
                new Vec3(bounds.minX, bounds.minY, bounds.minZ),
                new Vec3(bounds.maxX, bounds.minY, bounds.minZ),
                new Vec3(bounds.minX, bounds.maxY, bounds.minZ),
                new Vec3(bounds.maxX, bounds.maxY, bounds.minZ),
                new Vec3(bounds.minX, bounds.minY, bounds.maxZ),
                new Vec3(bounds.maxX, bounds.minY, bounds.maxZ),
                new Vec3(bounds.minX, bounds.maxY, bounds.maxZ),
                new Vec3(bounds.maxX, bounds.maxY, bounds.maxZ),
                centerOf(bounds)
        };
    }

    private static void warnCreateContraptionFallback(ServerPlayer player, String typeKey, BlockPos pos) {
        long nowNanos = System.nanoTime();
        long previousNanos = LAST_CREATE_CONTRAPTION_FALLBACK_WARN_NANOS.get();
        if (nowNanos - previousNanos < CREATE_CONTRAPTION_FALLBACK_WARN_INTERVAL_NANOS) {
            return;
        }
        if (!LAST_CREATE_CONTRAPTION_FALLBACK_WARN_NANOS.compareAndSet(previousNanos, nowNanos)) {
            return;
        }
        Bandwidthoptimizer.LOGGER.warn(
                "[BO-CreateGate] Failed to resolve Create contraption bounds; sending update directly. player={}, type={}, pos={}",
                player == null ? "<unknown>" : player.getName().getString(),
                typeKey,
                pos);
    }

    private static Vec3 centerOf(BlockPos pos) {
        if (pos == null) {
            return null;
        }
        return new Vec3(pos.getX() + 0.5D, pos.getY() + 0.5D, pos.getZ() + 0.5D);
    }

    private static Vec3[] cornersOf(BlockPos pos) {
        if (pos == null) {
            return new Vec3[0];
        }
        double minX = pos.getX();
        double minY = pos.getY();
        double minZ = pos.getZ();
        double maxX = minX + 1.0D;
        double maxY = minY + 1.0D;
        double maxZ = minZ + 1.0D;
        return new Vec3[] {
                new Vec3(minX, minY, minZ),
                new Vec3(maxX, minY, minZ),
                new Vec3(minX, maxY, minZ),
                new Vec3(maxX, maxY, minZ),
                new Vec3(minX, minY, maxZ),
                new Vec3(maxX, minY, maxZ),
                new Vec3(minX, maxY, maxZ),
                new Vec3(maxX, maxY, maxZ)
        };
    }

    private static ResolvedPoints resolveSablePoints(ServerPlayer player, BlockPos pos, Vec3[] vanillaPoints) {
        Vec3[] resolved = new Vec3[vanillaPoints.length];
        for (int index = 0; index < vanillaPoints.length; index++) {
            SableDynamicStructureCompat.DynamicTarget pointTarget =
                    SableDynamicStructureCompat.resolveTarget(player, pos, vanillaPoints[index]);
            if (pointTarget.forceImmediate()) {
                return new ResolvedPoints(vanillaPoints, true);
            }
            resolved[index] = pointTarget.target();
        }
        return new ResolvedPoints(resolved, false);
    }

    private static ResolvedPoints resolveValkyrienSkiesPoints(ServerPlayer player, BlockPos pos, Vec3[] vanillaPoints) {
        Vec3[] resolved = new Vec3[vanillaPoints.length];
        for (int index = 0; index < vanillaPoints.length; index++) {
            ValkyrienSkiesDynamicStructureCompat.DynamicTarget pointTarget =
                    ValkyrienSkiesDynamicStructureCompat.resolveTarget(player, pos, vanillaPoints[index]);
            if (pointTarget.forceImmediate()) {
                return new ResolvedPoints(vanillaPoints, true);
            }
            resolved[index] = pointTarget.target();
        }
        return new ResolvedPoints(resolved, false);
    }

    private static double soundSendDistanceBlocks(String typeKey) {
        return CreateGateTypePolicy.soundSendDistanceBlocks(typeKey);
    }

    private static int lengthOf(byte[] bytes) {
        return bytes == null ? 0 : bytes.length;
    }

    private static int estimateBlockEntityDataPacketBytes(ClientboundBlockEntityDataPacket packet) {
        if (packet == null) {
            return 0;
        }
        return 1 // packet id varint
                + 8 // block position long
                + 5 // block entity type varint upper bound
                + estimateCompoundTagBytes(packet.getTag(), 0);
    }

    private static int estimateCompoundTagBytes(CompoundTag tag, int depth) {
        if (tag == null || tag.isEmpty()) {
            return 1;
        }
        if (depth >= 4) {
            return 64;
        }
        int bytes = 1; // TAG_End marker
        for (String key : NbtCompoundCompat.keys(tag)) {
            bytes += 1 + 2 + key.length();
            bytes += estimateTagPayloadBytes(tag.get(key), depth + 1);
        }
        return Math.max(bytes, 1);
    }

    private static int estimateTagPayloadBytes(Tag tag, int depth) {
        if (tag == null) {
            return 0;
        }
        return switch (tag.getId()) {
            case 1 -> 1;
            case 2 -> 2;
            case 3, 5 -> 4;
            case 4, 6 -> 8;
            case 7, 11 -> 16;
            case 8 -> 16;
            case 9 -> 24;
            case 10 -> tag instanceof CompoundTag compoundTag
                    ? estimateCompoundTagBytes(compoundTag, depth)
                    : 64;
            case 12 -> 32;
            default -> 8;
        };
    }

    private static boolean shouldLogSample(long count) {
        return count <= 10L || count % 1000L == 0L;
    }

    private static void logDiagnose(String message, Object... args) {
        if (BO_Diag_compatDynamicGates()) {
            DiagnosticLog.info(DiagnosticToolRegistry.Tool.COMPAT_DYNAMIC_GATES, "" + message, args);
        }
    }

    private static boolean BO_Diag_compatDynamicGates() {
        return DiagnosticToolRegistry.isEnabled(DiagnosticToolRegistry.Tool.COMPAT_DYNAMIC_GATES);
    }

    private record PendingKey(String typeKey, BlockPos pos, int chunkX, int chunkZ) {
        private static PendingKey of(String typeKey, BlockPos pos) {
            return new PendingKey(typeKey, pos, pos.getX() >> 4, pos.getZ() >> 4);
        }
    }

    private record DynamicTarget(Vec3 target, Vec3[] points, boolean forceImmediate, boolean refreshRequired) {}

    private record PreparedDynamicTarget(DynamicTarget dynamicTarget, long preparedAtNanos) {}

    private record ResolvedPoints(Vec3[] points, boolean forceImmediate) {}

    public record Snapshot(
            long delayedPackets,
            long supersededPackets,
            long releasedPackets,
            long droppedPackets,
            long delayedBytes,
            long supersededSavedBytes,
            long releasedBytes,
            long droppedSavedBytes
    ) {
        public long savedBytes() {
            return Math.max(this.supersededSavedBytes + this.droppedSavedBytes, 0L);
        }

        public long observedBytes() {
            return Math.max(this.savedBytes() + this.releasedBytes, 0L);
        }

        public long savedPackets() {
            return Math.max(this.supersededPackets + this.droppedPackets, 0L);
        }
    }

    private record PendingDropStats(int count, long rawBytes) {
        private static final PendingDropStats EMPTY = new PendingDropStats(0, 0L);

        private boolean isEmpty() {
            return this.count <= 0;
        }

        private PendingDropStats plus(PendingUpdate update) {
            if (update == null) {
                return this;
            }
            return new PendingDropStats(this.count + 1, this.rawBytes + update.rawBytes());
        }
    }

    private record PendingUpdate(
            PendingKey key,
            Packet<?> packet,
            int rawBytes,
            long firstQueuedNanos,
            long lastUpdatedNanos,
            int supersededCount,
            String releaseReason,
            DynamicTarget dynamicTarget
    ) {
        private PendingUpdate withLatest(
                Packet<?> nextPacket,
                int nextRawBytes,
                long nowNanos,
                DynamicTarget nextDynamicTarget
        ) {
            return new PendingUpdate(
                    this.key,
                    nextPacket,
                    nextRawBytes,
                    this.firstQueuedNanos,
                    nowNanos,
                    this.supersededCount + 1,
                    this.releaseReason,
                    nextDynamicTarget);
        }

        private PendingUpdate withReleaseReason(String reason) {
            return new PendingUpdate(
                    this.key,
                    this.packet,
                    this.rawBytes,
                    this.firstQueuedNanos,
                    this.lastUpdatedNanos,
                    this.supersededCount,
                    reason,
                    this.dynamicTarget);
        }

        private PendingUpdate withDynamicTarget(DynamicTarget nextDynamicTarget) {
            return new PendingUpdate(
                    this.key,
                    this.packet,
                    this.rawBytes,
                    this.firstQueuedNanos,
                    this.lastUpdatedNanos,
                    this.supersededCount,
                    this.releaseReason,
                    nextDynamicTarget);
        }
    }

    private static final class PlayerState {
        private final Map<PendingKey, PendingUpdate> pendingUpdates = new LinkedHashMap<>();
        private final Map<PendingKey, CreateGateSoundPolicy.SoundState> soundStates = new LinkedHashMap<>();
        private volatile ServerPlayer player;
        private volatile String channelId = "";
        private long chunkBootstrapDeadlineNanos;
        private CreateGateViewPolicy.ViewState lastDrainViewState;
        private boolean lastDrainChunkBootstrapActive;
        private boolean hasDrainSnapshot;
        private boolean hasRefreshRequired;
        private long earliestFirstQueuedNanos = Long.MAX_VALUE;

        private void bind(ServerPlayer player, String channelId) {
            this.player = player;
            this.channelId = channelId == null ? "" : channelId;
        }

        private ServerPlayer player() {
            return this.player;
        }

        private String channelId() {
            return this.channelId;
        }

        private synchronized void markChunkBootstrap(long nowNanos, long durationNanos) {
            if (durationNanos <= 0L) {
                return;
            }
            this.chunkBootstrapDeadlineNanos = Math.max(this.chunkBootstrapDeadlineNanos, nowNanos + durationNanos);
        }

        private synchronized boolean isChunkBootstrapActive(long nowNanos) {
            return this.chunkBootstrapDeadlineNanos > nowNanos;
        }

        private synchronized boolean rememberSoundStateAndShouldFlush(PendingKey key, CompoundTag tag) {
            if (key == null || !isSoundClassifiedBlockEntity(key.typeKey())) {
                return false;
            }
            if (!this.soundStates.containsKey(key) && this.soundStates.size() >= CreateGateQueueConfig.maxPendingPerPlayer()) {
                Iterator<PendingKey> iterator = this.soundStates.keySet().iterator();
                if (iterator.hasNext()) {
                    iterator.next();
                    iterator.remove();
                }
            }
            CreateGateSoundPolicy.SoundState previous = this.soundStates.get(key);
            CreateGateSoundPolicy.SoundState current = CreateGateSoundPolicy.capture(key.typeKey(), tag);
            this.soundStates.put(key, current);
            return CreateGateSoundPolicy.shouldFlush(key.typeKey(), previous, current);
        }

        private synchronized boolean rememberLatest(
                PendingKey key,
                Packet<?> packet,
                int rawBytes,
                DynamicTarget dynamicTarget
        ) {
            if (key == null || packet == null) {
                return false;
            }
            int maxPending = CreateGateQueueConfig.maxPendingPerPlayer();
            if (!this.pendingUpdates.containsKey(key) && this.pendingUpdates.size() >= maxPending) {
                return false;
            }
            long nowNanos = System.nanoTime();
            PendingUpdate existing = this.pendingUpdates.get(key);
            if (existing == null) {
                this.pendingUpdates.put(
                        key,
                        new PendingUpdate(key, packet, rawBytes, nowNanos, nowNanos, 0, "", dynamicTarget));
                this.earliestFirstQueuedNanos = Math.min(this.earliestFirstQueuedNanos, nowNanos);
            } else {
                this.pendingUpdates.put(key, existing.withLatest(packet, rawBytes, nowNanos, dynamicTarget));
                recordSuperseded(existing);
            }
            this.hasRefreshRequired |= dynamicTarget != null && dynamicTarget.refreshRequired();
            return true;
        }

        private synchronized PendingDropStats forgetPending(PendingKey key) {
            if (key != null) {
                return PendingDropStats.EMPTY.plus(this.pendingUpdates.remove(key));
            }
            return PendingDropStats.EMPTY;
        }

        private synchronized List<PendingUpdate> drainReady(ServerPlayer player, long nowNanos) {
            if (this.pendingUpdates.isEmpty()) {
                return List.of();
            }
            long maxDelayNanos = CreateGateQueueConfig.maxDelayNanos();
            boolean chunkBootstrapActive = isChunkBootstrapActive(nowNanos);
            CreateGateViewPolicy.ViewState viewState = CreateGateViewPolicy.capture(player);
            boolean viewChanged = !this.hasDrainSnapshot || !viewState.equals(this.lastDrainViewState);
            boolean bootstrapChanged = !this.hasDrainSnapshot
                    || chunkBootstrapActive != this.lastDrainChunkBootstrapActive;
            boolean expiryDue = !chunkBootstrapActive
                    && nowNanos - this.earliestFirstQueuedNanos >= maxDelayNanos;
            if (!viewChanged && !bootstrapChanged && !expiryDue && !this.hasRefreshRequired) {
                return List.of();
            }
            List<PendingUpdate> readyUpdates = new ArrayList<>();
            long nextEarliestFirstQueuedNanos = Long.MAX_VALUE;
            boolean nextHasRefreshRequired = false;
            Iterator<Map.Entry<PendingKey, PendingUpdate>> iterator = this.pendingUpdates.entrySet().iterator();
            while (iterator.hasNext()) {
                Map.Entry<PendingKey, PendingUpdate> entry = iterator.next();
                PendingUpdate pendingUpdate = entry.getValue();
                DynamicTarget dynamicTarget = pendingUpdate.dynamicTarget();
                if (dynamicTarget == null || dynamicTarget.refreshRequired()) {
                    dynamicTarget = resolveDynamicTargetOnServerThread(
                            player,
                            pendingUpdate.key().pos(),
                            pendingUpdate.key().typeKey());
                    pendingUpdate = pendingUpdate.withDynamicTarget(dynamicTarget);
                    entry.setValue(pendingUpdate);
                }
                boolean visible = dynamicTarget.forceImmediate()
                        || CreateGateViewPolicy.shouldSendImmediately(
                                viewState,
                                dynamicTarget.points(),
                                allowLookDirectionForGatedUpdate(
                                        pendingUpdate.key().typeKey(),
                                        chunkBootstrapActive));
                boolean expired = !chunkBootstrapActive
                        && nowNanos - pendingUpdate.firstQueuedNanos() >= maxDelayNanos;
                if (!visible && !expired) {
                    nextEarliestFirstQueuedNanos = Math.min(
                            nextEarliestFirstQueuedNanos,
                            pendingUpdate.firstQueuedNanos());
                    nextHasRefreshRequired |= dynamicTarget.refreshRequired();
                    continue;
                }
                iterator.remove();
                readyUpdates.add(pendingUpdate.withReleaseReason(visible ? "visible" : "max_delay"));
            }
            this.lastDrainViewState = viewState;
            this.lastDrainChunkBootstrapActive = chunkBootstrapActive;
            this.hasDrainSnapshot = true;
            this.earliestFirstQueuedNanos = nextEarliestFirstQueuedNanos;
            this.hasRefreshRequired = nextHasRefreshRequired;
            return readyUpdates;
        }

        private synchronized PendingDropStats dropChunk(int chunkX, int chunkZ) {
            if (this.pendingUpdates.isEmpty()) {
                return PendingDropStats.EMPTY;
            }
            PendingDropStats dropped = PendingDropStats.EMPTY;
            Iterator<Map.Entry<PendingKey, PendingUpdate>> iterator = this.pendingUpdates.entrySet().iterator();
            while (iterator.hasNext()) {
                Map.Entry<PendingKey, PendingUpdate> entry = iterator.next();
                PendingKey key = entry.getKey();
                if (key.chunkX() == chunkX && key.chunkZ() == chunkZ) {
                    dropped = dropped.plus(entry.getValue());
                    iterator.remove();
                    this.soundStates.remove(key);
                }
            }
            return dropped;
        }

        private synchronized PendingDropStats clearPending() {
            PendingDropStats dropped = PendingDropStats.EMPTY;
            for (PendingUpdate pendingUpdate : this.pendingUpdates.values()) {
                dropped = dropped.plus(pendingUpdate);
            }
            this.pendingUpdates.clear();
            this.soundStates.clear();
            return dropped;
        }
    }
}
