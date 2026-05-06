package com.PinkCats.bandwidthoptimizer.compat.minecraft;

import net.minecraft.commands.CommandSourceStack;
import net.minecraft.network.chat.Component;

import java.util.function.Supplier;

public final class CommandSourceCompat {
    private CommandSourceCompat() {}

    public static void sendSuccess(CommandSourceStack source, Component component, boolean broadcastToAdmins) {
        if (source == null || component == null) {
            return;
        }
        try {
            source.getClass()
                    .getMethod("sendSuccess", Supplier.class, boolean.class)
                    .invoke(source, (Supplier<Component>) () -> component, broadcastToAdmins);
            return;
        } catch (ReflectiveOperationException ignored) {
        }
        try {
            source.getClass()
                    .getMethod("sendSuccess", Component.class, boolean.class)
                    .invoke(source, component, broadcastToAdmins);
        } catch (ReflectiveOperationException ignored) {
        }
    }
}
