package com.PinkCats.bandwidthoptimizer.compat.minecraft;

import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;

public final class ServerPlayerLevelCompat {
    private ServerPlayerLevelCompat() {}

    public static ServerLevel serverLevel(ServerPlayer player) {
        if (player == null) {
            return null;
        }
        try {
            return (ServerLevel) player.getClass().getMethod("serverLevel").invoke(player);
        } catch (ReflectiveOperationException ignored) {
        }
        try {
            return (ServerLevel) player.getClass().getMethod("getLevel").invoke(player);
        } catch (ReflectiveOperationException exception) {
            throw new IllegalStateException("Unable to resolve ServerPlayer level", exception);
        }
    }
}
