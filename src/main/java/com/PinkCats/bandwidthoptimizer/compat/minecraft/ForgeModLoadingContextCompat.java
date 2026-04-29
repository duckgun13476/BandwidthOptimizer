package com.PinkCats.bandwidthoptimizer.compat.minecraft;

import net.minecraftforge.fml.ModLoadingContext;

public final class ForgeModLoadingContextCompat {

    private ForgeModLoadingContextCompat() {}

    public static ModLoadingContext getCurrentModLoadingContext() {
        ThreadLocal<?> contextThreadLocal = MinecraftReflectionCompat.readStaticTypedField(
                ModLoadingContext.class,
                ThreadLocal.class,
                "context"
        );
        if (contextThreadLocal == null) {
            throw new IllegalStateException("Bandwidth Optimizer failed to locate ModLoadingContext.context");
        }

        Object context = contextThreadLocal.get();
        if (context instanceof ModLoadingContext modLoadingContext) {
            return modLoadingContext;
        }

        throw new IllegalStateException("Bandwidth Optimizer failed to resolve current ModLoadingContext");
    }
}
