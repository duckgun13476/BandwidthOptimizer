package com.PinkCats.bandwidthoptimizer.integration.minecraft;

import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;

public final class ServerPlayerLevelCompat {
    private ServerPlayerLevelCompat() {}

    public static ServerLevel serverLevel(ServerPlayer player) {
        if (player == null) {
            return null;
        }
        return player.serverLevel();
    }
}
