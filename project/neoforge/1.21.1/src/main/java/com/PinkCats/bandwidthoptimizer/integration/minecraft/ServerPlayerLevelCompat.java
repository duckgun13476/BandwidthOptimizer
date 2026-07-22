package com.PinkCats.bandwidthoptimizer.integration.minecraft;

import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;

public final class ServerPlayerLevelCompat {
    private ServerPlayerLevelCompat() {}

    // NeoForge 1.21.1 使用新版 ServerPlayer.serverLevel()，和 Forge 1.20.1 保持同名适配入口。
    public static ServerLevel serverLevel(ServerPlayer player) {
        if (player == null) {
            return null;
        }
        return player.serverLevel();
    }
}
