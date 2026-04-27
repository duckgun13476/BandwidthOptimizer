package com.PinkCats.bandwidthoptimizer.experient;

public final class ExperientChunkHotspotPathRuntimeConfig {

    public static final String ENABLED_PROPERTY = "bandwidthoptimizer.experient.chunkHotspotPathEnabled";
    public static final String STOP_AFTER_SECTION_PROPERTY = "bandwidthoptimizer.experient.chunkHotspotPathStopAfterSection";
    public static final String STOP_AFTER_BLOCK_ENTITY_PROPERTY = "bandwidthoptimizer.experient.chunkHotspotPathStopAfterBlockEntity";
    public static final String TWO_POINT_REUSE_MODE_PROPERTY = "bandwidthoptimizer.experient.chunkHotspotPathTwoPointReuseMode";
    public static final String BOUNDARY_HOP_MODE_PROPERTY = "bandwidthoptimizer.experient.chunkHotspotPathBoundaryHopMode";
    public static final String DIMENSION_HOP_MODE_PROPERTY = "bandwidthoptimizer.experient.chunkHotspotPathDimensionHopMode";
    public static final String RANGE_BOUNCE_MODE_PROPERTY = "bandwidthoptimizer.experient.chunkHotspotPathRangeBounceMode";
    public static final String RANGE_START_X_PROPERTY = "bandwidthoptimizer.experient.chunkHotspotPathRangeStartX";
    public static final String RANGE_START_Y_PROPERTY = "bandwidthoptimizer.experient.chunkHotspotPathRangeStartY";
    public static final String RANGE_START_Z_PROPERTY = "bandwidthoptimizer.experient.chunkHotspotPathRangeStartZ";
    public static final String RANGE_END_X_PROPERTY = "bandwidthoptimizer.experient.chunkHotspotPathRangeEndX";
    public static final String RANGE_END_Y_PROPERTY = "bandwidthoptimizer.experient.chunkHotspotPathRangeEndY";
    public static final String RANGE_END_Z_PROPERTY = "bandwidthoptimizer.experient.chunkHotspotPathRangeEndZ";
    public static final String RANGE_STEP_BLOCKS_PROPERTY = "bandwidthoptimizer.experient.chunkHotspotPathRangeStepBlocks";
    public static final String RANGE_ROUND_TRIPS_PROPERTY = "bandwidthoptimizer.experient.chunkHotspotPathRangeRoundTrips";
    private static final double DEFAULT_RANGE_START_X = 28.0D;
    private static final double DEFAULT_RANGE_START_Y = 182.0D;
    private static final double DEFAULT_RANGE_START_Z = -364.0D;
    private static final double DEFAULT_RANGE_END_X = 26.0D;
    private static final double DEFAULT_RANGE_END_Y = 200.0D;
    private static final double DEFAULT_RANGE_END_Z = -281.0D;
    private static final double DEFAULT_RANGE_STEP_BLOCKS = 16.0D;
    private static final int DEFAULT_RANGE_ROUND_TRIPS = 3;

    private ExperientChunkHotspotPathRuntimeConfig() {}

    public static boolean isEnabled() {
        return Boolean.parseBoolean(System.getProperty(ENABLED_PROPERTY, "false"));
    }

    public static boolean shouldStopAfterSection() {
        return Boolean.parseBoolean(System.getProperty(STOP_AFTER_SECTION_PROPERTY, "false"));
    }

    // block entity test pulse
    public static boolean shouldStopAfterBlockEntity() {
        return Boolean.parseBoolean(System.getProperty(STOP_AFTER_BLOCK_ENTITY_PROPERTY, "false"));
    }

    // skip chunk TP。
    public static boolean isTwoPointReuseMode() {
        return Boolean.parseBoolean(System.getProperty(TWO_POINT_REUSE_MODE_PROPERTY, "false"));
    }

    // hop in chunk border
    public static boolean isBoundaryHopMode() {
        return Boolean.parseBoolean(System.getProperty(BOUNDARY_HOP_MODE_PROPERTY, "false"));
    }

    // hop dimension
    public static boolean isDimensionHopMode() {
        return Boolean.parseBoolean(System.getProperty(DIMENSION_HOP_MODE_PROPERTY, "false"));
    }

    // move switch
    public static boolean isRangeBounceMode() {
        return Boolean.parseBoolean(System.getProperty(RANGE_BOUNCE_MODE_PROPERTY, "false"));
    }


    public static double rangeStartX() {
        return readDoubleProperty(RANGE_START_X_PROPERTY, DEFAULT_RANGE_START_X);
    }

    public static double rangeStartY() {
        return readDoubleProperty(RANGE_START_Y_PROPERTY, DEFAULT_RANGE_START_Y);
    }

    public static double rangeStartZ() {
        return readDoubleProperty(RANGE_START_Z_PROPERTY, DEFAULT_RANGE_START_Z);
    }

    public static double rangeEndX() {
        return readDoubleProperty(RANGE_END_X_PROPERTY, DEFAULT_RANGE_END_X);
    }

    public static double rangeEndY() {
        return readDoubleProperty(RANGE_END_Y_PROPERTY, DEFAULT_RANGE_END_Y);
    }

    public static double rangeEndZ() {
        return readDoubleProperty(RANGE_END_Z_PROPERTY, DEFAULT_RANGE_END_Z);
    }


    public static double rangeStepBlocks() {
        return Math.max(readDoubleProperty(RANGE_STEP_BLOCKS_PROPERTY, DEFAULT_RANGE_STEP_BLOCKS), 1.0D);
    }

    public static int rangeRoundTrips() {
        return Math.max(readIntProperty(RANGE_ROUND_TRIPS_PROPERTY, DEFAULT_RANGE_ROUND_TRIPS), 1);
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
