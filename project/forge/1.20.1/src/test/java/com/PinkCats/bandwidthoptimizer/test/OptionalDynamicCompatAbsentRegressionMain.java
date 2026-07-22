package com.PinkCats.bandwidthoptimizer.test;

import com.PinkCats.bandwidthoptimizer.integration.sable.SableDynamicStructureCompat;
import com.PinkCats.bandwidthoptimizer.integration.valkyrienskies.ValkyrienSkiesDynamicStructureCompat;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.phys.Vec3;

import java.lang.reflect.Method;

public final class OptionalDynamicCompatAbsentRegressionMain {

    private OptionalDynamicCompatAbsentRegressionMain() {}

    public static void main(String[] args) throws Exception {
        require(!isClassPresent("dev.ryanhcode.sable.Sable"), "Sable is unexpectedly present in the bare test classpath");
        require(!isClassPresent("org.valkyrienskies.mod.common.IShipObjectWorldServerProvider"), "Valkyrien Skies is unexpectedly present in the bare test classpath");

        require(invokePrivateStatic("com.PinkCats.bandwidthoptimizer.integration.sable.SableDynamicStructureCompat", "projectOutMethod") == null,
                "Sable lazy lookup should stay empty when Sable is absent");
        require(invokePrivateStatic("com.PinkCats.bandwidthoptimizer.integration.valkyrienskies.ValkyrienSkiesDynamicStructureCompat", "serverBridge") == null,
                "Valkyrien Skies lazy lookup should stay empty when Valkyrien Skies is absent");

        Vec3 fallback = new Vec3(1.0D, 2.0D, 3.0D);
        SableDynamicStructureCompat.DynamicTarget sableTarget = SableDynamicStructureCompat.resolveTarget(null, null, fallback);
        require(sableTarget.target() == fallback && !sableTarget.transformed() && !sableTarget.forceImmediate(),
                "Sable absent target should be vanilla");

        ValkyrienSkiesDynamicStructureCompat.DynamicTarget valkyrienTarget =
                ValkyrienSkiesDynamicStructureCompat.resolveTarget(null, null, fallback);
        require(valkyrienTarget.target() == fallback && !valkyrienTarget.transformed() && !valkyrienTarget.forceImmediate(),
                "Valkyrien Skies absent target should be vanilla");

        for (String dynamicController : new String[] {
                "mechanical_piston",
                "windmill_bearing",
                "mechanical_bearing",
                "clockwork_bearing",
                "rope_pulley",
                "hose_pulley",
                "elevator_pulley",
                "gantry_shaft",
                "gantry_pinion",
                "cart_assembler",
                "contraption_controls"
        }) {
            require(shouldGateCreateBlockEntity(dynamicController, false),
                    "Create dynamic structure controllers must use entity-aware gate: " + dynamicController);
            require(shouldGateCreateBlockEntity(dynamicController, true),
                    "Create dynamic structure controllers must use entity-aware bootstrap gate: " + dynamicController);
        }
        require(shouldGateCreateBlockEntity("belt", false),
                "Create mechanical block entities should still use the delayed gate");
        require(shouldGateCreateBlockEntity("belt", true),
                "Create mechanical block entities should still use the bootstrap gate");
        require(shouldGateCreateBlockEntity("display_link", false),
                "Non-mechanical Create block entities should use the shared normal delayed gate");
        require(shouldGateCreateBlockEntity("display_link", true),
                "Non-mechanical Create block entities should still use the bootstrap gate");
        require(isAnyPointInImmediateView(
                        new Vec3(0.0D, 0.0D, 0.0D),
                        new Vec3(0.0D, 0.0D, 1.0D),
                        new Vec3[] {new Vec3(-20.0D, 0.0D, 20.0D), new Vec3(0.0D, 0.0D, 20.0D)},
                        0.0D,
                        true,
                        0.99D),
                "Create visibility gate should release when any block-entity corner enters view");
        require(!isAnyPointInImmediateView(
                        new Vec3(0.0D, 0.0D, 0.0D),
                        new Vec3(0.0D, 0.0D, 1.0D),
                        new Vec3[] {new Vec3(0.0D, 0.0D, 20.0D)},
                        0.0D,
                        false,
                        0.99D),
                "Create bootstrap gate should not release from look direction alone");

        System.out.println("Optional dynamic compat absent regression matched");
    }

    private static Object invokePrivateStatic(String className, String methodName) throws Exception {
        Class<?> targetClass = Class.forName(className);
        Method method = targetClass.getDeclaredMethod(methodName);
        method.setAccessible(true);
        return method.invoke(null);
    }

    private static boolean shouldGateCreateBlockEntity(String path, boolean chunkBootstrapActive) throws Exception {
        Class<?> targetClass = Class.forName("com.PinkCats.bandwidthoptimizer.gate.integration.create.CreateBlockEntityUpdateGate");
        Method method = targetClass.getDeclaredMethod(
                "shouldGateCreateBlockEntity",
                ResourceLocation.class,
                boolean.class
        );
        method.setAccessible(true);
        ResourceLocation typeKey = ResourceLocation.tryParse("create:" + path);
        require(typeKey != null, "Create block entity type key should parse: " + path);
        return (Boolean) method.invoke(null, typeKey, chunkBootstrapActive);
    }

    private static boolean isAnyPointInImmediateView(
            Vec3 eyePosition,
            Vec3 lookAngle,
            Vec3[] points,
            double nearDistance,
            boolean allowLookDirection,
            double dotThreshold
    ) throws Exception {
        Class<?> targetClass = Class.forName("com.PinkCats.bandwidthoptimizer.gate.integration.create.CreateBlockEntityUpdateGate");
        Method method = targetClass.getDeclaredMethod(
                "isAnyPointInImmediateView",
                Vec3.class,
                Vec3.class,
                Vec3[].class,
                double.class,
                boolean.class,
                double.class
        );
        method.setAccessible(true);
        return (Boolean) method.invoke(null, eyePosition, lookAngle, points, nearDistance, allowLookDirection, dotThreshold);
    }

    private static boolean isClassPresent(String className) {
        try {
            Class.forName(className);
            return true;
        } catch (ClassNotFoundException | LinkageError ignored) {
            return false;
        }
    }

    private static void require(boolean condition, String message) {
        if (!condition) {
            throw new IllegalStateException(message);
        }
    }
}
