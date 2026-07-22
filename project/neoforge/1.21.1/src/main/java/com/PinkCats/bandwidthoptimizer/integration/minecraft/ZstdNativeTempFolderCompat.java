package com.PinkCats.bandwidthoptimizer.integration.minecraft;

import com.PinkCats.bandwidthoptimizer.util.BandwidthOptimizerOutputPaths;

import java.nio.file.Path;

public final class ZstdNativeTempFolderCompat {

    private ZstdNativeTempFolderCompat() {}

    public static BandwidthOptimizerOutputPaths.LegacyMigrationResult migrateLegacyDefaultLayout() {
        return BandwidthOptimizerOutputPaths.migrateLegacyDefaultLayout();
    }
    public static Path nativeTempFolder() {
        return BandwidthOptimizerOutputPaths.nativeDriveDirectory();
    }
}
