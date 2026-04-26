package com.PinkCats.bandwidthoptimizer.chunk.integration.transport;

public final class ChunkTransportRuntimeConfig {

    public static final String ENABLED_PROPERTY = "bandwidthoptimizer.experimentalChunkHotspotTransport";

    private ChunkTransportRuntimeConfig() {}

    public static boolean isEnabled() {
        return Boolean.parseBoolean(System.getProperty(ENABLED_PROPERTY, "true"));
    }
}
