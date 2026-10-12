package com.PinkCats.bandwidthoptimizer.integration.sable;

import com.PinkCats.bandwidthoptimizer.integration.minecraft.ServerPlayerLevelCompat;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.phys.Vec3;

import java.lang.reflect.Method;

/** Projects all samples through the controller's pose, including corners outside its plot. */
public final class SableControllerTargetProjection {
    private static final Access ACCESS = discover();

    private SableControllerTargetProjection() {}

    public static Target resolve(ServerPlayer player, BlockPos pos, String typeKey, Vec3 center, Vec3[] points) {
        if (!BearingTargetProjection.supports(typeKey)) return new Target(center, points, false);
        if (!ACCESS.present()) return new Target(center, points, false);
        var level = player == null ? null : ServerPlayerLevelCompat.serverLevel(player);
        if (level == null || pos == null || level.getServer() == null || !level.getServer().isSameThread()) {
            return new Target(center, points, true);
        }
        return project(level.getBlockEntity(pos), center, points, ACCESS);
    }

    static Target project(Object controller, Vec3 center, Vec3[] points, Access access) {
        if (!access.present()) return new Target(center, points, false);
        if (controller == null || access.companion() == null) return new Target(center, points, true);
        try {
            Object subLevel = access.containing().invoke(access.companion(), controller);
            if (subLevel == null) return new Target(center, points, false);
            // Resolve once per update. Do not independently look up a sublevel for each corner.
            Object pose = access.pose().invoke(subLevel);
            if (pose == null) return new Target(center, points, true);
            BearingTargetProjection.Target projected = BearingTargetProjection.project(
                    "create:mechanical_bearing", point -> transform(access, pose, point), center, points);
            Vec3 projectedCenter = projected.center();
            Vec3[] projectedPoints = projected.points();
            if (!finite(projectedCenter)) return new Target(center, points, true);
            for (int index = 0; index < points.length; index++) {
                if (!finite(projectedPoints[index])) return new Target(center, points, true);
            }
            return new Target(projectedCenter, projectedPoints, false);
        } catch (ReflectiveOperationException | LinkageError | RuntimeException unsupported) {
            // Incorrect visibility must never withhold a controller update.
            return new Target(center, points, true);
        }
    }

    private static Vec3 transform(Access access, Object pose, Vec3 point) {
        try {
            return (Vec3) access.transform().invoke(pose, point);
        } catch (ReflectiveOperationException exception) {
            throw new IllegalStateException("Sable pose projection failed", exception);
        }
    }

    private static boolean finite(Vec3 point) {
        return point != null && Double.isFinite(point.x) && Double.isFinite(point.y) && Double.isFinite(point.z);
    }

    private static Access discover() {
        try {
            Class<?> companion = Class.forName("dev.ryanhcode.sable.companion.SableCompanion");
            Class<?> subLevel = Class.forName("dev.ryanhcode.sable.companion.SubLevelAccess");
            Class<?> pose = Class.forName("dev.ryanhcode.sable.companion.math.Pose3dc");
            return new Access(true, companion.getField("INSTANCE").get(null),
                    companion.getMethod("getContaining", BlockEntity.class),
                    subLevel.getMethod("logicalPose"), pose.getMethod("transformPosition", Vec3.class));
        } catch (ClassNotFoundException absentOrUnsupported) {
            try {
                Class.forName("dev.ryanhcode.sable.Sable");
                return new Access(true, null, null, null, null);
            } catch (ClassNotFoundException absent) {
                return new Access(false, null, null, null, null);
            } catch (LinkageError | RuntimeException unsupported) {
                return new Access(true, null, null, null, null);
            }
        } catch (ReflectiveOperationException | LinkageError | RuntimeException unsupported) {
            return new Access(true, null, null, null, null);
        }
    }

    record Access(boolean present, Object companion, Method containing, Method pose, Method transform) {}
    public record Target(Vec3 center, Vec3[] points, boolean forceImmediate) {}
}
