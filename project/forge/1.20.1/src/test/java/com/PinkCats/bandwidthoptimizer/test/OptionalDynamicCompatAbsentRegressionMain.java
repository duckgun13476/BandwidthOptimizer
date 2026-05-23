package com.PinkCats.bandwidthoptimizer.test;

import com.PinkCats.bandwidthoptimizer.compat.sable.SableDynamicStructureCompat;
import com.PinkCats.bandwidthoptimizer.compat.valkyrienskies.ValkyrienSkiesDynamicStructureCompat;
import net.minecraft.world.phys.Vec3;

import java.lang.reflect.Method;

public final class OptionalDynamicCompatAbsentRegressionMain {

    private OptionalDynamicCompatAbsentRegressionMain() {}

    public static void main(String[] args) throws Exception {
        require(!isClassPresent("dev.ryanhcode.sable.Sable"), "Sable is unexpectedly present in the bare test classpath");
        require(!isClassPresent("org.valkyrienskies.mod.common.VSGameUtilsKt"), "Valkyrien Skies is unexpectedly present in the bare test classpath");

        require(invokePrivateStatic("com.PinkCats.bandwidthoptimizer.compat.sable.SableDynamicStructureCompat", "projectOutMethod") == null,
                "Sable lazy lookup should stay empty when Sable is absent");
        require(invokePrivateStatic("com.PinkCats.bandwidthoptimizer.compat.valkyrienskies.ValkyrienSkiesDynamicStructureCompat", "toWorldCoordinatesMethod") == null,
                "Valkyrien Skies lazy lookup should stay empty when Valkyrien Skies is absent");

        Vec3 fallback = new Vec3(1.0D, 2.0D, 3.0D);
        SableDynamicStructureCompat.DynamicTarget sableTarget = SableDynamicStructureCompat.resolveTarget(null, null, fallback);
        require(sableTarget.target() == fallback && !sableTarget.transformed() && !sableTarget.forceImmediate(),
                "Sable absent target should be vanilla");

        ValkyrienSkiesDynamicStructureCompat.DynamicTarget valkyrienTarget =
                ValkyrienSkiesDynamicStructureCompat.resolveTarget(null, null, fallback);
        require(valkyrienTarget.target() == fallback && !valkyrienTarget.transformed() && !valkyrienTarget.forceImmediate(),
                "Valkyrien Skies absent target should be vanilla");

        System.out.println("Optional dynamic compat absent regression matched");
    }

    private static Object invokePrivateStatic(String className, String methodName) throws Exception {
        Class<?> targetClass = Class.forName(className);
        Method method = targetClass.getDeclaredMethod(methodName);
        method.setAccessible(true);
        return method.invoke(null);
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
