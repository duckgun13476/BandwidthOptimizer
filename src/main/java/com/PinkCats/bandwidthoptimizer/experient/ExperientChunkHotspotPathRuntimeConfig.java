package com.PinkCats.bandwidthoptimizer.experient;

public final class ExperientChunkHotspotPathRuntimeConfig {

    public static final String ENABLED_PROPERTY = "bandwidthoptimizer.experient.chunkHotspotPathEnabled";

    private ExperientChunkHotspotPathRuntimeConfig() {}

    public static boolean isEnabled() {
        return Boolean.parseBoolean(System.getProperty(ENABLED_PROPERTY, "false"));
    }
}
