package com.PinkCats.bandwidthoptimizer.channel.capture;

import com.PinkCats.bandwidthoptimizer.Config;

import java.util.concurrent.atomic.AtomicBoolean;

public final class ChannelCaptureRuntimeConfig {

    private static final String CHANNEL_JSONL_CAPTURE_ENABLED_PROPERTY =
            Config.RuntimeProperty.Transport.CHANNEL_JSONL_CAPTURE_ENABLED;
    private static final AtomicBoolean JSONL_CAPTURE_ENABLED =
            new AtomicBoolean(readInitialJsonlCaptureEnabled());

    private ChannelCaptureRuntimeConfig() {}

    public static boolean isJsonlCaptureEnabled() {
        return JSONL_CAPTURE_ENABLED.get();
    }

    public static void setJsonlCaptureEnabled(boolean enabled) {
        JSONL_CAPTURE_ENABLED.set(enabled);
    }

    private static boolean readInitialJsonlCaptureEnabled() {
        return Boolean.parseBoolean(
                System.getProperty(
                        CHANNEL_JSONL_CAPTURE_ENABLED_PROPERTY,
                        Boolean.toString(Config.RuntimeProperty.Transport.DEFAULT_CHANNEL_JSONL_CAPTURE_ENABLED)
                )
        );
    }
}
