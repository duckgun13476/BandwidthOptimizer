package com.PinkCats.bandwidthoptimizer.gate.recovery;

import com.PinkCats.bandwidthoptimizer.debug.DiagnosticLog;
import com.PinkCats.bandwidthoptimizer.debug.DiagnosticToolRegistry;
import com.PinkCats.bandwidthoptimizer.gate.IdleGateServerState;
import com.PinkCats.bandwidthoptimizer.integration.minecraft.EntityMotionPacketCompat;
import com.PinkCats.bandwidthoptimizer.integration.minecraft.ServerPlayerLevelCompat;
import com.PinkCats.bandwidthoptimizer.mixin.minecraft.ClientboundMoveEntityPacketAccessor;
import io.netty.channel.Channel;
import it.unimi.dsi.fastutil.ints.Int2ByteOpenHashMap;
import it.unimi.dsi.fastutil.ints.Int2ByteMap;
import net.minecraft.network.protocol.game.ClientboundMoveEntityPacket;
import net.minecraft.network.protocol.game.ClientboundRemoveEntitiesPacket;
import net.minecraft.network.protocol.game.ClientboundSetEntityMotionPacket;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.Entity;

import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;

/** Rebuilds current entity motion and absolute position after background idle. */
public final class EntityMotionRecoveryPolicy extends IdleGateRecoveryPolicy {

    private static final byte POSITION = 1;
    private static final byte MOTION = 1 << 1;
    private static final int MAX_PENDING_ENTITIES_PER_PLAYER = 4_096;

    private final ConcurrentHashMap<UUID, PlayerState> states = new ConcurrentHashMap<>();
    private final AtomicLong capturedMovementPackets = new AtomicLong();
    private final AtomicLong capturedMotionPackets = new AtomicLong();
    private final AtomicLong restoredTeleports = new AtomicLong();
    private final AtomicLong restoredMotionPackets = new AtomicLong();
    private final AtomicLong skippedEntities = new AtomicLong();

    public boolean tryCaptureMovement(Channel channel, ClientboundMoveEntityPacket packet) {
        if (packet == null) {
            return false;
        }
        int entityId = ((ClientboundMoveEntityPacketAccessor) packet).bandwidthoptimizer$getEntityId();
        boolean captured = capture(channel, entityId, POSITION);
        if (captured) {
            capturedMovementPackets.incrementAndGet();
        }
        return captured;
    }

    public boolean tryCaptureMotion(Channel channel, ClientboundSetEntityMotionPacket packet) {
        if (packet == null) {
            return false;
        }
        boolean captured = capture(channel, EntityMotionPacketCompat.entityId(packet), MOTION);
        if (captured) {
            capturedMotionPackets.incrementAndGet();
        }
        return captured;
    }

    public void observeRemoval(Channel channel, ClientboundRemoveEntitiesPacket packet) {
        if (channel == null || packet == null) {
            return;
        }
        ServerPlayer player = IdleGateServerState.resolvePlayer(channel);
        PlayerState state = player == null ? null : states.get(player.getUUID());
        if (state != null) {
            state.remove(packet.getEntityIds());
        }
    }

    @Override
    public void restore(ServerPlayer player) {
        if (player != null) {
            restore(states.remove(player.getUUID()));
        }
    }

    @Override
    public void onServerTick() {
        for (Map.Entry<UUID, PlayerState> entry : states.entrySet()) {
            PlayerState state = entry.getValue();
            ServerPlayer player = state.player();
            if (player == null
                    || IdleGateServerState.snapshot(player).mode().suppressesWorldPresentation()
                    || !states.remove(entry.getKey(), state)) {
                continue;
            }
            restore(state);
        }
    }

    @Override
    public void discard(ServerPlayer player) {
        if (player != null) {
            states.remove(player.getUUID());
        }
    }

    private boolean capture(Channel channel, int entityId, byte updateMask) {
        if (channel == null) {
            return false;
        }
        ServerPlayer player = IdleGateServerState.resolvePlayer(channel);
        if (player == null || player.getId() == entityId) {
            return false;
        }
        PlayerState state = states.computeIfAbsent(player.getUUID(), ignored -> new PlayerState());
        return state.bind(player).remember(entityId, updateMask);
    }

    private void restore(PlayerState state) {
        if (state == null || state.player() == null) {
            return;
        }
        ServerPlayer player = state.player();
        ServerLevel level = ServerPlayerLevelCompat.serverLevel(player);
        if (level == null) {
            return;
        }

        Int2ByteOpenHashMap entities = state.drain();
        long teleports = 0L;
        long motions = 0L;
        long skipped = 0L;
        for (Int2ByteMap.Entry entry : entities.int2ByteEntrySet()) {
            Entity entity = level.getEntity(entry.getIntKey());
            if (entity == null || entity == player) {
                skipped++;
                continue;
            }
            byte updateMask = entry.getByteValue();
            if ((updateMask & POSITION) != 0) {
                player.connection.send(EntityMotionPacketCompat.teleport(entity));
                teleports++;
            }
            if ((updateMask & MOTION) != 0) {
                player.connection.send(new ClientboundSetEntityMotionPacket(entity));
                motions++;
            }
        }
        if (teleports > 0L || motions > 0L || skipped > 0L) {
            restoredTeleports.addAndGet(teleports);
            restoredMotionPackets.addAndGet(motions);
            skippedEntities.addAndGet(skipped);
            DiagnosticLog.info(
                    DiagnosticToolRegistry.Tool.COMPAT_DYNAMIC_GATES,
                    "event=idle_gate_entity_restore player={} teleports={} motions={} skipped={} capturedMoves={} capturedMotions={} totalTeleports={} totalMotions={} totalSkipped={}",
                    player.getName().getString(),
                    teleports,
                    motions,
                    skipped,
                    capturedMovementPackets.get(),
                    capturedMotionPackets.get(),
                    restoredTeleports.get(),
                    restoredMotionPackets.get(),
                    skippedEntities.get());
        }
    }

    private static final class PlayerState {
        private final Int2ByteOpenHashMap entities = new Int2ByteOpenHashMap();
        private volatile ServerPlayer player;

        private PlayerState bind(ServerPlayer player) {
            this.player = player;
            return this;
        }

        private ServerPlayer player() {
            return player;
        }

        private synchronized boolean remember(int entityId, byte updateMask) {
            byte current = entities.getOrDefault(entityId, (byte) 0);
            if (current == 0 && entities.size() >= MAX_PENDING_ENTITIES_PER_PLAYER) {
                return false;
            }
            entities.put(entityId, (byte) (current | updateMask));
            return true;
        }

        private synchronized void remove(it.unimi.dsi.fastutil.ints.IntList entityIds) {
            for (int entityId : entityIds) {
                entities.remove(entityId);
            }
        }

        private synchronized Int2ByteOpenHashMap drain() {
            Int2ByteOpenHashMap drained = new Int2ByteOpenHashMap(entities);
            entities.clear();
            return drained;
        }
    }
}
