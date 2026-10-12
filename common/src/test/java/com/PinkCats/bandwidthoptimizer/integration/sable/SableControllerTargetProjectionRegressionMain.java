package com.PinkCats.bandwidthoptimizer.integration.sable;

import net.minecraft.world.phys.Vec3;

public final class SableControllerTargetProjectionRegressionMain {
    public static void main(String[] args) throws Exception {
        for (String type : new String[]{"create:windmill_bearing", "create:clockwork_bearing",
                "create:mechanical_piston", "other:mechanical_bearing"}) {
            Vec3 center = new Vec3(1, 2, 3);
            Vec3[] points = {center};
            var untouched = BearingTargetProjection.project(type, point -> { throw new AssertionError("Out of scope"); }, center, points);
            require(!BearingTargetProjection.supports(type) && untouched.center() == center && untouched.points() == points,
                    "Other controller types changed");
        }
        Companion companion = new Companion();
        var access = new SableControllerTargetProjection.Access(true, companion,
                Companion.class.getMethod("containing", Object.class),
                SubLevel.class.getMethod("pose"), Pose.class.getMethod("transform", Vec3.class));
        Vec3 center = new Vec3(10000, 50, 20000);
        Vec3[] points = new Vec3[8];
        for (int index = 0; index < 8; index++) {
            points[index] = center.add((index & 1) == 0 ? -20 : 20,
                    (index & 2) == 0 ? -2 : 2, (index & 4) == 0 ? -40 : 40);
        }
        Vec3[] original = points.clone();
        Object controller = new Object();
        var first = SableControllerTargetProjection.project(controller, center, points, access);
        require(!first.forceImmediate(), "Valid pose should retain visibility gating");
        equal(first.center(), new Vec3(8, 4, 12));
        for (int index = 0; index < 8; index++) {
            Vec3 delta = points[index].subtract(center);
            equal(first.points()[index], new Vec3(8 - 2 * delta.z, 4 + 2 * delta.y, 12 + 2 * delta.x));
            require(points[index] == original[index], "Input corner changed");
        }
        require(companion.lookups == 1 && companion.level.poseReads == 1,
                "Samples must use one controller and one pose");
        companion.level.current.offset = 100;
        var refreshed = SableControllerTargetProjection.project(controller, center, points, access);
        equal(refreshed.center(), new Vec3(108, 4, 12));
        require(companion.lookups == 2 && companion.level.poseReads == 2, "Pose must refresh per update");
        companion.level.current.fail = true;
        require(SableControllerTargetProjection.project(controller, center, points, access).forceImmediate(),
                "API failure must pass through");
        companion.level.current.fail = false;
        companion.level.current.offset = Double.NaN;
        require(SableControllerTargetProjection.project(controller, center, points, access).forceImmediate(),
                "Invalid coordinates must pass through");
        companion.level = null;
        var ground = SableControllerTargetProjection.project(controller, center, points, access);
        require(!ground.forceImmediate() && ground.center() == center && ground.points() == points,
                "Ground target changed");
        var absent = new SableControllerTargetProjection.Access(false, null, null, null, null);
        require(!SableControllerTargetProjection.project(controller, center, points, absent).forceImmediate(),
                "Absent Sable should preserve gating");
        var unsupported = new SableControllerTargetProjection.Access(true, null, null, null, null);
        require(SableControllerTargetProjection.project(controller, center, points, unsupported).forceImmediate(),
                "Installed unsupported Sable must pass through");
        if (Boolean.getBoolean("boSableRealPoseTest")) verifyOriginalPoseScenarios();
        System.out.println("Sable controller projection regression passed: all corners, rotation/scale, refresh, input ownership, ground, absent, failure");
    }

