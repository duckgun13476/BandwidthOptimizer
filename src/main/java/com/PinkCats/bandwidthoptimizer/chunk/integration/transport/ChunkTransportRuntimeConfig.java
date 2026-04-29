package com.PinkCats.bandwidthoptimizer.chunk.integration.transport;

import com.PinkCats.bandwidthoptimizer.Config;

public final class ChunkTransportRuntimeConfig {

    public static final String ENABLED_PROPERTY =
            Config.RuntimeProperty.Transport.CHUNK_HOTSPOT_TRANSPORT_ENABLED;

    private ChunkTransportRuntimeConfig() {}

    public static boolean isEnabled() {
        return Boolean.parseBoolean(
                System.getProperty(
                        ENABLED_PROPERTY,
                        Boolean.toString(Config.RuntimeProperty.Transport.DEFAULT_CHUNK_HOTSPOT_TRANSPORT_ENABLED)
                )
        );
    }
}
