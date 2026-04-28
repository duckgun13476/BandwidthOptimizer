package com.PinkCats.bandwidthoptimizer.chunk.plan;

public final class ChunkWatchBoundaryReuseRuntimeConfig {

    public static final String MAX_DELTA_PACKETS_PROPERTY =
            "bandwidthoptimizer.chunk.watchBoundaryReuseMaxDeltaPackets";
    public static final String MAX_DELTA_BYTES_RATIO_PROPERTY =
            "bandwidthoptimizer.chunk.watchBoundaryReuseMaxDeltaBytesRatio";
    private static final long DEFAULT_MAX_DELTA_PACKETS = 64L;
    private static final double DEFAULT_MAX_DELTA_BYTES_RATIO = 0.5D;

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
