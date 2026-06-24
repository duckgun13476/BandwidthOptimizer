package com.PinkCats.bandwidthoptimizer.compat.valkyrienskies;

import com.PinkCats.bandwidthoptimizer.Bandwidthoptimizer;
import com.PinkCats.bandwidthoptimizer.compat.minecraft.ServerPlayerLevelCompat;
import com.PinkCats.bandwidthoptimizer.debug.DiagnosticToolRegistry;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.phys.Vec3;

import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
import java.lang.reflect.Method;

public final class ValkyrienSkiesDynamicStructureCompat {

    private static volatile ServerBridge serverBridge;
    private static volatile boolean lookupAttempted;

    private ValkyrienSkiesDynamicStructureCompat() {}

    public static DynamicTarget resolveTarget(ServerPlayer player, BlockPos pos, Vec3 fallbackTarget) {
        if (player == null || pos == null || fallbackTarget == null) {
            return DynamicTarget.vanilla(fallbackTarget);
        }
        ServerBridge bridge = serverBridge();
        if (bridge == null) {
            return DynamicTarget.vanilla(fallbackTarget);
        }
        ServerLevel level = ServerPlayerLevelCompat.serverLevel(player);
        if (level == null) {
            return DynamicTarget.vanilla(fallbackTarget);
        }
        try {
            Object shipWorld = bridge.getShipObjectWorld.invoke(level.getServer());
            if (shipWorld == null) {
                return DynamicTarget.vanilla(fallbackTarget);
            }
            Object dimensionId = bridge.getDimensionId.invoke(level);
            if (!(dimensionId instanceof String dimension)) {
                return DynamicTarget.vanilla(fallbackTarget);
            }
            Object allShips = bridge.getAllShips.invoke(shipWorld);
            Object ship = bridge.getByChunkPos.invoke(allShips, pos.getX() >> 4, pos.getZ() >> 4, dimension);
            if (ship == null) {
                return DynamicTarget.vanilla(fallbackTarget);
            }
            Object shipToWorld = bridge.getShipToWorld.invoke(ship);
            if (!bridge.matrix4dcClass.isInstance(shipToWorld)) {
                return DynamicTarget.forceImmediate(fallbackTarget);
            }
            Object projected = bridge.vector3dConstructor.newInstance();
            bridge.transformPosition.invoke(
                    shipToWorld,
                    fallbackTarget.x,
                    fallbackTarget.y,
                    fallbackTarget.z,
                    projected);
            Vec3 projectedTarget = new Vec3(
                    bridge.vector3dX.getDouble(projected),
                    bridge.vector3dY.getDouble(projected),
                    bridge.vector3dZ.getDouble(projected));
            return DynamicTarget.resolved(projectedTarget, !samePosition(fallbackTarget, projectedTarget));
        } catch (ReflectiveOperationException | RuntimeException exception) {
            logFailure(exception);
            return DynamicTarget.forceImmediate(fallbackTarget);
        }
    }

    private static ServerBridge serverBridge() {
        if (lookupAttempted) {
            return serverBridge;
        }
        synchronized (ValkyrienSkiesDynamicStructureCompat.class) {
            if (lookupAttempted) {
                return serverBridge;
            }
            lookupAttempted = true;
            try {
                Class<?> serverProviderClass = Class.forName(
                        "org.valkyrienskies.mod.common.IShipObjectWorldServerProvider",
                        false,
                        ValkyrienSkiesDynamicStructureCompat.class.getClassLoader());
                Class<?> dimensionProviderClass = Class.forName(
                        "org.valkyrienskies.mod.common.util.DimensionIdProvider",
                        false,
                        ValkyrienSkiesDynamicStructureCompat.class.getClassLoader());
                Class<?> shipWorldClass = Class.forName(
                        "org.valkyrienskies.core.api.world.ServerShipWorld",
                        false,
                        ValkyrienSkiesDynamicStructureCompat.class.getClassLoader());
                Class<?> queryableShipDataClass = Class.forName(
                        "org.valkyrienskies.core.api.ships.QueryableShipData",
                        false,
                        ValkyrienSkiesDynamicStructureCompat.class.getClassLoader());
                Class<?> shipClass = Class.forName(
                        "org.valkyrienskies.core.api.ships.Ship",
                        false,
                        ValkyrienSkiesDynamicStructureCompat.class.getClassLoader());
                Class<?> matrix4dcClass = Class.forName(
                        "org.joml.Matrix4dc",
                        false,
                        ValkyrienSkiesDynamicStructureCompat.class.getClassLoader());
                Class<?> vector3dClass = Class.forName(
                        "org.joml.Vector3d",
                        false,
                        ValkyrienSkiesDynamicStructureCompat.class.getClassLoader());
                serverBridge = new ServerBridge(
                        serverProviderClass.getMethod("getShipObjectWorld"),
                        dimensionProviderClass.getMethod("getDimensionId"),
                        shipWorldClass.getMethod("getAllShips"),
                        queryableShipDataClass.getMethod("getByChunkPos", int.class, int.class, String.class),
                        shipClass.getMethod("getShipToWorld"),
                        matrix4dcClass,
                        vector3dClass.getConstructor(),
                        matrix4dcClass.getMethod("transformPosition", double.class, double.class, double.class, vector3dClass),
                        vector3dClass.getField("x"),
                        vector3dClass.getField("y"),
                        vector3dClass.getField("z"));
                return serverBridge;
            } catch (ReflectiveOperationException | LinkageError ignored) {
                return null;
            }
        }
    }

    private static boolean samePosition(Vec3 left, Vec3 right) {
        return left != null && right != null && left.distanceToSqr(right) <= 1.0E-6D;
    }

    private static void logFailure(Exception exception) {
        if (BO_Diag_compatDynamicGates()) {
            Bandwidthoptimizer.LOGGER.info("[BO:Diag:compatDynamicGates] event=valkyrienskies_create_target_failed", exception);
        }
    }

    private static boolean BO_Diag_compatDynamicGates() {
        return DiagnosticToolRegistry.isEnabled(DiagnosticToolRegistry.Tool.COMPAT_DYNAMIC_GATES);
    }

    private record ServerBridge(
            Method getShipObjectWorld,
            Method getDimensionId,
            Method getAllShips,
            Method getByChunkPos,
            Method getShipToWorld,
            Class<?> matrix4dcClass,
            Constructor<?> vector3dConstructor,
            Method transformPosition,
            Field vector3dX,
            Field vector3dY,
            Field vector3dZ
    ) {}

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
