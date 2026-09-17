package com.PinkCats.bandwidthoptimizer.gate.integration.minecraft;

import com.PinkCats.bandwidthoptimizer.debug.DiagnosticLog;
import com.PinkCats.bandwidthoptimizer.debug.DiagnosticToolRegistry;
import com.PinkCats.bandwidthoptimizer.gate.IdleGateServerState;
import com.PinkCats.bandwidthoptimizer.integration.minecraft.EntityMotionPacketCompat;
import com.PinkCats.bandwidthoptimizer.integration.minecraft.ServerPlayerLevelCompat;
import com.PinkCats.bandwidthoptimizer.mixin.minecraft.ClientboundMoveEntityPacketAccessor;
import com.PinkCats.bandwidthoptimizer.util.WeakIdentitySet;
import io.netty.channel.Channel;
import it.unimi.dsi.fastutil.ints.Int2ObjectOpenHashMap;
import net.minecraft.network.protocol.Packet;
import net.minecraft.network.protocol.game.ClientboundMoveEntityPacket;
import net.minecraft.network.protocol.game.ClientboundRemoveEntitiesPacket;
import net.minecraft.network.protocol.game.ClientboundSetEntityMotionPacket;
import net.minecraft.network.protocol.game.ClientboundUpdateAttributesPacket;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.IntPredicate;

/** Coalesces reconstructable updates for entities outside an active player's view. */
public final class ActiveEntityViewGate {

    private static final boolean ENABLED = Boolean.parseBoolean(System.getProperty(
            "bandwidthoptimizer.activeEntityViewGateEnabled", "true"));
    static final byte POSITION = 1;
    static final byte MOTION = 1 << 1;
    static final byte ATTRIBUTES = 1 << 2;
    static final int MAX_PENDING_ENTITIES_PER_PLAYER = 4_096;
    static final int MAX_QUEUE_ENTRIES_PER_PLAYER = MAX_PENDING_ENTITIES_PER_PLAYER * 2;
    private static final int MAX_CHECKS_PER_PLAYER_TICK = 256;
    private static final int MAX_CHECKS_PER_TICK = 2_048;
    static final long MAX_HOLD_TICKS = 100L;
    private static final ConcurrentHashMap<UUID, PlayerState> STATES = new ConcurrentHashMap<>();
    private static final WeakIdentitySet<Packet<?>> FORCED_PACKETS = new WeakIdentitySet<>();
    private static final AtomicLong CAPTURED_PACKETS = new AtomicLong();
    private static final AtomicLong COALESCED_PACKETS = new AtomicLong();
    private static final AtomicLong VISIBLE_RELEASES = new AtomicLong();
    private static final AtomicLong TIMEOUT_RELEASES = new AtomicLong();
    private static final AtomicLong RESTORED_PACKETS = new AtomicLong();
    private static final AtomicLong NEXT_DIAGNOSTIC_MILLIS = new AtomicLong();

    private ActiveEntityViewGate() {}

    public static boolean tryCapture(Channel channel, Packet<?> packet) {
        if (!ENABLED || channel == null || packet == null || FORCED_PACKETS.remove(packet)) {
            return false;
        }
        if (IdleGateServerState.isResumeDirectWindow(channel)) {
            return false;
        }
        ServerPlayer player = IdleGateServerState.resolvePlayer(channel);
        if (player == null || IdleGateServerState.snapshot(player).mode().isIdle()) {
            return false;
        }
        if (packet instanceof ClientboundRemoveEntitiesPacket removal) {
            PlayerState state = STATES.get(player.getUUID());
            if (state != null) {
                state.remove(removal);
            }
            return false;
        }

        int entityId;
        byte mask;
        if (packet instanceof ClientboundMoveEntityPacket movement) {
            entityId = ((ClientboundMoveEntityPacketAccessor) movement).bandwidthoptimizer$getEntityId();
            mask = POSITION;
        } else if (packet instanceof ClientboundSetEntityMotionPacket motion) {
            entityId = EntityMotionPacketCompat.entityId(motion);
            mask = MOTION;
        } else if (packet instanceof ClientboundUpdateAttributesPacket attributes) {
            entityId = attributes.getEntityId();
            mask = ATTRIBUTES;
        } else {
            return false;
        }
        if (player.getId() == entityId || isOwnFishingHook(player, entityId)) {
            return false;
        }
        if (!IdleGateForegroundEntityViewPolicy.shouldDefer(player, entityId)) {
            return false;
        }
        ServerLevel level = ServerPlayerLevelCompat.serverLevel(player);
        if (level == null) {
            return false;
        }
        RememberResult result = STATES.computeIfAbsent(player.getUUID(), ignored -> new PlayerState())
                .remember(player, level, entityId, mask, level.getGameTime());
        if (!result.captured()) {
            return false;
        }
        CAPTURED_PACKETS.incrementAndGet();
        if (result.coalesced()) {
            COALESCED_PACKETS.incrementAndGet();
        }
        return true;
    }

