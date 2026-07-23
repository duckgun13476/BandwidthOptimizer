package com.PinkCats.bandwidthoptimizer.gate.recovery;

import com.PinkCats.bandwidthoptimizer.debug.DiagnosticLog;
import com.PinkCats.bandwidthoptimizer.debug.DiagnosticToolRegistry;
import com.PinkCats.bandwidthoptimizer.gate.IdleGateServerState;
import com.PinkCats.bandwidthoptimizer.integration.minecraft.ServerPlayerLevelCompat;
import io.netty.channel.Channel;
import it.unimi.dsi.fastutil.ints.IntArrayList;
import it.unimi.dsi.fastutil.ints.IntIterator;
import it.unimi.dsi.fastutil.ints.IntOpenHashSet;
import net.minecraft.network.protocol.game.ClientboundUpdateAttributesPacket;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.LivingEntity;

import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;

/** Rebuilds complete living-entity attribute snapshots after background idle. */
public final class AttributeRecoveryPolicy extends IdleGateRecoveryPolicy {

    private final ConcurrentHashMap<UUID, PlayerState> states = new ConcurrentHashMap<>();
    private final AtomicLong capturedPackets = new AtomicLong();
    private final AtomicLong restoredEntities = new AtomicLong();
    private final AtomicLong skippedEntities = new AtomicLong();

    public boolean tryCapture(Channel channel, ClientboundUpdateAttributesPacket packet) {
        if (channel == null || packet == null) {
            return false;
        }
        ServerPlayer player = IdleGateServerState.resolvePlayer(channel);
        if (player == null) {
            return false;
        }
        PlayerState state = states.computeIfAbsent(player.getUUID(), ignored -> new PlayerState());
        state.bind(player).remember(packet.getEntityId());
        capturedPackets.incrementAndGet();
        return true;
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

    private void restore(PlayerState state) {
        if (state == null || state.player() == null) {
            return;
        }
        ServerPlayer player = state.player();
        ServerLevel level = ServerPlayerLevelCompat.serverLevel(player);
        if (level == null) {
            return;
        }
        IntArrayList entityIds = state.drain();
        long restored = 0L;
        long skipped = 0L;
        for (IntIterator iterator = entityIds.iterator(); iterator.hasNext();) {
            net.minecraft.world.entity.Entity entity = level.getEntity(iterator.nextInt());
            if (!(entity instanceof LivingEntity livingEntity)) {
                skipped++;
                continue;
            }
            player.connection.send(new ClientboundUpdateAttributesPacket(
                    livingEntity.getId(),
                    livingEntity.getAttributes().getSyncableAttributes()));
            restored++;
        }
        if (restored > 0L || skipped > 0L) {
            restoredEntities.addAndGet(restored);
            skippedEntities.addAndGet(skipped);
            DiagnosticLog.info(
                    DiagnosticToolRegistry.Tool.COMPAT_DYNAMIC_GATES,
                    "event=idle_gate_attribute_restore player={} restored={} skipped={} capturedPackets={} totalRestored={} totalSkipped={}",
                    player.getName().getString(),
                    restored,
                    skipped,
                    capturedPackets.get(),
                    restoredEntities.get(),
                    skippedEntities.get());
        }
    }

    private static final class PlayerState {
        private final IntOpenHashSet entityIds = new IntOpenHashSet();
        private volatile ServerPlayer player;

        private PlayerState bind(ServerPlayer player) {
            this.player = player;
            return this;
        }

        private ServerPlayer player() {
            return player;
        }

        private synchronized void remember(int entityId) {
            entityIds.add(entityId);
        }

        private synchronized IntArrayList drain() {
            IntArrayList drained = new IntArrayList(entityIds);
            entityIds.clear();
            return drained;
        }
    }
}
