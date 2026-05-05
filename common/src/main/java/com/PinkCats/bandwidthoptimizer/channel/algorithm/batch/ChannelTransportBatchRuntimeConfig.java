package com.PinkCats.bandwidthoptimizer.channel.algorithm.batch;

import com.PinkCats.bandwidthoptimizer.Config;

public final class ChannelTransportBatchRuntimeConfig {

    private static final String BATCH_ENABLED_PROPERTY = Config.RuntimeProperty.Transport.BATCH_ENABLED;
    private static final String BATCH_WINDOW_MILLIS_PROPERTY = Config.RuntimeProperty.Transport.BATCH_WINDOW_MILLIS;
    private static final long DEFAULT_BATCH_WINDOW_MILLIS =
            Config.RuntimeProperty.Transport.DEFAULT_BATCH_WINDOW_MILLIS;
    private static final long DEFAULT_BATCH_WARMUP_MILLIS =
            Config.RuntimeProperty.Transport.DEFAULT_BATCH_WARMUP_MILLIS;

    private ChannelTransportBatchRuntimeConfig() {}

    // Batch algorthm switch
    public static boolean isBatchEnabled() {
        return Boolean.parseBoolean(
                System.getProperty(
                        BATCH_ENABLED_PROPERTY,
                        Boolean.toString(Config.RuntimeProperty.Transport.DEFAULT_BATCH_ENABLED)
                )
        );
    }

    // prevent problem window length
    public static long windowMillis() {
        String rawValue = System.getProperty(BATCH_WINDOW_MILLIS_PROPERTY);
        if (rawValue == null || rawValue.isBlank())
            return DEFAULT_BATCH_WINDOW_MILLIS;

        try {
            long parsedValue = Long.parseLong(rawValue.trim());
            return parsedValue > 0L ? parsedValue : DEFAULT_BATCH_WINDOW_MILLIS;
        } catch (NumberFormatException ignored) {
            return DEFAULT_BATCH_WINDOW_MILLIS;
        }
    }

    public static long warmupMillis() {return DEFAULT_BATCH_WARMUP_MILLIS;}
}
