package com.PinkCats.bandwidthoptimizer.experient;

import com.PinkCats.bandwidthoptimizer.Config;

public final class ExperientChunkHotspotPathRuntimeConfig {

    private ExperientChunkHotspotPathRuntimeConfig() {}

    public static boolean isEnabled() {
        return readBooleanProperty(Config.RuntimeProperty.Experient.CHUNK_HOTSPOT_PATH_ENABLED);
    }

    public static boolean shouldStopAfterSection() {
        return readBooleanProperty(Config.RuntimeProperty.Experient.CHUNK_HOTSPOT_PATH_STOP_AFTER_SECTION);
    }

    // block entity test pulse
    public static boolean shouldStopAfterBlockEntity() {
        return readBooleanProperty(Config.RuntimeProperty.Experient.CHUNK_HOTSPOT_PATH_STOP_AFTER_BLOCK_ENTITY);
    }

    // skip chunk TP
    public static boolean isTwoPointReuseMode() {
        return readBooleanProperty(Config.RuntimeProperty.Experient.CHUNK_HOTSPOT_PATH_TWO_POINT_REUSE_MODE);
    }

    // hop in chunk border
    public static boolean isBoundaryHopMode() {
        return readBooleanProperty(Config.RuntimeProperty.Experient.CHUNK_HOTSPOT_PATH_BOUNDARY_HOP_MODE);
    }

    // hop dimension
    public static boolean isDimensionHopMode() {
        return readBooleanProperty(Config.RuntimeProperty.Experient.CHUNK_HOTSPOT_PATH_DIMENSION_HOP_MODE);
    }

    // move switch
    public static boolean isRangeBounceMode() {
        return readBooleanProperty(Config.RuntimeProperty.Experient.CHUNK_HOTSPOT_PATH_RANGE_BOUNCE_MODE);
    }

    public static double rangeStartX() {
        return readDoubleProperty(
                Config.RuntimeProperty.Experient.CHUNK_HOTSPOT_PATH_RANGE_START_X,
                Config.RuntimeProperty.Experient.DEFAULT_CHUNK_HOTSPOT_PATH_RANGE_START_X
        );
    }

    public static double rangeStartY() {
        return readDoubleProperty(
                Config.RuntimeProperty.Experient.CHUNK_HOTSPOT_PATH_RANGE_START_Y,
                Config.RuntimeProperty.Experient.DEFAULT_CHUNK_HOTSPOT_PATH_RANGE_START_Y
        );
    }

    public static double rangeStartZ() {
        return readDoubleProperty(
                Config.RuntimeProperty.Experient.CHUNK_HOTSPOT_PATH_RANGE_START_Z,
                Config.RuntimeProperty.Experient.DEFAULT_CHUNK_HOTSPOT_PATH_RANGE_START_Z
        );
    }

    public static double rangeEndX() {
        return readDoubleProperty(
                Config.RuntimeProperty.Experient.CHUNK_HOTSPOT_PATH_RANGE_END_X,
                Config.RuntimeProperty.Experient.DEFAULT_CHUNK_HOTSPOT_PATH_RANGE_END_X
        );
    }

    public static double rangeEndY() {
        return readDoubleProperty(
                Config.RuntimeProperty.Experient.CHUNK_HOTSPOT_PATH_RANGE_END_Y,
                Config.RuntimeProperty.Experient.DEFAULT_CHUNK_HOTSPOT_PATH_RANGE_END_Y
        );
    }

    public static double rangeEndZ() {
        return readDoubleProperty(
                Config.RuntimeProperty.Experient.CHUNK_HOTSPOT_PATH_RANGE_END_Z,
                Config.RuntimeProperty.Experient.DEFAULT_CHUNK_HOTSPOT_PATH_RANGE_END_Z
        );
    }

    public static double rangeStepBlocks() {
        return Math.max(
                readDoubleProperty(
                        Config.RuntimeProperty.Experient.CHUNK_HOTSPOT_PATH_RANGE_STEP_BLOCKS,
                        Config.RuntimeProperty.Experient.DEFAULT_CHUNK_HOTSPOT_PATH_RANGE_STEP_BLOCKS
                ),
                1.0D
        );
    }

    public static int rangeRoundTrips() {
        return Math.max(
                readIntProperty(
                        Config.RuntimeProperty.Experient.CHUNK_HOTSPOT_PATH_RANGE_ROUND_TRIPS,
                        Config.RuntimeProperty.Experient.DEFAULT_CHUNK_HOTSPOT_PATH_RANGE_ROUND_TRIPS
                ),
                1
        );
    }

    private static boolean readBooleanProperty(String propertyName) {
        return Boolean.parseBoolean(
                System.getProperty(
                        propertyName,
                        Boolean.toString(Config.RuntimeProperty.Experient.DEFAULT_ENABLED)
                )
        );
    }

    private static double readDoubleProperty(String propertyName, double defaultValue) {
        String propertyValue = System.getProperty(propertyName);
        if (propertyValue == null || propertyValue.isBlank()) {
            return defaultValue;
        }

        try {
            return Double.parseDouble(propertyValue);
        } catch (NumberFormatException ignored) {
            return defaultValue;
        }
    }

    private static int readIntProperty(String propertyName, int defaultValue) {
        String propertyValue = System.getProperty(propertyName);
        if (propertyValue == null || propertyValue.isBlank()) {
            return defaultValue;
        }

        try {
            return Integer.parseInt(propertyValue);
        } catch (NumberFormatException ignored) {
            return defaultValue;
        }
    }
}
