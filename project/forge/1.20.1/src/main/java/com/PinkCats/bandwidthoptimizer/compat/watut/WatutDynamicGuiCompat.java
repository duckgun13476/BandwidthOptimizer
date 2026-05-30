package com.PinkCats.bandwidthoptimizer.compat.watut;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.ReceivingLevelScreen;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.util.Mth;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.nio.ByteBuffer;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;
import java.util.WeakHashMap;
import java.util.concurrent.ConcurrentHashMap;

public final class WatutDynamicGuiCompat {

    private static final int VISIBILITY_UNSEEN = 0;
    private static final int VISIBILITY_DISTANT = 1;
    private static final int VISIBILITY_NEAR = 2;
    private static final int UNSEEN_TICK_RATE = 200;
    private static final int DISTANT_TICK_RATE = 40;
    private static final int DISTANT_VIEW_DISTANCE = 16;
    private static final int VIEWER_CHECK_RATE = 10;
    private static final double VIEW_DOT_THRESHOLD = 0.35D;
    private static final Map<Object, State> MANAGER_STATES = new WeakHashMap<>();
    private static final ConcurrentHashMap<Class<?>, Field> SELF_STATUS_FIELDS = new ConcurrentHashMap<>();
    private static final ConcurrentHashMap<String, Method> METHODS = new ConcurrentHashMap<>();

    private WatutDynamicGuiCompat() {}

    public static void afterTickGame(Object manager) {
        Minecraft mc = Minecraft.getInstance();
        if (manager == null || mc.level == null || mc.player == null) {
            reset(manager);
            return;
        }

        Object selfStatus = selfPlayerStatus(manager);
        Object screenData = screenData(selfStatus);
        if (selfStatus == null || screenData == null) {
            reset(manager);
            return;
        }

        Screen screen = mc.screen;
        State state = state(manager);
        long gameTime = mc.level.getGameTime();
        int visibilityState = resolveVisibilityState(mc.player);
        boolean visibilityImproved = visibilityState > state.visibilityState;
        state.visibilityState = visibilityState;

        if (screen == null) {
            setNeedsNewRenderToPixelData(screenData, false);
            state.reset();
            return;
        }

        boolean validGui = isValidGui(screen, selfStatus);
        boolean stillActiveInGui = intValue(selfStatus, "getTicksSinceLastAction", 0) < 100;
        boolean captureRequested = isNeedsNewRenderToPixelData(screenData);
        boolean shouldForceVisibleCapture = validGui && stillActiveInGui && visibilityImproved && visibilityState != VISIBILITY_UNSEEN;

        if (captureRequested || shouldForceVisibleCapture) {
            if (validGui && stillActiveInGui && shouldAllowFreshFrame(state, gameTime, visibilityState, visibilityImproved)) {
                setNeedsNewRenderToPixelData(screenData, true);
                state.lastAcceptedFrameTick = gameTime;
                updateVisibleViewers(state, mc.player);
            } else {
                setNeedsNewRenderToPixelData(screenData, false);
            }
            return;
        }

        if (validGui
                && visibilityState != VISIBILITY_UNSEEN
                && hasTexturePixelData(screenData)
                && shouldCheckViewers(state, gameTime, visibilityImproved)) {
            boolean newViewer = updateVisibleViewers(state, mc.player);
            state.lastViewerCheckTick = gameTime;
            if (newViewer) {
                invokeSendScreenRenderData(manager, selfStatus);
            }
        }
    }

    public static void afterSendScreenRenderData(Object status) {
        Object screenData = screenData(status);
        Object texturePixelData = objectValue(screenData, "getTexturePixelData");
        if (texturePixelData instanceof ByteBuffer buffer) {
            buffer.flip();
        }
    }

    private static boolean shouldAllowFreshFrame(State state, long gameTime, int visibilityState, boolean visibilityImproved) {
        if (state.lastAcceptedFrameTick == Long.MIN_VALUE || visibilityImproved && visibilityState != VISIBILITY_UNSEEN) {
            return true;
        }
        return gameTime >= state.lastAcceptedFrameTick + sendRate(visibilityState);
    }

    private static boolean shouldCheckViewers(State state, long gameTime, boolean visibilityImproved) {
        return visibilityImproved || gameTime >= state.lastViewerCheckTick + VIEWER_CHECK_RATE;
    }

    private static int sendRate(int visibilityState) {
        if (visibilityState == VISIBILITY_NEAR) {
            return Math.max(5, intStaticValue(
                    "com.corosus.watut.config.ConfigServerControlledSyncedToClient",
                    "dynamicGuiTickSendRateOfGUIUpdates",
                    10
            ));
        }
        if (visibilityState == VISIBILITY_DISTANT) {
            return DISTANT_TICK_RATE;
        }
        return UNSEEN_TICK_RATE;
    }