    public static void onServerTick() {
        if (!ENABLED) {
            return;
        }
        int remainingChecks = MAX_CHECKS_PER_TICK;
        for (Map.Entry<UUID, PlayerState> entry : STATES.entrySet()) {
            if (remainingChecks <= 0) {
                break;
            }
            PlayerState state = entry.getValue();
            ServerPlayer player = state.player();
            if (player == null || player.connection == null) {
                STATES.remove(entry.getKey(), state);
                continue;
            }
            if (IdleGateServerState.snapshot(player).mode().isIdle()) {
                continue;
            }
            ServerLevel level = ServerPlayerLevelCompat.serverLevel(player);
            if (level == null) {
                continue;
            }
            if (!state.matchesLevel(level)) {
                STATES.remove(entry.getKey(), state);
                continue;
            }
            DrainResult drained = state.drainReady(
                    level.getGameTime(),
                    Math.min(MAX_CHECKS_PER_PLAYER_TICK, remainingChecks),
                    entityId -> IdleGateForegroundEntityViewPolicy.shouldDefer(player, entityId));
            remainingChecks -= drained.checks();
            for (ReadyEntity readyEntity : drained.ready()) {
                if (readyEntity.timedOut()) {
                    TIMEOUT_RELEASES.incrementAndGet();
                } else {
                    VISIBLE_RELEASES.incrementAndGet();
                }
                RESTORED_PACKETS.addAndGet(restore(player, level, readyEntity.pending()));
            }
            logDiagnosticSnapshot(drained.ready().size());
            if (state.isEmpty()) {
                STATES.remove(entry.getKey(), state);
            }
        }
    }

    public static void discard(ServerPlayer player) {
        if (player != null) {
            STATES.remove(player.getUUID());
        }
    }

    public static Snapshot snapshot() {
        int pendingEntities = 0;
        for (PlayerState state : STATES.values()) {
            pendingEntities += state.pendingCount();
        }
        return new Snapshot(
                CAPTURED_PACKETS.get(),
                COALESCED_PACKETS.get(),
                VISIBLE_RELEASES.get(),
                TIMEOUT_RELEASES.get(),
                RESTORED_PACKETS.get(),
                pendingEntities);
    }

    public static void resetStats() {
        CAPTURED_PACKETS.set(0L);
        COALESCED_PACKETS.set(0L);
        VISIBLE_RELEASES.set(0L);
        TIMEOUT_RELEASES.set(0L);
        RESTORED_PACKETS.set(0L);
        NEXT_DIAGNOSTIC_MILLIS.set(0L);
    }

    private static boolean isOwnFishingHook(ServerPlayer player, int entityId) {
        return player.fishing != null && player.fishing.getId() == entityId;
    }

    private static void logDiagnosticSnapshot(int releasedEntities) {
        if (releasedEntities <= 0
                || !DiagnosticToolRegistry.isEnabled(DiagnosticToolRegistry.Tool.COMPAT_DYNAMIC_GATES)) {
            return;
        }
        long nowMillis = System.currentTimeMillis();
        long nextMillis = NEXT_DIAGNOSTIC_MILLIS.get();
        if (nowMillis < nextMillis
                || !NEXT_DIAGNOSTIC_MILLIS.compareAndSet(nextMillis, nowMillis + 10_000L)) {
            return;
        }
        Snapshot snapshot = snapshot();
        DiagnosticLog.info(
                DiagnosticToolRegistry.Tool.COMPAT_DYNAMIC_GATES,
                "event=active_entity_view_gate captured={} coalesced={} visibleReleases={} timeoutReleases={} restoredPackets={} pending={}",
                snapshot.capturedPackets(),
                snapshot.coalescedPackets(),
                snapshot.visibleReleases(),
                snapshot.timeoutReleases(),
                snapshot.restoredPackets(),
                snapshot.pendingEntities());
    }

    private static int restore(ServerPlayer player, ServerLevel level, PendingEntity pending) {
        Entity entity = level.getEntity(pending.entityId());
        if (entity == null || entity == player) {
            return 0;
        }
        int restoredPackets = 0;
        if ((pending.mask() & POSITION) != 0) {
            sendForced(player, EntityMotionPacketCompat.teleport(entity));
            restoredPackets++;
        }
        if ((pending.mask() & MOTION) != 0) {
            sendForced(player, new ClientboundSetEntityMotionPacket(entity));
            restoredPackets++;
        }
        if ((pending.mask() & ATTRIBUTES) != 0 && entity instanceof LivingEntity livingEntity) {
            sendForced(player, new ClientboundUpdateAttributesPacket(
                    livingEntity.getId(),
                    livingEntity.getAttributes().getSyncableAttributes()));
            restoredPackets++;
        }
        return restoredPackets;
    }

