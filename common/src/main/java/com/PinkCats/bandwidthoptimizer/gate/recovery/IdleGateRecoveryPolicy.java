package com.PinkCats.bandwidthoptimizer.gate.recovery;

import net.minecraft.server.level.ServerPlayer;

public abstract class IdleGateRecoveryPolicy {

    public abstract void restore(ServerPlayer player);

    public void onServerTick() {}

    public void discard(ServerPlayer player) {}
}
