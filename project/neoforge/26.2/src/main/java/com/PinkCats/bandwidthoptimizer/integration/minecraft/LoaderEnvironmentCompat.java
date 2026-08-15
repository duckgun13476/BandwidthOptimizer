package com.PinkCats.bandwidthoptimizer.integration.minecraft;

import net.neoforged.api.distmarker.Dist;
import net.neoforged.fml.loading.FMLEnvironment;

public final class LoaderEnvironmentCompat {

    private LoaderEnvironmentCompat() {}

    public static String physicalSideName() {
        return FMLEnvironment.getDist().name();
    }

    public static boolean isClientSide() {
        return FMLEnvironment.getDist() == Dist.CLIENT;
    }
}
