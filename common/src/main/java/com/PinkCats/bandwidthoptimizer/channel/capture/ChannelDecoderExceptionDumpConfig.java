package com.PinkCats.bandwidthoptimizer.channel.capture;

import com.PinkCats.bandwidthoptimizer.Config;

public final class ChannelDecoderExceptionDumpConfig {

    private ChannelDecoderExceptionDumpConfig() {}

    public static boolean isEnabled() {
        return Boolean.parseBoolean(
                System.getProperty(
                        Config.RuntimeProperty.Transport.DECODER_EXCEPTION_DUMP_ENABLED,
                        Boolean.toString(Config.RuntimeProperty.Transport.DEFAULT_DECODER_EXCEPTION_DUMP_ENABLED)
                )
        );
    }

    public static int maxPayloadBytes() {
        String rawValue = System.getProperty(Config.RuntimeProperty.Transport.DECODER_EXCEPTION_DUMP_MAX_BYTES);
        if (rawValue == null || rawValue.isBlank()) {
            return Config.RuntimeProperty.Transport.DEFAULT_DECODER_EXCEPTION_DUMP_MAX_BYTES;
        }
        try {
            return Math.max(Integer.parseInt(rawValue.trim()), 0);
        } catch (NumberFormatException ignored) {
            return Config.RuntimeProperty.Transport.DEFAULT_DECODER_EXCEPTION_DUMP_MAX_BYTES;
        }
    }
}