    /** Adapted from kalium's BandwidthSableFix BearingTargetProjectionTest. Uses real Pose3d. */
    private static void verifyOriginalPoseScenarios() throws Exception {
        Class<?> poseType = Class.forName("dev.ryanhcode.sable.companion.math.Pose3d");
        Class<?> poseApi = Class.forName("dev.ryanhcode.sable.companion.math.Pose3dc");
        Object pose = poseType.getConstructor().newInstance();
        Vec3 plotCenter = new Vec3(30_000_000, 64, 30_000_000);
        Object rotationPoint = poseType.getMethod("rotationPoint").invoke(pose);
        rotationPoint.getClass().getMethod("set", double.class, double.class, double.class)
                .invoke(rotationPoint, plotCenter.x, plotCenter.y, plotCenter.z);
        Object position = poseType.getMethod("position").invoke(pose);
        position.getClass().getMethod("set", double.class, double.class, double.class).invoke(position, 12d, 70d, -8d);
        Vec3[] samples = new Vec3[9];
        int index = 0;
        for (int x : new int[]{-2, 2}) for (int y : new int[]{-1, 1}) for (int z : new int[]{-3, 3}) {
            samples[index++] = plotCenter.add(x, y, z);
        }
        samples[8] = plotCenter;
        Vec3[] original = samples.clone();
        var transform = poseApi.getMethod("transformPosition", Vec3.class);
        java.util.function.UnaryOperator<Vec3> projection = point -> {
            try { return (Vec3) transform.invoke(pose, point); }
            catch (ReflectiveOperationException exception) { throw new IllegalStateException(exception); }
        };
        var first = BearingTargetProjection.project("create:mechanical_bearing", projection, plotCenter, samples);
        equal(first.center(), new Vec3(12, 70, -8));
        equal(first.points()[0], new Vec3(10, 69, -11));
        Vec3 eye = new Vec3(12, 72, -4);
        require(eye.distanceToSqr(plotCenter) > 64 && eye.distanceToSqr(first.center()) < 64,
                "Original near-player storage-space reproduction failed");
        Object orientation = poseType.getMethod("orientation").invoke(pose);
        orientation.getClass().getMethod("rotationY", double.class).invoke(orientation, Math.PI / 4);
        Object scale = poseType.getMethod("scale").invoke(pose);
        scale.getClass().getMethod("set", double.class).invoke(scale, 2d);
        var rotated = BearingTargetProjection.project("create:mechanical_bearing", projection, plotCenter, samples);
        double diagonal = Math.sqrt(0.5);
        for (index = 0; index < samples.length; index++) {
            Vec3 delta = samples[index].subtract(plotCenter).scale(2);
            equal(rotated.points()[index], new Vec3(12 + diagonal * (delta.x + delta.z),
                    70 + delta.y, -8 + diagonal * (delta.z - delta.x)));
            require(samples[index] == original[index], "Original geometry was mutated");
        }
        scale.getClass().getMethod("set", double.class).invoke(scale, 1d);
        position.getClass().getMethod("add", double.class, double.class, double.class).invoke(position, 100d, 0d, 0d);
        orientation.getClass().getMethod("rotationY", double.class).invoke(orientation, Math.PI / 2);
        var refreshed = BearingTargetProjection.project("create:mechanical_bearing", projection, plotCenter, samples);
        equal(refreshed.center(), new Vec3(112, 70, -8));
        equal(refreshed.points()[0], new Vec3(109, 69, -6));
        equal(first.center(), new Vec3(12, 70, -8));
        System.out.println("Original BandwidthSableFix scenarios passed against real Sable Companion 1.6.0 Pose3d");
    }

    public static final class Companion {
        int lookups;
        SubLevel level = new SubLevel();
        public SubLevel containing(Object controller) { lookups++; return level; }
    }
    public static final class SubLevel {
        int poseReads;
        Pose current = new Pose();
        public Pose pose() { poseReads++; return current; }
    }
    public static final class Pose {
        double offset;
        boolean fail;
        public Vec3 transform(Vec3 point) {
            if (fail) throw new IllegalStateException("Injected API failure");
            return new Vec3(8 + offset - 2 * (point.z - 20000),
                    4 + 2 * (point.y - 50), 12 + 2 * (point.x - 10000));
        }
    }
    private static void equal(Vec3 actual, Vec3 expected) {
        require(actual.distanceToSqr(expected) < 1e-12, "Incorrect transformed point: " + actual);
    }
    private static void require(boolean condition, String message) {
        if (!condition) throw new AssertionError(message);
    }
}
