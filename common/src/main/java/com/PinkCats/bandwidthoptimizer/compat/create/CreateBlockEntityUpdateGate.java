package com.PinkCats.bandwidthoptimizer.compat.create;

import com.PinkCats.bandwidthoptimizer.Bandwidthoptimizer;
import com.PinkCats.bandwidthoptimizer.Config;
import com.PinkCats.bandwidthoptimizer.chunk.PeerState.ChunkPeerStateManager;
import com.PinkCats.bandwidthoptimizer.compat.minecraft.BlockEntityTypeKeyCompat;
import com.PinkCats.bandwidthoptimizer.compat.sable.SableDynamicStructureCompat;
import com.PinkCats.bandwidthoptimizer.compat.valkyrienskies.ValkyrienSkiesDynamicStructureCompat;
import com.PinkCats.bandwidthoptimizer.debug.DebugRuntimeConfig;
import io.netty.channel.Channel;
import io.netty.channel.ChannelHandlerContext;
import net.minecraft.core.BlockPos;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.PacketSendListener;
import net.minecraft.network.protocol.Packet;
import net.minecraft.network.protocol.PacketFlow;
import net.minecraft.network.protocol.game.ClientboundBlockEntityDataPacket;
import net.minecraft.network.protocol.game.ClientboundPlayerPositionPacket;
import net.minecraft.network.protocol.game.ClientboundSetChunkCacheCenterPacket;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.phys.Vec3;

import java.util.ArrayList;
import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;

public final class CreateBlockEntityUpdateGate {

