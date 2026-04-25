package com.PinkCats.bandwidthoptimizer.experient;

public final class ExperientChunkHotspotPathRuntimeConfig {

    public static final String ENABLED_PROPERTY = "bandwidthoptimizer.experient.chunkHotspotPathEnabled";
    public static final String STOP_AFTER_SECTION_PROPERTY = "bandwidthoptimizer.experient.chunkHotspotPathStopAfterSection";
    public static final String STOP_AFTER_BLOCK_ENTITY_PROPERTY = "bandwidthoptimizer.experient.chunkHotspotPathStopAfterBlockEntity";

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
}
