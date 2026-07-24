package com.PinkCats.bandwidthoptimizer.gate.integration.minecraft;

import com.PinkCats.bandwidthoptimizer.gate.IdleGateMode;
import com.PinkCats.bandwidthoptimizer.gate.IdleGateServerState;
import net.minecraft.server.level.ServerPlayer;

public final class IdleGateHudSyncPolicy {

    private static final int ACTIVE_HUD_INTERVAL_TICKS = 5;
    private static final int STILL_HUD_INTERVAL_TICKS = 20 * 5;

    private IdleGateHudSyncPolicy() {}

    public static boolean shouldSend(ServerPlayer player, int serverTick) {
        IdleGateServerState.PlayerIdleState state = IdleGateServerState.snapshot(player);
        if (!state.hudVisible()) {
            return false;
        }
        if (state.mode().suppressesWorldPresentation()) {
            return false;
        }
        int interval = state.mode() == IdleGateMode.FOREGROUND_STILL
                ? STILL_HUD_INTERVAL_TICKS
                : ACTIVE_HUD_INTERVAL_TICKS;
        return serverTick % interval == 0;
    }
}
