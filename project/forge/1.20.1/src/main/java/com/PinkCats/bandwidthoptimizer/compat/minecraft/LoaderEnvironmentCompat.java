package com.PinkCats.bandwidthoptimizer.compat.minecraft;

import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.fml.loading.FMLEnvironment;

public final class LoaderEnvironmentCompat {

    private LoaderEnvironmentCompat() {}

    public static String physicalSideName() {
        return FMLEnvironment.dist.name();
    }

    public static boolean isClientSide() {
        return FMLEnvironment.dist == Dist.CLIENT;
    }
}
