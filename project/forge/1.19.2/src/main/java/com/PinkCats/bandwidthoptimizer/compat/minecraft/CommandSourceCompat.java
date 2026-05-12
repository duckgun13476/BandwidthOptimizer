package com.PinkCats.bandwidthoptimizer.compat.minecraft;

import net.minecraft.commands.CommandSourceStack;
import net.minecraft.network.chat.Component;

public final class CommandSourceCompat {
    private CommandSourceCompat() {}

    public static void sendSuccess(CommandSourceStack source, Component component, boolean broadcastToAdmins) {
        if (source == null || component == null) {
            return;
        }
        source.sendSuccess(component, broadcastToAdmins);
    }
}
