package com.PinkCats.bandwidthoptimizer.chunk.plan;

import com.PinkCats.bandwidthoptimizer.Config;

public final class ChunkWatchBoundaryReuseRuntimeConfig {

    public static final String MAX_DELTA_PACKETS_PROPERTY =
            Config.RuntimeProperty.Chunk.WATCH_BOUNDARY_REUSE_MAX_DELTA_PACKETS;
    public static final String MAX_DELTA_BYTES_RATIO_PROPERTY =
            Config.RuntimeProperty.Chunk.WATCH_BOUNDARY_REUSE_MAX_DELTA_BYTES_RATIO;
    private static final long DEFAULT_MAX_DELTA_PACKETS =
            Config.RuntimeProperty.Chunk.DEFAULT_WATCH_BOUNDARY_REUSE_MAX_DELTA_PACKETS;
    private static final double DEFAULT_MAX_DELTA_BYTES_RATIO =
            Config.RuntimeProperty.Chunk.DEFAULT_WATCH_BOUNDARY_REUSE_MAX_DELTA_BYTES_RATIO;

    private ChunkWatchBoundaryReuseRuntimeConfig() {}

    public static long maxDeltaPackets() {
        String rawValue = System.getProperty(MAX_DELTA_PACKETS_PROPERTY, Long.toString(DEFAULT_MAX_DELTA_PACKETS));
        try {
            return Math.max(Long.parseLong(rawValue.trim()), 0L);
        } catch (NumberFormatException ignored) {
            return DEFAULT_MAX_DELTA_PACKETS;
        }
    }

    public static double maxDeltaBytesRatio() {
        String rawValue = System.getProperty(MAX_DELTA_BYTES_RATIO_PROPERTY, Double.toString(DEFAULT_MAX_DELTA_BYTES_RATIO));
        try {
            return Math.max(Double.parseDouble(rawValue.trim()), 0D);
        } catch (NumberFormatException ignored) {
            return DEFAULT_MAX_DELTA_BYTES_RATIO;
        }
    }
}
