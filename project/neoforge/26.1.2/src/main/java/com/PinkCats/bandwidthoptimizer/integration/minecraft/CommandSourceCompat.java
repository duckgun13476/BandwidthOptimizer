package com.PinkCats.bandwidthoptimizer.integration.minecraft;

import net.minecraft.commands.CommandSourceStack;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;

public final class CommandSourceCompat {
    private CommandSourceCompat() {
    }

    public static void sendSuccess(CommandSourceStack source, Component component, boolean broadcastToAdmins) {
        if (source == null || component == null) {
            return;
        }
        source.sendSuccess(() -> component, broadcastToAdmins);
    }

    public static boolean hasPermission(CommandSourceStack source, int level) {
        if (source == null) {
            return false;
        }
        if (!(source.getEntity() instanceof ServerPlayer player)) {
            return true;
        }
        return source.getServer().getPlayerList().isOp(player.nameAndId());
    }
}
