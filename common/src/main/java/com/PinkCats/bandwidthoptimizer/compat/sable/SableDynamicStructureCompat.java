package com.PinkCats.bandwidthoptimizer.compat.sable;

import com.PinkCats.bandwidthoptimizer.Bandwidthoptimizer;
import com.PinkCats.bandwidthoptimizer.compat.minecraft.ServerPlayerLevelCompat;
import com.PinkCats.bandwidthoptimizer.debug.DebugRuntimeConfig;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.phys.Vec3;

import java.lang.reflect.Field;
import java.lang.reflect.Method;

public final class SableDynamicStructureCompat {

    private static volatile Object helper;
    private static volatile Method projectOutMethod;
    private static volatile boolean lookupAttempted;

    private SableDynamicStructureCompat() {}

    public static DynamicTarget resolveTarget(ServerPlayer player, BlockPos pos, Vec3 fallbackTarget) {
        if (player == null || pos == null || fallbackTarget == null) {
            return DynamicTarget.vanilla(fallbackTarget);
        }
        Method method = projectOutMethod();
        Object targetHelper = helper;
        if (method == null || targetHelper == null) {
            return DynamicTarget.vanilla(fallbackTarget);
        }
        ServerLevel level = ServerPlayerLevelCompat.serverLevel(player);
        if (level == null) {
            return DynamicTarget.vanilla(fallbackTarget);
        }
        try {
            Object projected = method.invoke(targetHelper, level, fallbackTarget);
            if (!(projected instanceof Vec3 projectedTarget)) {
                return DynamicTarget.forceImmediate(fallbackTarget);
            }
            return DynamicTarget.resolved(projectedTarget, !samePosition(fallbackTarget, projectedTarget));
        } catch (ReflectiveOperationException | RuntimeException exception) {
            logFailure(exception);
            return DynamicTarget.forceImmediate(fallbackTarget);
        }
    }

    private static Method projectOutMethod() {
        if (lookupAttempted) {
            return projectOutMethod;
        }
        synchronized (SableDynamicStructureCompat.class) {
            if (lookupAttempted) {
                return projectOutMethod;
            }
            lookupAttempted = true;
            try {
                Class<?> sableClass = Class.forName("dev.ryanhcode.sable.Sable");
                Field helperField = sableClass.getField("HELPER");
                Object resolvedHelper = helperField.get(null);
                if (resolvedHelper == null) {
                    return null;
                }
                for (Method method : resolvedHelper.getClass().getMethods()) {
                    if (!"projectOutOfSubLevel".equals(method.getName()) || method.getParameterCount() != 2) {
                        continue;
                    }
                    Class<?>[] parameterTypes = method.getParameterTypes();
                    if (parameterTypes[1].isAssignableFrom(Vec3.class)) {
                        helper = resolvedHelper;
                        projectOutMethod = method;
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
            Bandwidthoptimizer.LOGGER.info("[SableCompat][CreateTarget] failed to project dynamic target", exception);
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
