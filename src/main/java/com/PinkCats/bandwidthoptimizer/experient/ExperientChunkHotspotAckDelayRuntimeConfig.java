package com.PinkCats.bandwidthoptimizer.experient;

public final class ExperientChunkHotspotAckDelayRuntimeConfig {

    public static final String ACK_DELAY_TICKS_PROPERTY = "bandwidthoptimizer.experient.chunkHotspotAckDelayTicks";
    private static final long TICK_MILLIS = 50L;

    private ExperientChunkHotspotAckDelayRuntimeConfig() {}


    public static int readDelayTicks() {
        String rawValue = System.getProperty(ACK_DELAY_TICKS_PROPERTY, "0");
        if (rawValue == null || rawValue.isBlank()) {
            return 0;
        }

        try {
            return Math.max(Integer.parseInt(rawValue.trim()), 0);
        } catch (NumberFormatException ignored) {
            return 0;
        }
    }

    public static boolean isEnabled() {
        return readDelayTicks() > 0;
    }

    public static long readDelayMillis() {
        return Math.max(readDelayTicks(), 0) * TICK_MILLIS;
    }
}
