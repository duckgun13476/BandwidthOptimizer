package com.PinkCats.bandwidthoptimizer.compat.minecraft;

import net.minecraftforge.fml.ModLoadingContext;

import java.lang.reflect.Field;

public final class ForgeModLoadingContextCompat {

    private ForgeModLoadingContextCompat() {}

    public static ModLoadingContext getCurrentModLoadingContext() {
        ThreadLocal<?> contextThreadLocal = readContextThreadLocal();
        if (contextThreadLocal == null) {
            throw new IllegalStateException("Bandwidth Optimizer failed to locate ModLoadingContext.context");
        }

        Object context = contextThreadLocal.get();
        if (context instanceof ModLoadingContext modLoadingContext) {
            return modLoadingContext;
        }

        throw new IllegalStateException("Bandwidth Optimizer failed to resolve current ModLoadingContext");
    }

    private static ThreadLocal<?> readContextThreadLocal() {
        try {
            Field contextField = ModLoadingContext.class.getDeclaredField("context");
            contextField.setAccessible(true);
            Object value = contextField.get(null);
            if (value instanceof ThreadLocal<?> contextThreadLocal) {
                return contextThreadLocal;
            }
            return null;
        } catch (ReflectiveOperationException exception) {
            throw new IllegalStateException("Bandwidth Optimizer failed to read ModLoadingContext.context", exception);
        }
    }
}
