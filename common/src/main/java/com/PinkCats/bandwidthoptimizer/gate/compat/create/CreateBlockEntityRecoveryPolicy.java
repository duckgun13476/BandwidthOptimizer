package com.PinkCats.bandwidthoptimizer.gate.compat.create;

import com.PinkCats.bandwidthoptimizer.compat.minecraft.BlockEntityTypeKeyCompat;
import com.PinkCats.bandwidthoptimizer.gate.IdleGateMode;
import com.PinkCats.bandwidthoptimizer.gate.IdleGateServerState;
import com.PinkCats.bandwidthoptimizer.gate.recovery.IdleGateRecoveryPolicy;
import io.netty.channel.Channel;
import net.minecraft.core.BlockPos;
import net.minecraft.network.PacketSendListener;
import net.minecraft.network.protocol.Packet;
import net.minecraft.network.protocol.game.ClientboundBlockEntityDataPacket;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerPlayer;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

public final class CreateBlockEntityRecoveryPolicy extends IdleGateRecoveryPolicy {

    private final ConcurrentHashMap<UUID, PlayerState> states = new ConcurrentHashMap<>();

    @Override
    public boolean tryCapture(Channel channel, Packet<?> packet, PacketSendListener listener) {
        if (!CreateBlockEntityUpdateGate.isEnabled()
                || channel == null
                || packet == null
                || listener != null
                || IdleGateServerState.snapshot(channel).mode() != IdleGateMode.BACKGROUND_IDLE
                || !(packet instanceof ClientboundBlockEntityDataPacket blockEntityPacket)) {
            return false;
        }
        ResourceLocation typeKey = BlockEntityTypeKeyCompat.keyOf(blockEntityPacket.getType());
        if (!CreateGateTypePolicy.shouldHoldWhileBackground(typeKey)) {
            return false;
        }
        ServerPlayer player = CreateBlockEntityUpdateGate.resolveBoundPlayer(channel);
        if (player == null) {
            return false;
        }
        return states.computeIfAbsent(player.getUUID(), ignored -> new PlayerState())
                .remember(player, typeKey, blockEntityPacket.getPos(), packet);
    }

    @Override
    public void restore(ServerPlayer player) {
        if (player == null) {
            return;
        }
        PlayerState state = states.remove(player.getUUID());
        if (state == null) {
            return;
        }
        for (Packet<?> packet : state.drain()) {
            CreateBlockEntityUpdateGate.sendRecoveryPacket(player, packet);
        }
    }

    @Override
    public void onServerTick() {
        for (Map.Entry<UUID, PlayerState> entry : states.entrySet()) {
            PlayerState state = entry.getValue();
            ServerPlayer player = state.player();
            if (player == null
                    || IdleGateServerState.snapshot(player).mode() == IdleGateMode.BACKGROUND_IDLE
                    || !states.remove(entry.getKey(), state)) {
                continue;
            }
            for (Packet<?> packet : state.drain()) {
                CreateBlockEntityUpdateGate.sendRecoveryPacket(player, packet);
            }
        }
    }

    @Override
    public void discard(ServerPlayer player) {
        if (player != null) {
            states.remove(player.getUUID());
        }
    }

    private record PendingKey(ResourceLocation typeKey, BlockPos pos) {}

    private static final class PlayerState {
        private final Map<PendingKey, Packet<?>> latestPackets = new LinkedHashMap<>();
        private volatile ServerPlayer player;

        private synchronized boolean remember(ServerPlayer player, ResourceLocation typeKey, BlockPos pos, Packet<?> packet) {
            this.player = player;
            PendingKey key = new PendingKey(typeKey, pos.immutable());
            if (!latestPackets.containsKey(key)
                    && latestPackets.size() >= CreateGateQueueConfig.maxPendingPerPlayer()) {
                return false;
            }
            latestPackets.put(key, packet);
            return true;
        }

        private ServerPlayer player() {
            return player;
        }

        private synchronized Iterable<Packet<?>> drain() {
            java.util.List<Packet<?>> packets = java.util.List.copyOf(latestPackets.values());
            latestPackets.clear();
            return packets;
        }
    }
}
