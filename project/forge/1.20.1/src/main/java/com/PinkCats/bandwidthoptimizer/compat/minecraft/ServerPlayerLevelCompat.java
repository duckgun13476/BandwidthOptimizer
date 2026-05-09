package com.PinkCats.bandwidthoptimizer.compat.minecraft;

import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;

public final class ServerPlayerLevelCompat {
    private ServerPlayerLevelCompat() {}

    // Forge 1.20.1 使用本版本的原版访问器，让 reobf 在发布包中正确映射到生产环境命名。
    public static ServerLevel serverLevel(ServerPlayer player) {
        if (player == null) {
            return null;
        }
        return (ServerLevel) player.level();
    }
}
