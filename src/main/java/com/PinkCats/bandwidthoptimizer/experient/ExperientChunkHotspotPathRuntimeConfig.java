package com.PinkCats.bandwidthoptimizer.experient;

public final class ExperientChunkHotspotPathRuntimeConfig {

    public static final String ENABLED_PROPERTY = "bandwidthoptimizer.experient.chunkHotspotPathEnabled";
    public static final String STOP_AFTER_SECTION_PROPERTY = "bandwidthoptimizer.experient.chunkHotspotPathStopAfterSection";
    public static final String STOP_AFTER_BLOCK_ENTITY_PROPERTY = "bandwidthoptimizer.experient.chunkHotspotPathStopAfterBlockEntity";
    public static final String TWO_POINT_REUSE_MODE_PROPERTY = "bandwidthoptimizer.experient.chunkHotspotPathTwoPointReuseMode";

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
}
