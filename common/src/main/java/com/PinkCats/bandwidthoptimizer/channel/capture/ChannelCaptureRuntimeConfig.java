package com.PinkCats.bandwidthoptimizer.channel.capture;

import com.PinkCats.bandwidthoptimizer.debug.DiagnosticToolRegistry;

public final class ChannelCaptureRuntimeConfig {

    private ChannelCaptureRuntimeConfig() {}

    // Full JSONL capture is expensive; keep it behind an explicit diagnostic switch.
    public static boolean isJsonlCaptureEnabled() {
        return DiagnosticToolRegistry.isEnabled(DiagnosticToolRegistry.Tool.CHANNEL_JSONL_CAPTURE);
    }
}
