package com.PinkCats.bandwidthoptimizer.integration.minecraft;

import net.minecraft.client.Minecraft;
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
}
