package com.PinkCats.bandwidthoptimizer.gate;

import com.PinkCats.bandwidthoptimizer.client.hud.BandwidthOptimizerHudOverlay;
import net.minecraft.client.Minecraft;
import net.minecraft.client.player.LocalPlayer;
import org.lwjgl.glfw.GLFW;

import java.util.function.Consumer;

public final class IdleGateClientController {

    private static final long FOREGROUND_STILL_DELAY_MILLIS = 10_000L;
    private static final long BACKGROUND_IDLE_DELAY_MILLIS = 2_000L;
    private static final long WORLD_READY_GRACE_MILLIS = 30_000L;
    private static final long ACTIVE_REPORT_MILLIS = 15_000L;
    private static final long FOREGROUND_STILL_REPORT_MILLIS = 30_000L;
    private static final long BACKGROUND_IDLE_REPORT_MILLIS = 60_000L;

    private static Consumer<IdleGateStatePayload> sender;
    private static IdleGateMode lastSentMode = IdleGateMode.ACTIVE;
    private static IdleGateMode currentMode = IdleGateMode.ACTIVE;
    private static boolean lastSentHudVisible = true;
    private static long lastActivityMillis = System.currentTimeMillis();
    private static long lastReportMillis;
    private static int sequence;
    private static double lastX = Double.NaN;
    private static double lastY = Double.NaN;
    private static double lastZ = Double.NaN;
    private static float lastYaw = Float.NaN;
    private static float lastPitch = Float.NaN;
    private static Object lastLevelIdentity;
    private static Object lastConnectionIdentity;
    private static long worldReadySinceMillis;

    private IdleGateClientController() {}

    public static void setSender(Consumer<IdleGateStatePayload> payloadSender) {
        sender = payloadSender;
    }

    public static void onClientTick(Minecraft minecraft) {
        if (minecraft == null || minecraft.player == null || minecraft.getConnection() == null) {
            resetLocalState();
            return;
        }

        long nowMillis = System.currentTimeMillis();
        LocalPlayer player = minecraft.player;
        boolean activeThisTick = observePlayerActivity(player);
        if (activeThisTick) {
            lastActivityMillis = nowMillis;
        }

        boolean stableWorldReady = observeStableWorldReady(minecraft, nowMillis);
        if (!stableWorldReady) {
            lastActivityMillis = nowMillis;
        }
        boolean background = stableWorldReady && isBackground(minecraft);
        IdleGateMode mode = resolveMode(nowMillis, background);
        boolean hudVisible = BandwidthOptimizerHudOverlay.isEnabled()
                && !minecraft.options.hideGui
                && minecraft.screen == null
                && !background;
        maybeSend(mode, hudVisible, nowMillis);
    }

    public static IdleGateMode currentMode() {
        return currentMode;
    }

    public static void onDisconnected() {
        resetLocalState();
    }

    private static IdleGateMode resolveMode(long nowMillis, boolean background) {
        long idleMillis = nowMillis - lastActivityMillis;
        if (background && idleMillis >= BACKGROUND_IDLE_DELAY_MILLIS) {
            return IdleGateMode.BACKGROUND_IDLE;
        }
        if (!background && idleMillis >= FOREGROUND_STILL_DELAY_MILLIS) {
            return IdleGateMode.FOREGROUND_STILL;
        }
        return IdleGateMode.ACTIVE;
    }

    private static void maybeSend(IdleGateMode mode, boolean hudVisible, long nowMillis) {
        Consumer<IdleGateStatePayload> currentSender = sender;
        if (currentSender == null) {
            return;
        }
        currentMode = mode;
        boolean changed = mode != lastSentMode || hudVisible != lastSentHudVisible;
        if (!changed && nowMillis - lastReportMillis < reportIntervalMillis(mode)) {
            return;
        }
        // State transitions are immediate; stable states use bounded heartbeats.
        lastSentMode = mode;
        lastSentHudVisible = hudVisible;
        lastReportMillis = nowMillis;
        currentSender.accept(new IdleGateStatePayload(++sequence, mode, hudVisible, nowMillis));
    }

