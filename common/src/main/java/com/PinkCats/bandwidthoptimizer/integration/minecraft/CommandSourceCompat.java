package com.PinkCats.bandwidthoptimizer.integration.minecraft;

import net.minecraft.ChatFormatting;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.network.chat.ClickEvent;
import net.minecraft.network.chat.Component;

import java.net.URI;

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
        return source != null && source.hasPermission(level);
    }

    public static Component viewerLink(URI uri) {
        return Component.literal(uri.toASCIIString())
                .withStyle(ChatFormatting.GRAY)
                .withStyle(style -> style.withClickEvent(new ClickEvent(ClickEvent.Action.OPEN_URL, uri.toASCIIString())));
    }
}
