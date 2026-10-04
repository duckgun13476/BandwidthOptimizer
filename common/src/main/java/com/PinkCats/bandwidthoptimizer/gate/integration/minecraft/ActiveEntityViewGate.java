package com.PinkCats.bandwidthoptimizer.gate.integration.minecraft;

import com.PinkCats.bandwidthoptimizer.gate.IdleGateServerState;
import com.PinkCats.bandwidthoptimizer.integration.minecraft.EntityMotionPacketCompat;
import com.PinkCats.bandwidthoptimizer.integration.minecraft.ServerPlayerLevelCompat;
import com.PinkCats.bandwidthoptimizer.integration.sable.SableEntityGateCompat;
import com.PinkCats.bandwidthoptimizer.mixin.minecraft.ClientboundMoveEntityPacketAccessor;
import com.PinkCats.bandwidthoptimizer.util.WeakIdentitySet;
import io.netty.channel.Channel;
import it.unimi.dsi.fastutil.ints.Int2ObjectOpenHashMap;
import net.minecraft.network.protocol.Packet;
import net.minecraft.network.protocol.game.ClientboundMoveEntityPacket;
import net.minecraft.network.protocol.game.ClientboundRemoveEntitiesPacket;
import net.minecraft.network.protocol.game.ClientboundTeleportEntityPacket;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Combines adjacent active-play movement updates without changing the client's
 * authoritative position. Vanilla interpolates each delivered endpoint.
 */
public final class ActiveEntityViewGate {
    private static final boolean ENABLED = Boolean.parseBoolean(System.getProperty(
            "bandwidthoptimizer.activeEntityInterpolationEnabled",
            System.getProperty("bandwidthoptimizer.activeEntityViewGateEnabled", "true")));
    static final int MAX_PENDING_ENTITIES_PER_PLAYER = 128;
    static final int MAX_PENDING_ENTITIES_TOTAL = 2_048;
    private static final ConcurrentHashMap<UUID, PlayerState> STATES = new ConcurrentHashMap<>();
    private static final WeakIdentitySet<Packet<?>> FORCED_PACKETS = new WeakIdentitySet<>();
    private static final AtomicInteger PENDING_TOTAL = new AtomicInteger();
    private static final AtomicLong OBSERVED_PACKETS = new AtomicLong();
    private static final AtomicLong OBSERVED_PAYLOAD_BYTES = new AtomicLong();
    private static final AtomicLong CAPTURED_PACKETS = new AtomicLong();
    private static final AtomicLong COALESCED_PACKETS = new AtomicLong();
    private static final AtomicLong COALESCED_SAVED_PAYLOAD_BYTES = new AtomicLong();
    private static final AtomicLong TIMEOUT_RELEASES = new AtomicLong();
    private static final AtomicLong OVERFLOW_RELEASES = new AtomicLong();
    private static final AtomicLong SUPERSEDED_PACKETS = new AtomicLong();

    private ActiveEntityViewGate() {}

    public static boolean tryCapture(Channel channel, Packet<?> packet) {
        if (!ENABLED || channel == null || packet == null || FORCED_PACKETS.remove(packet)) {
            return false;
        }
        ServerPlayer player = IdleGateServerState.resolvePlayer(channel);
        if (player == null) {
            return false;
        }
        PlayerState existing = STATES.get(player.getUUID());
        if (packet instanceof ClientboundRemoveEntitiesPacket removal) {
            if (existing != null) {
                for (int id : removal.getEntityIds()) {
                    if (existing.remove(id)) {
                        SUPERSEDED_PACKETS.incrementAndGet();
                    }
                }
            }
            return false;
        }
        if (packet instanceof ClientboundTeleportEntityPacket teleport) {
            if (existing != null && existing.remove(EntityMotionPacketCompat.entityId(teleport))) {
                SUPERSEDED_PACKETS.incrementAndGet();
            }
            return false;
        }
        if (!(packet instanceof ClientboundMoveEntityPacket movement)
                || IdleGateServerState.isResumeDirectWindow(channel)
                || IdleGateServerState.snapshot(player).mode().isIdle()) {
            return false;
        }
        int entityId = ((ClientboundMoveEntityPacketAccessor) movement).bandwidthoptimizer$getEntityId();
        if (player.getId() == entityId || player.fishing != null && player.fishing.getId() == entityId) {
            return false;
        }
        ServerLevel level = ServerPlayerLevelCompat.serverLevel(player);
        if (level == null || level.getServer() == null || !level.getServer().isSameThread()) {
            return false;
        }
        if (SableEntityGateCompat.shouldPassThrough(player, entityId)) {
            // Release the original held packet before the new packet; never combine Sable metadata.
            if (existing != null && !existing.matchesLevel(level)) {
                existing.clear();
            }
            ClientboundMoveEntityPacket held = existing == null ? null : existing.take(entityId);
            if (held != null) {
                sendForced(player, held);
            }
            return false;
        }
        long gameTime = level.getGameTime();
        PlayerState state = STATES.computeIfAbsent(player.getUUID(), ignored -> new PlayerState());
        state.bind(player, level);
        OBSERVED_PACKETS.incrementAndGet();
        OBSERVED_PAYLOAD_BYTES.addAndGet(payloadBytes(entityId, movement));
        CaptureResult result = state.accept(entityId, movement, gameTime);
        if (result.captured()) {
            CAPTURED_PACKETS.incrementAndGet();
            return true;
        }
        if (result.overflow() != null) {
            OVERFLOW_RELEASES.incrementAndGet();
            sendForced(player, result.overflow());
            return false;
        }
        if (result.replacement() != null) {
            COALESCED_PACKETS.incrementAndGet();
            COALESCED_SAVED_PAYLOAD_BYTES.addAndGet(
                    payloadBytes(entityId, result.held())
                            + payloadBytes(entityId, movement)
                            - payloadBytes(entityId, result.replacement()));
            sendForced(player, result.replacement());
            return true;
        }
        return false;
    }