    private static void sendForced(ServerPlayer player, Packet<?> packet) {
        FORCED_PACKETS.add(packet);
        try {
            player.connection.send(packet);
        } catch (RuntimeException | Error exception) {
            FORCED_PACKETS.remove(packet);
            throw exception;
        }
    }

    public record Snapshot(
            long capturedPackets,
            long coalescedPackets,
            long visibleReleases,
            long timeoutReleases,
            long restoredPackets,
            int pendingEntities
    ) {}

    record PendingEntity(int entityId, byte mask, long firstGameTime, long generation) {
        private PendingEntity merge(byte additionalMask) {
            return new PendingEntity(
                    this.entityId,
                    (byte) (this.mask | additionalMask),
                    this.firstGameTime,
                    this.generation);
        }
    }

    record ReadyEntity(PendingEntity pending, boolean timedOut) {}

    record DrainResult(List<ReadyEntity> ready, int checks) {}

    private record QueueEntry(int entityId, long generation) {}

    record RememberResult(boolean captured, boolean coalesced) {
        private static final RememberResult REJECTED = new RememberResult(false, false);
        private static final RememberResult ADMITTED = new RememberResult(true, false);
        private static final RememberResult COALESCED = new RememberResult(true, true);
    }

    static final class PlayerState {
        private final Int2ObjectOpenHashMap<PendingEntity> pending = new Int2ObjectOpenHashMap<>();
        private final ArrayDeque<QueueEntry> queue = new ArrayDeque<>();
        private volatile ServerPlayer player;
        private volatile Object levelScope;
        private long nextGeneration;

        synchronized RememberResult remember(
                ServerPlayer player,
                ServerLevel level,
                int entityId,
                byte mask,
                long gameTime
        ) {
            this.player = player;
            enterScope(level);
            return remember(entityId, mask, gameTime);
        }

        synchronized void enterScope(Object levelScope) {
            if (this.levelScope == levelScope) {
                return;
            }
            this.levelScope = levelScope;
            this.pending.clear();
            this.queue.clear();
        }

        synchronized RememberResult remember(int entityId, byte mask, long gameTime) {
            PendingEntity current = this.pending.get(entityId);
            if (current != null) {
                this.pending.put(entityId, current.merge(mask));
                return RememberResult.COALESCED;
            }
            if (this.pending.size() >= MAX_PENDING_ENTITIES_PER_PLAYER) {
                return RememberResult.REJECTED;
            }
            if (this.queue.size() >= MAX_QUEUE_ENTRIES_PER_PLAYER) {
                compactQueue();
            }
            long generation = ++this.nextGeneration;
            this.pending.put(entityId, new PendingEntity(entityId, mask, gameTime, generation));
            this.queue.addLast(new QueueEntry(entityId, generation));
            return RememberResult.ADMITTED;
        }

        private ServerPlayer player() {
            return this.player;
        }

        private boolean matchesLevel(ServerLevel level) {
            return this.levelScope == level;
        }

        private synchronized void remove(ClientboundRemoveEntitiesPacket packet) {
            for (int entityId : packet.getEntityIds()) {
                this.pending.remove(entityId);
            }
        }

        synchronized void remove(int entityId) {
            this.pending.remove(entityId);
        }

        synchronized DrainResult drainReady(long gameTime, int maxChecks, IntPredicate shouldDefer) {
            int checks = Math.min(Math.max(maxChecks, 0), this.queue.size());
            if (checks == 0) {
                return new DrainResult(List.of(), 0);
            }
            int performedChecks = checks;
            List<ReadyEntity> ready = new ArrayList<>();
            while (checks-- > 0) {
                QueueEntry queued = this.queue.removeFirst();
                PendingEntity current = this.pending.get(queued.entityId());
                if (current == null || current.generation() != queued.generation()) {
                    continue;
                }
                boolean expired = gameTime - current.firstGameTime() >= MAX_HOLD_TICKS;
                if (expired || !shouldDefer.test(queued.entityId())) {
                    this.pending.remove(queued.entityId());
                    ready.add(new ReadyEntity(current, expired));
                } else {
                    this.queue.addLast(queued);
                }
            }
            return new DrainResult(ready, performedChecks);
        }

        private synchronized boolean isEmpty() {
            return this.pending.isEmpty();
        }

        synchronized int pendingCount() {
            return this.pending.size();
        }

        synchronized int queueSize() {
            return this.queue.size();
        }

        private void compactQueue() {
            this.queue.removeIf(queued -> {
                PendingEntity current = this.pending.get(queued.entityId());
                return current == null || current.generation() != queued.generation();
            });
        }
    }
}
