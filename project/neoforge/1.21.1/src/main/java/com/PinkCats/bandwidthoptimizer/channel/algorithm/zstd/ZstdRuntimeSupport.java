package com.PinkCats.bandwidthoptimizer.channel.algorithm.zstd;

import com.PinkCats.bandwidthoptimizer.Bandwidthoptimizer;
import com.PinkCats.bandwidthoptimizer.util.BandwidthOptimizerOutputPaths;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

public final class ZstdRuntimeSupport {

    private static final String TEMP_FOLDER_PROPERTY = "ZstdTempFolder";

    private ZstdRuntimeSupport() {}

    public static void configureNativeTempFolder() {
        // archive
        BandwidthOptimizerOutputPaths.LegacyMigrationResult migrationResult =
                BandwidthOptimizerOutputPaths.migrateLegacyDefaultLayout();
        if (migrationResult.changed()) {
            Bandwidthoptimizer.LOGGER.info(
                    "Migrated legacy BandwidthOptimizer output layout: movedOutputs={}, deletedLegacyFiles={}, failures={}",
                    migrationResult.movedOutputFiles(),
                    migrationResult.deletedLegacyFiles(),
                    migrationResult.failedOperations()
            );
        }

        String configured = System.getProperty(TEMP_FOLDER_PROPERTY);
        if (configured != null && !configured.isBlank()) {
            Bandwidthoptimizer.LOGGER.info("Using preconfigured zstd native temp folder: {}", configured);
            return;
        }

        Path tempFolder = BandwidthOptimizerOutputPaths.nativeDriveDirectory();
        try {
            Files.createDirectories(tempFolder);
            System.setProperty(TEMP_FOLDER_PROPERTY, tempFolder.toAbsolutePath().toString());
            Bandwidthoptimizer.LOGGER.info("Configured zstd native temp folder: {}", tempFolder.toAbsolutePath());
        } catch (IOException exception) {
            Bandwidthoptimizer.LOGGER.warn(
                    "Failed to create zstd native temp folder at {}, runtime may fall back to non-zstd algorithm.",
                    tempFolder.toAbsolutePath(),
                    exception
            );
        }
    }
}
