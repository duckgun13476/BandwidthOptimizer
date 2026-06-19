package com.PinkCats.bandwidthoptimizer.compat.minecraft;

import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;

public final class ServerPlayerLevelCompat {
    private ServerPlayerLevelCompat() {}

    // NeoForge 1.21.1 浣跨敤鏂扮増 serverPlayer.level()锛屽拰 Forge 1.20.1 淇濇寔鍚屽悕閫傞厤鍏ュ彛銆?
    public static ServerLevel serverLevel(ServerPlayer player) {
        if (player == null) {
            return null;
        }
        return player.level();
    }
}
