package com.PinkCats.bandwidthoptimizer.integration.minecraft;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.Screen;

public final class ClientWindowCompat {
    private ClientWindowCompat() {
    }

    public static boolean isIconified(Minecraft minecraft) {
        return minecraft != null && minecraft.getWindow().isIconified();
    }

    public static Screen screen(Minecraft minecraft) {
        return minecraft == null ? null : minecraft.gui.screen();
    }

    public static boolean hasOverlay(Minecraft minecraft) {
        return minecraft != null && minecraft.gui.overlay() != null;
    }

    public static boolean isHudHidden(Minecraft minecraft) {
        return minecraft != null && minecraft.gui.hud.isHidden();
    }
}