    public static void onServerTick() {
        if (!ENABLED) {
            return;
        }
        for (Map.Entry<UUID, PlayerState> entry : STATES.entrySet()) {
            PlayerState state = entry.getValue();
            ServerPlayer player = state.player();
            if (player == null || player.connection == null) {
                if (STATES.remove(entry.getKey(), state)) {
                    state.clear();
                }
                continue;
            }
            ServerLevel level = ServerPlayerLevelCompat.serverLevel(player);
            if (level == null || !state.matchesLevel(level)) {
                if (STATES.remove(entry.getKey(), state)) {
                    state.clear();
                }
                continue;
            }
            List<ClientboundMoveEntityPacket> expired = state.drainExpired(level.getGameTime());
            for (ClientboundMoveEntityPacket movement : expired) {
                TIMEOUT_RELEASES.incrementAndGet();
                sendForced(player, movement);
            }
            if (state.isEmpty()) {
                STATES.remove(entry.getKey(), state);
            }
        }
    }

    public static void discard(ServerPlayer player) {
        if (player != null) {
            PlayerState state = STATES.remove(player.getUUID());
            if (state != null) {
                state.clear();
            }
        }
    }

    public static Snapshot snapshot() {
        return new Snapshot(
                CAPTURED_PACKETS.get(),
                COALESCED_PACKETS.get(),
                0L,
                TIMEOUT_RELEASES.get(),
                TIMEOUT_RELEASES.get() + OVERFLOW_RELEASES.get(),
                PENDING_TOTAL.get(),
                OBSERVED_PACKETS.get(),
                OBSERVED_PAYLOAD_BYTES.get(),
                COALESCED_SAVED_PAYLOAD_BYTES.get(),
                OVERFLOW_RELEASES.get(),
                SUPERSEDED_PACKETS.get());
    }

    public static void resetStats() {
        OBSERVED_PACKETS.set(0L);
        OBSERVED_PAYLOAD_BYTES.set(0L);
        CAPTURED_PACKETS.set(0L);
        COALESCED_PACKETS.set(0L);
        COALESCED_SAVED_PAYLOAD_BYTES.set(0L);
        TIMEOUT_RELEASES.set(0L);
        OVERFLOW_RELEASES.set(0L);
        SUPERSEDED_PACKETS.set(0L);
    }

    static ClientboundMoveEntityPacket combine(int entityId, ClientboundMoveEntityPacket first,
                                                 ClientboundMoveEntityPacket second) {
        ClientboundMoveEntityPacket latestRotation = second.hasRotation() ? second : first;
        byte yaw = 0;
        byte pitch = 0;
        if (latestRotation.hasRotation()) {
            ClientboundMoveEntityPacketAccessor accessor = (ClientboundMoveEntityPacketAccessor) latestRotation;
            yaw = accessor.bandwidthoptimizer$getYRotByte();
            pitch = accessor.bandwidthoptimizer$getXRotByte();
        }
        return combine(entityId, first, second, yaw, pitch);
    }

