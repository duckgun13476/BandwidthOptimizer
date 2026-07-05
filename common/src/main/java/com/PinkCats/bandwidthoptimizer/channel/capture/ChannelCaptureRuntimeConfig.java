package com.PinkCats.bandwidthoptimizer.channel.capture;

import com.PinkCats.bandwidthoptimizer.debug.DiagnosticToolRegistry;

public final class ChannelCaptureRuntimeConfig {

    private static final String LEGACY_JSONL_CAPTURE_PROPERTY = "bandwidthoptimizer.channelJsonlCaptureEnabled";

    private ChannelCaptureRuntimeConfig() {}

    // Full JSONL capture is expensive; allow only diagnostics and runAll.
    public static boolean isJsonlCaptureEnabled() {
        return Boolean.parseBoolean(System.getProperty(LEGACY_JSONL_CAPTURE_PROPERTY, "false"))
                || DiagnosticToolRegistry.isEnabled(DiagnosticToolRegistry.Tool.CHANNEL_JSONL_CAPTURE);
    }
}