    private static long reportIntervalMillis(IdleGateMode mode) {
        return switch (mode) {
            case BACKGROUND_IDLE -> BACKGROUND_IDLE_REPORT_MILLIS;
            case FOREGROUND_STILL -> FOREGROUND_STILL_REPORT_MILLIS;
            case ACTIVE -> ACTIVE_REPORT_MILLIS;
        };
    }

    private static boolean observePlayerActivity(LocalPlayer player) {
        boolean initialized = !Double.isNaN(lastX);
        double x = player.getX();
        double y = player.getY();
        double z = player.getZ();
        float yaw = player.getYRot();
        float pitch = player.getXRot();
        boolean moved = initialized
                && (distanceSqr(lastX, lastY, lastZ, x, y, z) > 0.0004D
                || Math.abs(lastYaw - yaw) > 0.1F
                || Math.abs(lastPitch - pitch) > 0.1F);
        lastX = x;
        lastY = y;
        lastZ = z;
        lastYaw = yaw;
        lastPitch = pitch;
        return !initialized || moved;
    }

    private static double distanceSqr(double ax, double ay, double az, double bx, double by, double bz) {
        double dx = ax - bx;
        double dy = ay - by;
        double dz = az - bz;
        return dx * dx + dy * dy + dz * dz;
    }

    private static boolean isBackground(Minecraft minecraft) {
        if (minecraft.screen != null && !isPauseScreen(minecraft)) {
            return false;
        }
        if (!isWindowActive(minecraft)) {
            return true;
        }
        try {
            long windowHandle = minecraft.getWindow().getWindow();
            if (GLFW.glfwGetWindowAttrib(windowHandle, GLFW.GLFW_ICONIFIED) == GLFW.GLFW_TRUE) {
                return true;
            }
        } catch (RuntimeException exception) {
            return false;
        }
        return isPauseScreen(minecraft);
    }

    private static boolean observeStableWorldReady(Minecraft minecraft, long nowMillis) {
        Object levelIdentity = minecraft.level;
        Object connectionIdentity = minecraft.getConnection();
        if (levelIdentity == null || connectionIdentity == null) {
            clearWorldReadyState();
            return false;
        }
        if (levelIdentity != lastLevelIdentity || connectionIdentity != lastConnectionIdentity) {
            lastLevelIdentity = levelIdentity;
            lastConnectionIdentity = connectionIdentity;
            worldReadySinceMillis = nowMillis;
            return false;
        }
        if (minecraft.screen != null && !isPauseScreen(minecraft)) {
            worldReadySinceMillis = nowMillis;
            return false;
        }
        return nowMillis - worldReadySinceMillis >= WORLD_READY_GRACE_MILLIS;
    }

    private static boolean isPauseScreen(Minecraft minecraft) {
        if (minecraft.screen == null) {
            return false;
        }
        String screenClassName = minecraft.screen.getClass().getName();
        return "net.minecraft.client.gui.screens.PauseScreen".equals(screenClassName)
                || screenClassName.endsWith(".PauseScreen");
    }

    private static boolean isWindowActive(Minecraft minecraft) {
        try {
            return (boolean) Minecraft.class.getMethod("isWindowActive").invoke(minecraft);
        } catch (ReflectiveOperationException | RuntimeException exception) {
            return true;
        }
    }

    private static void resetLocalState() {
        lastSentMode = IdleGateMode.ACTIVE;
        currentMode = IdleGateMode.ACTIVE;
        lastSentHudVisible = true;
        lastActivityMillis = System.currentTimeMillis();
        lastReportMillis = 0L;
        lastX = Double.NaN;
        lastY = Double.NaN;
        lastZ = Double.NaN;
        lastYaw = Float.NaN;
        lastPitch = Float.NaN;
        clearWorldReadyState();
    }

    private static void clearWorldReadyState() {
        lastLevelIdentity = null;
        lastConnectionIdentity = null;
        worldReadySinceMillis = 0L;
    }
}
