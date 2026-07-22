package com.PinkCats.bandwidthoptimizer.gate.integration.create;

import com.PinkCats.bandwidthoptimizer.gate.recovery.BlockEntityRecoveryPolicy;
import io.netty.channel.Channel;
import net.minecraft.network.protocol.Packet;
import net.minecraft.server.level.ServerPlayer;

abstract class AbstractCreateBlockEntityRecoveryPolicy extends BlockEntityRecoveryPolicy {

    @Override
    protected final ServerPlayer resolvePlayer(Channel channel) {
        return CreateBlockEntityUpdateGate.resolveBoundPlayer(channel);
    }

    @Override
    protected final int maxPendingPerPlayer() {
        return CreateGateQueueConfig.maxPendingPerPlayer();
    }

    @Override
    protected void sendRecoveryPacket(ServerPlayer player, Packet<?> packet) {
        CreateBlockEntityUpdateGate.sendRecoveryPacket(player, packet);
    }
}
