package com.PinkCats.bandwidthoptimizer.integration.minecraft;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.Screen;
import org.lwjgl.glfw.GLFW;

public final class ClientWindowCompat {
    private ClientWindowCompat() {
    }

    public static boolean isIconified(Minecraft minecraft) {
        try {
            long windowHandle = minecraft.getWindow().getWindow();
            return GLFW.glfwGetWindowAttrib(windowHandle, GLFW.GLFW_ICONIFIED) == GLFW.GLFW_TRUE;
        } catch (RuntimeException exception) {
            return false;
        }
    }

    public static Screen screen(Minecraft minecraft) {
        return minecraft == null ? null : minecraft.screen;
    }

    public static boolean hasOverlay(Minecraft minecraft) {
        return minecraft != null && minecraft.getOverlay() != null;
    }

    public static boolean isHudHidden(Minecraft minecraft) {
        return minecraft != null && minecraft.options.hideGui;
    }
}