    private static int resolveVisibilityState(Player sourcePlayer) {
        Minecraft mc = Minecraft.getInstance();
        if (sourcePlayer == null || mc.level == null) {
            return VISIBILITY_UNSEEN;
        }

        double distantDistance = DISTANT_VIEW_DISTANCE;
        double maxDistance = Math.max(distantDistance, intStaticValue(
                "com.corosus.watut.config.ConfigServerControlledSyncedToClient",
                "distanceRequiredToShowGUIInfo",
                10
        ));
        double maxDistanceSqr = maxDistance * maxDistance;
        double distantDistanceSqr = distantDistance * distantDistance;
        AABB playerBox = sourcePlayer.getBoundingBox();
        AABB guiBox = createDynamicGuiViewBox(sourcePlayer);
        int bestState = VISIBILITY_UNSEEN;

        for (Player observer : mc.level.players()) {
            if (observer == sourcePlayer || observer.isSpectator()) {
                continue;
            }
            double distanceSqr = observer.distanceToSqr(sourcePlayer);
            if (maxDistance > 0 && distanceSqr > maxDistanceSqr) {
                continue;
            }
            if (!canObserverSeeBox(observer, playerBox) && !canObserverSeeBox(observer, guiBox)) {
                continue;
            }
            if (distanceSqr <= distantDistanceSqr) {
                return VISIBILITY_NEAR;
            }
            bestState = VISIBILITY_DISTANT;
        }

        return bestState;
    }

    private static boolean updateVisibleViewers(State state, Player sourcePlayer) {
        Minecraft mc = Minecraft.getInstance();
        HashMap<UUID, Boolean> visibleViewers = new HashMap<>();
        boolean newViewer = false;
        if (sourcePlayer == null || mc.level == null) {
            state.visibleViewers = visibleViewers;
            return false;
        }

        double maxDistance = Math.max(DISTANT_VIEW_DISTANCE, intStaticValue(
                "com.corosus.watut.config.ConfigServerControlledSyncedToClient",
                "distanceRequiredToShowGUIInfo",
                10
        ));
        double maxDistanceSqr = maxDistance * maxDistance;
        AABB playerBox = sourcePlayer.getBoundingBox();
        AABB guiBox = createDynamicGuiViewBox(sourcePlayer);

        for (Player observer : mc.level.players()) {
            if (observer == sourcePlayer || observer.isSpectator()) {
                continue;
            }
            if (maxDistance > 0 && observer.distanceToSqr(sourcePlayer) > maxDistanceSqr) {
                continue;
            }
            if (!canObserverSeeBox(observer, playerBox) && !canObserverSeeBox(observer, guiBox)) {
                continue;
            }
            UUID uuid = observer.getUUID();
            visibleViewers.put(uuid, true);
            if (!state.visibleViewers.containsKey(uuid)) {
                newViewer = true;
            }
        }

        state.visibleViewers = visibleViewers;
        return newViewer;
    }

    private static AABB createDynamicGuiViewBox(Player sourcePlayer) {
        Vec3 forward = calculateViewVector(sourcePlayer.getXRot(), sourcePlayer.yBodyRot);
        Vec3 center = sourcePlayer.position().add(forward.x * 2D, 1.2D, forward.z * 2D);
        return AABB.ofSize(center, 2D, 1.6D, 2D);
    }

    private static Vec3 calculateViewVector(float xRot, float yRot) {
        float xRotRadians = xRot * ((float) Math.PI / 180F);
        float yRotRadians = -yRot * ((float) Math.PI / 180F);
        float cosY = Mth.cos(yRotRadians);
        float sinY = Mth.sin(yRotRadians);
        float cosX = Mth.cos(xRotRadians);
        float sinX = Mth.sin(xRotRadians);
        return new Vec3(sinY * cosX, -sinX, cosY * cosX);
    }

    private static boolean canObserverSeeBox(Player observer, AABB box) {
        Vec3 eyePos = observer.getEyePosition(1F);
        if (box.contains(eyePos)) {
            return true;
        }

        return canObserverSeePoint(observer, eyePos, box.minX, box.minY, box.minZ)
                || canObserverSeePoint(observer, eyePos, box.minX, box.minY, box.maxZ)
                || canObserverSeePoint(observer, eyePos, box.minX, box.maxY, box.minZ)
                || canObserverSeePoint(observer, eyePos, box.minX, box.maxY, box.maxZ)
                || canObserverSeePoint(observer, eyePos, box.maxX, box.minY, box.minZ)
                || canObserverSeePoint(observer, eyePos, box.maxX, box.minY, box.maxZ)
                || canObserverSeePoint(observer, eyePos, box.maxX, box.maxY, box.minZ)
                || canObserverSeePoint(observer, eyePos, box.maxX, box.maxY, box.maxZ);
    }

    private static boolean canObserverSeePoint(Player observer, Vec3 eyePos, double x, double y, double z) {
        Vec3 toPoint = new Vec3(x - eyePos.x, y - eyePos.y, z - eyePos.z);
        double distanceSqr = toPoint.lengthSqr();
        if (distanceSqr < 0.25D) {
            return true;
        }
        return observer.getViewVector(1F).dot(toPoint.normalize()) >= VIEW_DOT_THRESHOLD;
    }