    private static final ConcurrentHashMap<UUID, PlayerState> PLAYER_STATES = new ConcurrentHashMap<>();
    private static final ConcurrentHashMap<String, UUID> CHANNEL_PLAYERS = new ConcurrentHashMap<>();
    private static final java.util.Set<Packet<?>> FORCED_PACKETS =
            Collections.synchronizedSet(Collections.newSetFromMap(new IdentityHashMap<>()));
    private static final AtomicLong DELAYED_COUNT = new AtomicLong();
    private static final AtomicLong SUPERSEDED_COUNT = new AtomicLong();
    private static final AtomicLong RELEASED_COUNT = new AtomicLong();
    private static final AtomicLong DROPPED_COUNT = new AtomicLong();
    private static final AtomicLong DELAYED_BYTES = new AtomicLong();
    private static final AtomicLong SUPERSEDED_SAVED_BYTES = new AtomicLong();
    private static final AtomicLong RELEASED_BYTES = new AtomicLong();
    private static final AtomicLong DROPPED_SAVED_BYTES = new AtomicLong();
    private static final Set<String> MECHANICAL_BLOCK_ENTITY_TYPES = Set.of(
            "simple_kinetic",
            "creative_motor",
            "gearbox",
            "encased_shaft",
            "encased_cogwheel",
            "encased_large_cogwheel",
            "adjustable_chain_gearshift",
            "encased_fan",
            "nozzle",
            "clutch",
            "gearshift",
            "turntable",
            "hand_crank",
            "valve_handle",
            "cuckoo_clock",
            "gantry_shaft",
            "gantry_pinion",
            "chain_conveyor",
            "mechanical_pump",
            "fluid_valve",
            "hose_pulley",
            "spout",
            "belt",
            "mechanical_arm",
            "mechanical_piston",
            "windmill_bearing",
            "mechanical_bearing",
            "clockwork_bearing",
            "rope_pulley",
            "elevator_pulley",
            "chassis",
            "sticker",
            "contraption_controls",
            "mechanical_drill",
            "mechanical_saw",
            "mechanical_harvester",
            "mechanical_roller",
            "portable_storage_interface",
            "portable_fluid_interface",
            "steam_engine",
            "steam_whistle",
            "powered_shaft",
            "flywheel",
            "millstone",
            "crushing_wheel",
            "crushing_wheel_controller",
            "water_wheel",
            "large_water_wheel",
            "mechanical_press",
            "mechanical_mixer",
            "deployer",
            "basin",
            "blaze_burner",
            "mechanical_crafter",
            "sequenced_gearshift",
            "rotation_speed_controller",
            "speedometer",
            "stressometer",
            "cart_assembler",
            "depot",
            "weighted_ejector",
            "flap_display"
    );
    private static final Set<String> SOUND_CLASSIFIED_BLOCK_ENTITY_TYPES = Set.of(
            "cuckoo_clock",
            "deployer",
            "mechanical_arm",
            "mechanical_crafter",
            "mechanical_press",
            "steam_whistle"
    );
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
        PlayerState state = PLAYER_STATES.remove(player.getUUID());
        if (state != null) {
            String channelId = state.channelId();
            if (channelId != null && !channelId.isBlank()) {
                CHANNEL_PLAYERS.remove(channelId, player.getUUID());
            }
            PendingDropStats dropped = state.clearPending();
            if (!dropped.isEmpty()) {
                recordDropped(dropped);
                logDiagnose("[CreateUpdateGate][Clear] player={}, reason={}, dropped={}",
                        player.getGameProfile().getName(),
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
        PendingDropStats dropped = state.dropChunk(chunkPos.x, chunkPos.z);
        if (!dropped.isEmpty()) {
            recordDropped(dropped);
            logDiagnose("[CreateUpdateGate][DropChunk] player={}, chunk=({}, {}), reason={}, dropped={}",
                    player.getGameProfile().getName(),
                    chunkPos.x,
                    chunkPos.z,
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
        ResourceLocation blockEntityTypeKey = BlockEntityTypeKeyCompat.keyOf(blockEntityDataPacket.getType());
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
        DynamicTarget dynamicTarget = resolveDynamicTarget(player, blockEntityDataPacket.getPos());
        if (dynamicTarget.forceImmediate()
                || shouldSendImmediately(player, dynamicTarget.points(), !chunkBootstrapActive)
                || soundCritical && isWithinSoundSendDistance(player, dynamicTarget.target(), blockEntityTypeKey)) {
            recordDropped(state.forgetPending(key));
            return false;
        }

        int originalRawBytes = lengthOf(originalPacketBytes);
        boolean accepted = state.rememberLatest(key, packet, originalRawBytes);
        if (!accepted) {
            return false;
        }
        long delayed = DELAYED_COUNT.incrementAndGet();
        DELAYED_BYTES.addAndGet(originalRawBytes);
        if (shouldLogSample(delayed)) {
            logDiagnose("[CreateUpdateGate][Delay] player={}, type={}, pos={}, delayed={}, superseded={}, released={}, dropped={}",
                    player.getGameProfile().getName(),
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
    public static boolean tryDelayConnectionSend(Channel channel, Packet<?> packet, PacketSendListener listener) {
        if (!isEnabled()
                || channel == null
                || packet == null
                || listener != null
                || isForcedPacket(packet)
                || !(packet instanceof ClientboundBlockEntityDataPacket blockEntityDataPacket)) {
            return false;
        }
        ResourceLocation blockEntityTypeKey = BlockEntityTypeKeyCompat.keyOf(blockEntityDataPacket.getType());
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
        return tryRememberDelayedBlockEntity(player, state, blockEntityDataPacket, blockEntityTypeKey, packet, 0);
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
        state.markChunkBootstrap(System.nanoTime(), chunkBootstrapNanos());
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
        state.markChunkBootstrap(System.nanoTime(), chunkBootstrapNanos());
    }

    // TP commands can enqueue the position packet after Create has already filled the send path.
    public static void markCommandTeleportBootstrap(ServerPlayer player) {
        if (!isEnabled() || player == null) {
            return;
        }
        PlayerState state = PLAYER_STATES.computeIfAbsent(player.getUUID(), ignored -> new PlayerState());
        state.bind(player, currentChannelId(player));
        state.markChunkBootstrap(System.nanoTime(), chunkBootstrapNanos());
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
                    logDiagnose("[CreateUpdateGate][Release] player={}, type={}, pos={}, reason={}, rawBytes={}, superseded={}",
                            player.getGameProfile().getName(),
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
            ResourceLocation blockEntityTypeKey,
            Packet<?> packet,
            int originalRawBytes
    ) {
        long nowNanos = System.nanoTime();
        boolean chunkBootstrapActive = state.isChunkBootstrapActive(nowNanos);
        PendingKey key = PendingKey.of(blockEntityTypeKey, blockEntityDataPacket.getPos());
        boolean soundCritical = state.rememberSoundStateAndShouldFlush(key, blockEntityDataPacket.getTag());
        DynamicTarget dynamicTarget = resolveDynamicTarget(player, blockEntityDataPacket.getPos());
        if (dynamicTarget.forceImmediate()
                || shouldSendImmediately(player, dynamicTarget.points(), !chunkBootstrapActive)
                || soundCritical && isWithinSoundSendDistance(player, dynamicTarget.target(), blockEntityTypeKey)) {
            recordDropped(state.forgetPending(key));
            return false;
        }

        boolean accepted = state.rememberLatest(key, packet, originalRawBytes);
        if (!accepted) {
            return false;
        }
        long delayed = DELAYED_COUNT.incrementAndGet();
        DELAYED_BYTES.addAndGet(originalRawBytes);
        if (shouldLogSample(delayed)) {
            logDiagnose("[CreateUpdateGate][Delay] player={}, type={}, pos={}, delayed={}, superseded={}, released={}, dropped={}",
                    player.getGameProfile().getName(),
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

    private static boolean shouldSendImmediately(ServerPlayer player, Vec3[] points, boolean allowLookDirection) {
        if (player == null || points == null || points.length == 0) {
            return true;
        }
        return isAnyPointInImmediateView(
                player.getEyePosition(),
                player.getLookAngle(),
                points,
                alwaysSendDistanceBlocks(),
                allowLookDirection,
                lookDotThreshold());
    }

    private static boolean isAnyPointInImmediateView(
            Vec3 eyePosition,
            Vec3 lookAngle,
            Vec3[] points,
            double nearDistance,
            boolean allowLookDirection,
            double dotThreshold
    ) {
        if (eyePosition == null || points == null || points.length == 0) {
            return true;
        }
        Vec3 normalizedLook = lookAngle == null ? Vec3.ZERO : lookAngle.normalize();
        double nearDistanceSqr = nearDistance * nearDistance;
        boolean hadPoint = false;
        for (Vec3 point : points) {
            if (point == null) {
                continue;
            }
            hadPoint = true;
            Vec3 offset = point.subtract(eyePosition);
            double distanceSqr = offset.lengthSqr();
            if (distanceSqr <= nearDistanceSqr) {
                return true;
            }
            if (!allowLookDirection) {
                continue;
            }
            double length = Math.sqrt(distanceSqr);
            if (length <= 0.0001D) {
                return true;
            }
            double dot = normalizedLook.dot(offset.scale(1.0D / length));
            if (dot >= dotThreshold) {
                return true;
            }
        }
        return !hadPoint;
    }

    private static boolean isCreateMechanicalBlockEntity(ResourceLocation typeKey) {
        return isCreateBlockEntity(typeKey)
                && MECHANICAL_BLOCK_ENTITY_TYPES.contains(typeKey.getPath());
    }

    // During bootstrap, protect the teleport critical path first.
    private static boolean shouldGateCreateBlockEntity(ResourceLocation typeKey, boolean chunkBootstrapActive) {
        if (!isCreateBlockEntity(typeKey)) {
            return false;
        }
        return chunkBootstrapActive || isCreateMechanicalBlockEntity(typeKey);
    }

    private static boolean isSoundClassifiedBlockEntity(ResourceLocation typeKey) {
        return isCreateBlockEntity(typeKey)
                && SOUND_CLASSIFIED_BLOCK_ENTITY_TYPES.contains(typeKey.getPath());
    }

    private static boolean isCreateBlockEntity(ResourceLocation typeKey) {
        return typeKey != null
                && "create".equals(typeKey.getNamespace());
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

    private static boolean consumeForcedPacket(Packet<?> packet) {
        if (packet == null) {
            return false;
        }
        return FORCED_PACKETS.remove(packet);
    }

    private static boolean isForcedPacket(Packet<?> packet) {
        return packet != null && FORCED_PACKETS.contains(packet);
    }

    private static boolean isEnabled() {
        return Boolean.parseBoolean(System.getProperty(
                Config.RuntimeProperty.Create.CREATE_BLOCK_ENTITY_UPDATE_GATE_ENABLED,
                Boolean.toString(Config.RuntimeProperty.Create.DEFAULT_CREATE_BLOCK_ENTITY_UPDATE_GATE_ENABLED)));
    }

    private static long maxDelayNanos() {
        return TimeUnit.MILLISECONDS.toNanos(readLong(
                Config.RuntimeProperty.Create.CREATE_BLOCK_ENTITY_UPDATE_GATE_MAX_DELAY_MILLIS,
                Config.RuntimeProperty.Create.DEFAULT_CREATE_BLOCK_ENTITY_UPDATE_GATE_MAX_DELAY_MILLIS,
                50L,
                10_000L));
    }

    private static int maxPendingPerPlayer() {
        return (int) readLong(
                Config.RuntimeProperty.Create.CREATE_BLOCK_ENTITY_UPDATE_GATE_MAX_PENDING_PER_PLAYER,
                Config.RuntimeProperty.Create.DEFAULT_CREATE_BLOCK_ENTITY_UPDATE_GATE_MAX_PENDING_PER_PLAYER,
                1L,
                8192L);
    }

    private static long chunkBootstrapNanos() {
        return TimeUnit.MILLISECONDS.toNanos(readLong(
                Config.RuntimeProperty.Create.CREATE_BLOCK_ENTITY_UPDATE_GATE_CHUNK_BOOTSTRAP_MILLIS,
                Config.RuntimeProperty.Create.DEFAULT_CREATE_BLOCK_ENTITY_UPDATE_GATE_CHUNK_BOOTSTRAP_MILLIS,
                0L,
                15_000L));
    }

    private static double alwaysSendDistanceBlocks() {
        return readDouble(
                Config.RuntimeProperty.Create.CREATE_BLOCK_ENTITY_UPDATE_GATE_ALWAYS_SEND_DISTANCE_BLOCKS,
                Config.RuntimeProperty.Create.DEFAULT_CREATE_BLOCK_ENTITY_UPDATE_GATE_ALWAYS_SEND_DISTANCE_BLOCKS,
                0.0D,
                128.0D);
    }

    private static boolean isWithinSoundSendDistance(ServerPlayer player, Vec3 target, ResourceLocation typeKey) {
        if (player == null || target == null || typeKey == null) {
            return true;
        }
        double soundDistance = soundSendDistanceBlocks(typeKey);
        return player.getEyePosition().distanceToSqr(target) <= soundDistance * soundDistance;
    }

    private static DynamicTarget resolveDynamicTarget(ServerPlayer player, BlockPos pos) {
        Vec3 vanillaTarget = centerOf(pos);
        Vec3[] vanillaPoints = cornersOf(pos);
        SableDynamicStructureCompat.DynamicTarget sableTarget =
                SableDynamicStructureCompat.resolveTarget(player, pos, vanillaTarget);
        if (sableTarget.forceImmediate()) {
            return new DynamicTarget(sableTarget.target(), vanillaPoints, true);
        }
        if (sableTarget.transformed()) {
            ResolvedPoints resolvedPoints = resolveSablePoints(player, pos, vanillaPoints);
            return new DynamicTarget(sableTarget.target(), resolvedPoints.points(), resolvedPoints.forceImmediate());
        }
        ValkyrienSkiesDynamicStructureCompat.DynamicTarget valkyrienSkiesTarget =
                ValkyrienSkiesDynamicStructureCompat.resolveTarget(player, pos, vanillaTarget);
        if (valkyrienSkiesTarget.forceImmediate()) {
            return new DynamicTarget(valkyrienSkiesTarget.target(), vanillaPoints, true);
        }
        if (valkyrienSkiesTarget.transformed()) {
            ResolvedPoints resolvedPoints = resolveValkyrienSkiesPoints(player, pos, vanillaPoints);
            return new DynamicTarget(
                    valkyrienSkiesTarget.target(),
                    resolvedPoints.points(),
                    resolvedPoints.forceImmediate());
        }
        return new DynamicTarget(vanillaTarget, vanillaPoints, false);
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

    private static double soundSendDistanceBlocks(ResourceLocation typeKey) {
        if ("steam_whistle".equals(typeKey.getPath())) {
            return 64.0D;
        }
        if ("cuckoo_clock".equals(typeKey.getPath())) {
            return 32.0D;
        }
        return 16.0D;
    }

    private static double lookDotThreshold() {
        return readDouble(
                Config.RuntimeProperty.Create.CREATE_BLOCK_ENTITY_UPDATE_GATE_LOOK_DOT,
                Config.RuntimeProperty.Create.DEFAULT_CREATE_BLOCK_ENTITY_UPDATE_GATE_LOOK_DOT,
                -1.0D,
                1.0D);
    }

    private static long readLong(String propertyName, long defaultValue, long minValue, long maxValue) {
        String rawValue = System.getProperty(propertyName);
        if (rawValue == null || rawValue.isBlank()) {
            return defaultValue;
        }
        try {
            long parsed = Long.parseLong(rawValue.trim());
            return Math.max(minValue, Math.min(maxValue, parsed));
        } catch (NumberFormatException ignored) {
            return defaultValue;
        }
    }

    private static double readDouble(String propertyName, double defaultValue, double minValue, double maxValue) {
        String rawValue = System.getProperty(propertyName);
        if (rawValue == null || rawValue.isBlank()) {
            return defaultValue;
        }
        try {
            double parsed = Double.parseDouble(rawValue.trim());
            return Math.max(minValue, Math.min(maxValue, parsed));
        } catch (NumberFormatException ignored) {
            return defaultValue;
        }
    }

    private static int lengthOf(byte[] bytes) {
        return bytes == null ? 0 : bytes.length;
    }

    private static boolean shouldLogSample(long count) {
        return count <= 10L || count % 1000L == 0L;
    }

    private static void logDiagnose(String message, Object... args) {
        if (DebugRuntimeConfig.isDiagnoseEnabled()) {
            Bandwidthoptimizer.LOGGER.info(message, args);
        }
    }

    private record PendingKey(ResourceLocation typeKey, BlockPos pos, int chunkX, int chunkZ) {
        private static PendingKey of(ResourceLocation typeKey, BlockPos pos) {
            return new PendingKey(typeKey, pos, pos.getX() >> 4, pos.getZ() >> 4);
        }
    }

    private record DynamicTarget(Vec3 target, Vec3[] points, boolean forceImmediate) {}

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

    private record CreateSoundState(
            String type,
            String phase,
            String state,
            int ticks,
            int countDown,
            int pitch,
            boolean running,
            boolean fistBump,
            boolean hasParticle,
            boolean hasParticleItems,
            boolean hasAnimation,
            String heldItem
    ) {
        private static CreateSoundState capture(ResourceLocation typeKey, CompoundTag tag) {
            String type = typeKey == null ? "" : typeKey.getPath();
            CompoundTag safeTag = tag == null ? new CompoundTag() : tag;
            return new CreateSoundState(
                    type,
                    safeTag.getString("Phase"),
                    safeTag.getString("State"),
                    safeTag.getInt("Ticks"),
                    safeTag.getInt("CountDown"),
                    safeTag.getInt("Pitch"),
                    safeTag.getBoolean("Running"),
                    safeTag.getBoolean("Fistbump"),
                    safeTag.contains("Particle"),
                    safeTag.contains("ParticleItems") && !safeTag.getList("ParticleItems", 10).isEmpty(),
                    safeTag.contains("Animation") && !"NONE".equals(safeTag.getString("Animation")),
                    itemFingerprint(safeTag));
        }

        private static String itemFingerprint(CompoundTag tag) {
            if (tag == null || !tag.contains("HeldItem")) {
                return "";
            }
            CompoundTag itemTag = tag.getCompound("HeldItem");
            if (itemTag.isEmpty()) {
                return "";
            }
            return itemTag.getString("id") + "#" + itemTag.getInt("count") + "#" + itemTag.getInt("Count");
        }
    }

    private static boolean shouldFlushSoundState(PendingKey key, CreateSoundState previous, CreateSoundState current) {
        if (key == null || current == null || !isSoundClassifiedBlockEntity(key.typeKey())) {
            return false;
        }
        return switch (current.type()) {
            case "mechanical_press" -> shouldFlushMechanicalPress(previous, current);
            case "deployer" -> shouldFlushDeployer(previous, current);
            case "mechanical_crafter" -> shouldFlushMechanicalCrafter(previous, current);
            case "mechanical_arm" -> shouldFlushMechanicalArm(previous, current);
            case "cuckoo_clock" -> current.hasAnimation();
            case "steam_whistle" -> previous != null && previous.pitch() != current.pitch();
            default -> false;
        };
    }

    private static boolean shouldFlushMechanicalPress(CreateSoundState previous, CreateSoundState current) {
        if (current.hasParticleItems()) {
            return true;
        }
        if (!current.running() || current.ticks() < 120) {
            return false;
        }
        return previous == null || !previous.running() || previous.ticks() < 120 || previous.ticks() > current.ticks();
    }

    private static boolean shouldFlushDeployer(CreateSoundState previous, CreateSoundState current) {
        if (current.hasParticle()) {
            return true;
        }
        if (previous == null) {
            return false;
        }
        if (previous.fistBump() != current.fistBump()) {
            return true;
        }
        return !previous.heldItem().equals(current.heldItem()) && !current.heldItem().isBlank();
    }

    private static boolean shouldFlushMechanicalCrafter(CreateSoundState previous, CreateSoundState current) {
        if (previous == null) {
            return false;
        }
        if ("EXPORTING".equals(previous.phase()) && "WAITING".equals(current.phase())) {
            return true;
        }
        return "CRAFTING".equals(current.phase())
                && current.countDown() <= 1000
                && previous.countDown() > 1000;
    }

    private static boolean shouldFlushMechanicalArm(CreateSoundState previous, CreateSoundState current) {
        return previous != null
                && "SEARCH_OUTPUTS".equals(current.phase())
                && !previous.heldItem().equals(current.heldItem())
                && !current.heldItem().isBlank();
    }

    private record PendingUpdate(
            PendingKey key,
            Packet<?> packet,
            int rawBytes,
            long firstQueuedNanos,
            long lastUpdatedNanos,
            int supersededCount,
            String releaseReason
    ) {
        private PendingUpdate withLatest(Packet<?> nextPacket, int nextRawBytes, long nowNanos) {
            return new PendingUpdate(
                    this.key,
                    nextPacket,
                    nextRawBytes,
                    this.firstQueuedNanos,
                    nowNanos,
                    this.supersededCount + 1,
                    this.releaseReason);
        }

        private PendingUpdate withReleaseReason(String reason) {
            return new PendingUpdate(
                    this.key,
                    this.packet,
                    this.rawBytes,
                    this.firstQueuedNanos,
                    this.lastUpdatedNanos,
                    this.supersededCount,
                    reason);
        }
    }

    private static final class PlayerState {
        private final Map<PendingKey, PendingUpdate> pendingUpdates = new LinkedHashMap<>();
        private final Map<PendingKey, CreateSoundState> soundStates = new LinkedHashMap<>();
        private volatile ServerPlayer player;
        private volatile String channelId = "";
        private long chunkBootstrapDeadlineNanos;

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
            if (!this.soundStates.containsKey(key) && this.soundStates.size() >= maxPendingPerPlayer()) {
                Iterator<PendingKey> iterator = this.soundStates.keySet().iterator();
                if (iterator.hasNext()) {
                    iterator.next();
                    iterator.remove();
                }
            }
            CreateSoundState previous = this.soundStates.get(key);
            CreateSoundState current = CreateSoundState.capture(key.typeKey(), tag);
            this.soundStates.put(key, current);
            return shouldFlushSoundState(key, previous, current);
        }

        private synchronized boolean rememberLatest(PendingKey key, Packet<?> packet, int rawBytes) {
            if (key == null || packet == null) {
                return false;
            }
            int maxPending = maxPendingPerPlayer();
            if (!this.pendingUpdates.containsKey(key) && this.pendingUpdates.size() >= maxPending) {
                return false;
            }
            long nowNanos = System.nanoTime();
            PendingUpdate existing = this.pendingUpdates.get(key);
            if (existing == null) {
                this.pendingUpdates.put(
                        key,
                        new PendingUpdate(key, packet, rawBytes, nowNanos, nowNanos, 0, ""));
            } else {
                this.pendingUpdates.put(key, existing.withLatest(packet, rawBytes, nowNanos));
                recordSuperseded(existing);
            }
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
            long maxDelayNanos = maxDelayNanos();
            boolean chunkBootstrapActive = isChunkBootstrapActive(nowNanos);
            List<PendingUpdate> readyUpdates = new ArrayList<>();
            Iterator<Map.Entry<PendingKey, PendingUpdate>> iterator = this.pendingUpdates.entrySet().iterator();
            while (iterator.hasNext()) {
                Map.Entry<PendingKey, PendingUpdate> entry = iterator.next();
                PendingUpdate pendingUpdate = entry.getValue();
                DynamicTarget dynamicTarget = resolveDynamicTarget(player, pendingUpdate.key().pos());
                boolean visible = dynamicTarget.forceImmediate()
                        || shouldSendImmediately(player, dynamicTarget.points(), !chunkBootstrapActive);
                boolean expired = !chunkBootstrapActive
                        && nowNanos - pendingUpdate.firstQueuedNanos() >= maxDelayNanos;
                if (!visible && !expired) {
                    continue;
                }
                iterator.remove();
                readyUpdates.add(pendingUpdate.withReleaseReason(visible ? "visible" : "max_delay"));
            }
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
