package com.PinkCats.bandwidthoptimizer.gate.recovery;

import io.netty.channel.Channel;
import net.minecraft.network.PacketSendListener;
import net.minecraft.network.protocol.Packet;
import net.minecraft.server.level.ServerPlayer;

public abstract class IdleGateRecoveryPolicy {

    public abstract boolean tryCapture(Channel channel, Packet<?> packet, PacketSendListener listener);

    public abstract void restore(ServerPlayer player);

    public void onServerTick() {}

    public void discard(ServerPlayer player) {}
}
