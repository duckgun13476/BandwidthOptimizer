package com.PinkCats.bandwidthoptimizer.integration.minecraft;

import com.PinkCats.bandwidthoptimizer.util.BandwidthOptimizerOutputPaths;
import net.fabricmc.loader.api.FabricLoader;

import java.nio.file.Path;

public final class ZstdNativeTempFolderCompat {

    private ZstdNativeTempFolderCompat() {}

    public static BandwidthOptimizerOutputPaths.LegacyMigrationResult migrateLegacyDefaultLayout() {
        return BandwidthOptimizerOutputPaths.LegacyMigrationResult.empty();
    }


    public static Path nativeTempFolder() {
        return FabricLoader.getInstance().getGameDir().resolve("bandwidthoptimizer-native");
    }
}