    private static boolean isValidGui(Screen screen, Object selfStatus) {
        if (screen == null || screen instanceof ReceivingLevelScreen) {
            return false;
        }
        Object guiState = objectValue(selfStatus, "getPlayerGuiState");
        if (guiState == null) {
            return false;
        }
        String stateName = guiState.toString();
        return !"NONE".equals(stateName) && !"CHAT_SCREEN".equals(stateName);
    }

    private static boolean hasTexturePixelData(Object screenData) {
        return objectValue(screenData, "getTexturePixelData") instanceof ByteBuffer;
    }

    private static boolean isNeedsNewRenderToPixelData(Object screenData) {
        return booleanValue(screenData, "isNeedsNewRenderToPixelData", false);
    }

    private static void setNeedsNewRenderToPixelData(Object screenData, boolean value) {
        invoke(screenData, "setNeedsNewRenderToPixelData", new Class<?>[]{boolean.class}, value);
    }

    private static Object selfPlayerStatus(Object manager) {
        if (manager == null) {
            return null;
        }
        try {
            Field field = SELF_STATUS_FIELDS.computeIfAbsent(manager.getClass(), WatutDynamicGuiCompat::selfStatusField);
            return field == null ? null : field.get(manager);
        } catch (IllegalAccessException | RuntimeException ignored) {
            return null;
        }
    }

    private static Field selfStatusField(Class<?> managerClass) {
        try {
            Field field = managerClass.getDeclaredField("selfPlayerStatus");
            field.setAccessible(true);
            return field;
        } catch (ReflectiveOperationException | RuntimeException ignored) {
            return null;
        }
    }

    private static Object screenData(Object status) {
        return objectValue(status, "getScreenData");
    }

    private static void invokeSendScreenRenderData(Object manager, Object status) {
        if (manager == null || status == null) {
            return;
        }
        try {
            Method method = method(manager.getClass(), "sendScreenRenderData", status.getClass());
            if (method != null) {
                method.invoke(manager, status);
            }
        } catch (ReflectiveOperationException | RuntimeException ignored) {
        }
    }

    private static Object objectValue(Object target, String methodName) {
        return invoke(target, methodName, new Class<?>[0]);
    }

    private static int intValue(Object target, String methodName, int fallback) {
        Object value = objectValue(target, methodName);
        return value instanceof Number number ? number.intValue() : fallback;
    }

    private static boolean booleanValue(Object target, String methodName, boolean fallback) {
        Object value = objectValue(target, methodName);
        return value instanceof Boolean booleanValue ? booleanValue : fallback;
    }

    private static Object invoke(Object target, String methodName, Class<?>[] parameterTypes, Object... args) {
        if (target == null) {
            return null;
        }
        try {
            Method method = method(target.getClass(), methodName, parameterTypes);
            return method == null ? null : method.invoke(target, args);
        } catch (ReflectiveOperationException | RuntimeException ignored) {
            return null;
        }
    }

    private static Method method(Class<?> targetClass, String methodName, Class<?>... parameterTypes) {
        String key = targetClass.getName() + "#" + methodName + "#" + parameterTypes.length;
        return METHODS.computeIfAbsent(key, ignored -> {
            try {
                Method method = targetClass.getMethod(methodName, parameterTypes);
                method.setAccessible(true);
                return method;
            } catch (ReflectiveOperationException | RuntimeException exception) {
                return null;
            }
        });
    }

    private static int intStaticValue(String className, String fieldName, int fallback) {
        try {
            Class<?> configClass = Class.forName(className);
            Field field = configClass.getField(fieldName);
            return field.getInt(null);
        } catch (ReflectiveOperationException | LinkageError | RuntimeException ignored) {
            return fallback;
        }
    }

    private static State state(Object manager) {
        synchronized (MANAGER_STATES) {
            return MANAGER_STATES.computeIfAbsent(manager, ignored -> new State());
        }
    }

    private static void reset(Object manager) {
        if (manager == null) {
            return;
        }
        synchronized (MANAGER_STATES) {
            State state = MANAGER_STATES.get(manager);
            if (state != null) {
                state.reset();
            }
        }
    }

    private static final class State {
        private int visibilityState = VISIBILITY_UNSEEN;
        private long lastAcceptedFrameTick = Long.MIN_VALUE;
        private long lastViewerCheckTick = Long.MIN_VALUE;
        private Map<UUID, Boolean> visibleViewers = new HashMap<>();

        private void reset() {
            this.visibilityState = VISIBILITY_UNSEEN;
            this.lastAcceptedFrameTick = Long.MIN_VALUE;
            this.lastViewerCheckTick = Long.MIN_VALUE;
            this.visibleViewers = new HashMap<>();
        }
    }
}
