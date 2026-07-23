package com.PinkCats.bandwidthoptimizer.gate.recovery;

import com.PinkCats.bandwidthoptimizer.debug.DiagnosticLog;
import com.PinkCats.bandwidthoptimizer.debug.DiagnosticToolRegistry;
import com.PinkCats.bandwidthoptimizer.gate.IdleGateServerState;
import io.netty.channel.Channel;
import net.minecraft.core.BlockPos;
import net.minecraft.network.protocol.Packet;
import net.minecraft.network.protocol.game.ClientboundBlockEntityDataPacket;
import net.minecraft.server.level.ServerPlayer;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Holds the latest recoverable block-entity update per player and position.
 */
public abstract class BlockEntityRecoveryPolicy extends IdleGateRecoveryPolicy {

    private static final long DIAGNOSTIC_INTERVAL_NANOS = TimeUnit.SECONDS.toNanos(5L);
    private final ConcurrentHashMap<UUID, PlayerState> states = new ConcurrentHashMap<>();
    private final AtomicLong capturedCount = new AtomicLong();
    private final AtomicLong supersededCount = new AtomicLong();
    private final AtomicLong queuePassThroughCount = new AtomicLong();
    private final AtomicLong restoredCount = new AtomicLong();
    private final AtomicLong lastDiagnosticNanos = new AtomicLong();

    public final boolean tryCaptureBackground(
            Channel channel,
            ClientboundBlockEntityDataPacket blockEntityPacket,
            String typeKey
    ) {
        if (channel == null || blockEntityPacket == null || typeKey == null) {
            return false;
        }
        ServerPlayer player = resolvePlayer(channel);
        if (player == null) {
            return false;
        }
        CaptureResult result = states.computeIfAbsent(player.getUUID(), ignored -> new PlayerState())
                .remember(player, typeKey, blockEntityPacket.getPos(), blockEntityPacket, maxPendingPerPlayer());
        if (!result.captured()) {
            long passThrough = queuePassThroughCount.incrementAndGet();
            if (passThrough == 1L || passThrough % 100L == 0L) {
                logSnapshot("queue_pass_through");
            }
            return false;
        }
        long captured = capturedCount.incrementAndGet();
        if (result.superseded()) {
            supersededCount.incrementAndGet();
        }
        if (captured == 1L || captured % 1000L == 0L) {
            logSnapshot("capture");
        }
        return true;
    }

    @Override
    public final void restore(ServerPlayer player) {
        if (player == null) {
            return;
        }
        restore(states.remove(player.getUUID()));
    }

    @Override
    public final void onServerTick() {
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
    public final void discard(ServerPlayer player) {
        if (player != null) {
            states.remove(player.getUUID());
        }
    }

    protected abstract ServerPlayer resolvePlayer(Channel channel);

    protected abstract int maxPendingPerPlayer();

    protected abstract void sendRecoveryPacket(ServerPlayer player, Packet<?> packet);

    private void restore(PlayerState state) {
        if (state == null) {
            return;
        }
        ServerPlayer player = state.player();
        if (player == null) {
            return;
        }
        java.util.List<Packet<?>> packets = state.drain();
        for (Packet<?> packet : packets) {
            sendRecoveryPacket(player, packet);
        }
        if (!packets.isEmpty()) {
            restoredCount.addAndGet(packets.size());
            logSnapshot("restore");
        }
    }

    private void logSnapshot(String event) {
        if (!DiagnosticToolRegistry.isEnabled(DiagnosticToolRegistry.Tool.COMPAT_DYNAMIC_GATES)) {
            return;
        }
        long nowNanos = System.nanoTime();
        long previousNanos = lastDiagnosticNanos.get();
        if (!"restore".equals(event)
                && previousNanos != 0L
                && nowNanos - previousNanos < DIAGNOSTIC_INTERVAL_NANOS) {
            return;
        }
        if (!lastDiagnosticNanos.compareAndSet(previousNanos, nowNanos)) {
            return;
        }
        DiagnosticLog.info(
                DiagnosticToolRegistry.Tool.COMPAT_DYNAMIC_GATES,
                "event=idle_gate_block_entity policy={} phase={} captured={} superseded={} restored={} queuePassThrough={}",
                getClass().getSimpleName(),
                event,
                capturedCount.get(),
                supersededCount.get(),
                restoredCount.get(),
                queuePassThroughCount.get()
        );
    }

    private record PendingKey(String typeKey, BlockPos pos) {}

    private static final class PlayerState {
        private final Map<PendingKey, Packet<?>> latestPackets = new LinkedHashMap<>();
        private volatile ServerPlayer player;

        private synchronized CaptureResult remember(
                ServerPlayer player,
                String typeKey,
                BlockPos pos,
                Packet<?> packet,
                int maxPending
        ) {
            this.player = player;
            PendingKey key = new PendingKey(typeKey, pos.immutable());
            if (!latestPackets.containsKey(key) && latestPackets.size() >= maxPending) {
                return CaptureResult.QUEUE_FULL;
            }
            return latestPackets.put(key, packet) == null
                    ? CaptureResult.NEW
                    : CaptureResult.SUPERSEDED;
        }

        private ServerPlayer player() {
            return player;
        }

        private synchronized java.util.List<Packet<?>> drain() {
            java.util.List<Packet<?>> packets = java.util.List.copyOf(latestPackets.values());
            latestPackets.clear();
            return packets;
        }
    }

    private enum CaptureResult {
        NEW(true, false),
        SUPERSEDED(true, true),
        QUEUE_FULL(false, false);

        private final boolean captured;
        private final boolean superseded;

        CaptureResult(boolean captured, boolean superseded) {
            this.captured = captured;
            this.superseded = superseded;
        }

        private boolean captured() {
            return captured;
        }

        private boolean superseded() {
            return superseded;
        }
    }
}
