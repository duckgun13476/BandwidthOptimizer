package com.PinkCats.bandwidthoptimizer.compat.minecraft;

import net.fabricmc.api.EnvType;
import net.fabricmc.loader.api.FabricLoader;

public final class LoaderEnvironmentCompat {

    private LoaderEnvironmentCompat() {}

    public static String physicalSideName() {
        return FabricLoader.getInstance().getEnvironmentType().name();
    }

    public static boolean isClientSide() {
        return FabricLoader.getInstance().getEnvironmentType() == EnvType.CLIENT;
    }
}
