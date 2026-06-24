package com.PinkCats.bandwidthoptimizer.chunk.verify;

import com.PinkCats.bandwidthoptimizer.debug.DiagnosticToolRegistry;

public final class ChunkDiagnosticRuntimeConfig {

    private ChunkDiagnosticRuntimeConfig() {}

    // Diagnostic report files are disabled unless an explicit diagnostic switch enables them.
    public static boolean isEnabled() {
        return DiagnosticToolRegistry.isEnabled(DiagnosticToolRegistry.Tool.CHUNK_VERIFY_OUTPUT);
    }

    public static void applyEnabled(boolean enabled) {
        if (enabled) {
            ChunkHotspotVerifyHooks.flushCurrentReport();
        }
    }
}
