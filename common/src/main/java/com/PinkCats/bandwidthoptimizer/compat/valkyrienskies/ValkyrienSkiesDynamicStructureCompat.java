package com.PinkCats.bandwidthoptimizer.compat.valkyrienskies;

import com.PinkCats.bandwidthoptimizer.Bandwidthoptimizer;
import com.PinkCats.bandwidthoptimizer.compat.minecraft.ServerPlayerLevelCompat;
import com.PinkCats.bandwidthoptimizer.debug.DebugRuntimeConfig;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.phys.Vec3;

import java.lang.reflect.Method;
import java.lang.reflect.Modifier;

public final class ValkyrienSkiesDynamicStructureCompat {

    private static volatile Method toWorldCoordinatesMethod;
    private static volatile boolean lookupAttempted;

    private ValkyrienSkiesDynamicStructureCompat() {}

    public static DynamicTarget resolveTarget(ServerPlayer player, BlockPos pos, Vec3 fallbackTarget) {
        if (player == null || pos == null || fallbackTarget == null) {
            return DynamicTarget.vanilla(fallbackTarget);
        }
        Method method = toWorldCoordinatesMethod();
        if (method == null) {
            return DynamicTarget.vanilla(fallbackTarget);
        }
        ServerLevel level = ServerPlayerLevelCompat.serverLevel(player);
        if (level == null) {
            return DynamicTarget.vanilla(fallbackTarget);
        }
        try {
            Object projected = method.invoke(null, level, fallbackTarget);
            if (!(projected instanceof Vec3 projectedTarget)) {
                return DynamicTarget.forceImmediate(fallbackTarget);
            }
            return DynamicTarget.resolved(projectedTarget, !samePosition(fallbackTarget, projectedTarget));
        } catch (ReflectiveOperationException | RuntimeException exception) {
            logFailure(exception);
            return DynamicTarget.forceImmediate(fallbackTarget);
        }
    }

    private static Method toWorldCoordinatesMethod() {
        if (lookupAttempted) {
            return toWorldCoordinatesMethod;
        }
        synchronized (ValkyrienSkiesDynamicStructureCompat.class) {
            if (lookupAttempted) {
                return toWorldCoordinatesMethod;
            }
            lookupAttempted = true;
            try {
                Class<?> utilsClass = Class.forName("org.valkyrienskies.mod.common.VSGameUtilsKt");
                for (Method method : utilsClass.getMethods()) {
                    if (!Modifier.isStatic(method.getModifiers())
                            || !"toWorldCoordinates".equals(method.getName())
                            || method.getParameterCount() != 2
                            || !Vec3.class.isAssignableFrom(method.getReturnType())) {
                        continue;
                    }
                    Class<?>[] parameterTypes = method.getParameterTypes();
                    if (parameterTypes[1].isAssignableFrom(Vec3.class)) {
                        toWorldCoordinatesMethod = method;
                        return method;
                    }
                }
            } catch (ReflectiveOperationException | LinkageError ignored) {
                return null;
            }
            return null;
        }
    }

    private static boolean samePosition(Vec3 left, Vec3 right) {
        return left != null && right != null && left.distanceToSqr(right) <= 1.0E-6D;
    }

    private static void logFailure(Exception exception) {
        if (DebugRuntimeConfig.isDiagnoseEnabled()) {
            Bandwidthoptimizer.LOGGER.info("[ValkyrienSkiesCompat][CreateTarget] failed to project dynamic target", exception);
        }
    }

    public record DynamicTarget(Vec3 target, boolean transformed, boolean forceImmediate) {
        private static DynamicTarget vanilla(Vec3 target) {
            return new DynamicTarget(target, false, false);
        }

        private static DynamicTarget resolved(Vec3 target, boolean transformed) {
            return new DynamicTarget(target, transformed, false);
        }

        private static DynamicTarget forceImmediate(Vec3 target) {
            return new DynamicTarget(target, false, true);
        }
    }
}
