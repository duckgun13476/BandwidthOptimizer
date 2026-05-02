package com.PinkCats.bandwidthoptimizer.chunk.verify;

import com.PinkCats.bandwidthoptimizer.Config;

import java.util.concurrent.atomic.AtomicBoolean;

public final class ChunkDiagnosticRuntimeConfig {

    private static final String DIAGNOSTIC_OUTPUT_ENABLED_PROPERTY =
            Config.RuntimeProperty.Chunk.DIAGNOSTIC_OUTPUTS_ENABLED;
    private static final AtomicBoolean DIAGNOSTIC_OUTPUT_ENABLED =
            new AtomicBoolean(readInitialEnabled());

    private ChunkDiagnosticRuntimeConfig() {}

    public static boolean isEnabled() {
        return DIAGNOSTIC_OUTPUT_ENABLED.get();
    }

    public static void applyEnabled(boolean enabled) {
        DIAGNOSTIC_OUTPUT_ENABLED.set(enabled);
        if (enabled) {
            ChunkHotspotVerifyHooks.flushCurrentReport();
        }
    }


    private static boolean readInitialEnabled() {
        return Boolean.parseBoolean(
                System.getProperty(
                        DIAGNOSTIC_OUTPUT_ENABLED_PROPERTY,
                        Boolean.toString(Config.RuntimeProperty.Chunk.DEFAULT_DIAGNOSTIC_OUTPUTS_ENABLED)
                )
        );
    }
}
