package com.PinkCats.bandwidthoptimizer.integration.sable;

import net.minecraft.world.phys.Vec3;
import java.util.function.UnaryOperator;

/** Adapted from chidayuyu-stack/BandwidthSableFix, BearingTargetProjection.java.
 * https://github.com/chidayuyu-stack/BandwidthSableFix
 * The pose callback preserves BO's optional Sable dependency across loaders.
 */
public final class BearingTargetProjection {
    private BearingTargetProjection() {}

    public static boolean supports(String typeKey) {
        return "create:mechanical_bearing".equals(typeKey);
    }

    public static Target project(String typeKey, UnaryOperator<Vec3> pose, Vec3 center, Vec3[] points) {
        if (!supports(typeKey) || pose == null) {
            return new Target(center, points);
        }
        // Use the controller's pose even for corners outside its plot. Keep the oriented
        // corners: rebuilding a world AABB would unnecessarily enlarge the view test.
        Vec3[] worldPoints = new Vec3[points.length];
        for (int index = 0; index < points.length; index++) {
            worldPoints[index] = pose.apply(points[index]);
        }
        return new Target(pose.apply(center), worldPoints);
    }

    public record Target(Vec3 center, Vec3[] points) {}
}
