package com.PinkCats.bandwidthoptimizer.util;

import java.nio.file.Path;

public final class BandwidthOptimizerOutputPaths {

    public static final String OUTPUT_DIRECTORY_PROPERTY = "bandwidthoptimizer.outputDirectory";
    public static final String SHARED_OUTPUT_DIRECTORY_PROPERTY = "bandwidthoptimizer.sharedOutputDirectory";
    public static final String NATIVE_DRIVE_DIRECTORY_PROPERTY = "bandwidthoptimizer.nativeDriveDirectory";
    private static final String DEFAULT_OUTPUT_DIRECTORY = "bandwidthoptimizer-native";
    private static final String DEFAULT_NATIVE_DRIVE_DIRECTORY = "drive";

    private BandwidthOptimizerOutputPaths() {}

    public static Path outputRoot() {
        return readPathProperty(OUTPUT_DIRECTORY_PROPERTY, DEFAULT_OUTPUT_DIRECTORY);
    }

    public static Path sharedOutputRoot() {
        return readPathProperty(SHARED_OUTPUT_DIRECTORY_PROPERTY, outputRoot().toString());
    }

    public static Path nativeDriveDirectory() {
        String configured = System.getProperty(NATIVE_DRIVE_DIRECTORY_PROPERTY);
        if (configured != null && !configured.isBlank()) {
            return Path.of(configured);
        }
        return outputRoot().resolve(DEFAULT_NATIVE_DRIVE_DIRECTORY);
    }

    public static Path resolve(String first, String... more) {
        Path path = Path.of(first, more);
        return path.isAbsolute() ? path : outputRoot().resolve(path);
    }

    public static Path resolveDirectory(String directory) {
        if (directory == null || directory.isBlank()) {
            return outputRoot();
        }
        Path path = Path.of(directory);
        return path.isAbsolute() ? path : outputRoot().resolve(path);
    }

    public static Path resolveShared(String first, String... more) {
        Path path = Path.of(first, more);
        return path.isAbsolute() ? path : sharedOutputRoot().resolve(path);
    }

    private static Path readPathProperty(String propertyName, String fallback) {
        String configured = System.getProperty(propertyName);
        if (configured == null || configured.isBlank()) {
            return Path.of(fallback);
        }
        return Path.of(configured);
    }
}