    static ClientboundMoveEntityPacket combine(int entityId, ClientboundMoveEntityPacket first,
                                                 ClientboundMoveEntityPacket second, byte yaw, byte pitch) {
        int x = first.getXa() + second.getXa();
        int y = first.getYa() + second.getYa();
        int z = first.getZa() + second.getZa();
        if (x < Short.MIN_VALUE || x > Short.MAX_VALUE
                || y < Short.MIN_VALUE || y > Short.MAX_VALUE
                || z < Short.MIN_VALUE || z > Short.MAX_VALUE) {
            return null;
        }
        boolean position = first.hasPosition() || second.hasPosition();
        boolean rotation = first.hasRotation() || second.hasRotation();
        if (position && rotation) {
            return new ClientboundMoveEntityPacket.PosRot(
                    entityId, (short) x, (short) y, (short) z, yaw, pitch, second.isOnGround());
        }
        if (position) {
            return new ClientboundMoveEntityPacket.Pos(
                    entityId, (short) x, (short) y, (short) z, second.isOnGround());
        }
        return new ClientboundMoveEntityPacket.Rot(entityId, yaw, pitch, second.isOnGround());
    }

    static int payloadBytes(int entityId, ClientboundMoveEntityPacket movement) {
        int varint = 1;
        for (int value = entityId; (value & ~0x7f) != 0; value >>>= 7) {
            varint++;
        }
        return varint + (movement.hasPosition() ? 6 : 0)
                + (movement.hasRotation() ? 2 : 0) + 1;
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

    private static boolean reserveSlot() {
        int count;
        do {
            count = PENDING_TOTAL.get();
            if (count >= MAX_PENDING_ENTITIES_TOTAL) {
                return false;
            }
        } while (!PENDING_TOTAL.compareAndSet(count, count + 1));
        return true;
    }

    public record Snapshot(
            long capturedPackets, long coalescedPackets, long visibleReleases,
            long timeoutReleases, long restoredPackets, int pendingEntities,
            long observedPackets, long observedPayloadBytes, long coalescedSavedPayloadBytes,
            long overflowReleases, long supersededPackets
    ) {}

    record PendingMove(ClientboundMoveEntityPacket packet, long gameTime) {}
    record CaptureResult(boolean captured, ClientboundMoveEntityPacket held,
                         ClientboundMoveEntityPacket replacement, ClientboundMoveEntityPacket overflow) {
        static final CaptureResult PASS = new CaptureResult(false, null, null, null);
        static final CaptureResult CAPTURED = new CaptureResult(true, null, null, null);
    }

    static final class PlayerState {
        private final Int2ObjectOpenHashMap<PendingMove> pending = new Int2ObjectOpenHashMap<>();
        private volatile ServerPlayer player;
        private volatile ServerLevel level;

        synchronized void bind(ServerPlayer player, ServerLevel level) {
            if (this.level != level) {
                clear();
                this.level = level;
            }
            this.player = player;
        }

        synchronized CaptureResult accept(int entityId, ClientboundMoveEntityPacket movement, long gameTime) {
            PendingMove first = pending.remove(entityId);
            if (first != null) {
                PENDING_TOTAL.decrementAndGet();
                ClientboundMoveEntityPacket combined = combine(entityId, first.packet(), movement);
                return combined == null
                        ? new CaptureResult(false, null, null, first.packet())
                        : new CaptureResult(false, first.packet(), combined, null);
            }
            if ((gameTime & 1L) == 0L
                    || pending.size() >= MAX_PENDING_ENTITIES_PER_PLAYER
                    || !reserveSlot()) {
                return CaptureResult.PASS;
            }
            pending.put(entityId, new PendingMove(movement, gameTime));
            return CaptureResult.CAPTURED;
        }

        synchronized boolean remove(int entityId) {
            if (pending.remove(entityId) == null) {
                return false;
            }
            PENDING_TOTAL.decrementAndGet();
            return true;
        }

        synchronized ClientboundMoveEntityPacket take(int entityId) {
            PendingMove move = pending.remove(entityId);
            if (move == null) {
                return null;
            }
            PENDING_TOTAL.decrementAndGet();
            return move.packet();
        }

        synchronized List<ClientboundMoveEntityPacket> drainExpired(long gameTime) {
            if (pending.isEmpty()) {
                return List.of();
            }
            List<ClientboundMoveEntityPacket> expired = new ArrayList<>();
            var iterator = pending.int2ObjectEntrySet().fastIterator();
            while (iterator.hasNext()) {
                var entry = iterator.next();
                if (gameTime > entry.getValue().gameTime()) {
                    expired.add(entry.getValue().packet());
                    iterator.remove();
                    PENDING_TOTAL.decrementAndGet();
                }
            }
            return expired;
        }

        synchronized void clear() {
            PENDING_TOTAL.addAndGet(-pending.size());
            pending.clear();
        }

        private ServerPlayer player() {
            return player;
        }

        private boolean matchesLevel(ServerLevel level) {
            return this.level == level;
        }

        private synchronized boolean isEmpty() {
            return pending.isEmpty();
        }
    }
}
