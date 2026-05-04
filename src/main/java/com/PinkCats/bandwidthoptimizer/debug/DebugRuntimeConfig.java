package com.PinkCats.bandwidthoptimizer.debug;

import com.PinkCats.bandwidthoptimizer.Config;

public final class DebugRuntimeConfig {

    private DebugRuntimeConfig() {}

    public static boolean isAnalysisEnabled() {
        return readBooleanProperty(
                Config.RuntimeProperty.Debug.ANALYSIS_ENABLED,
                Config.debugAnalysis || Config.RuntimeProperty.Debug.DEFAULT_ANALYSIS_ENABLED
        );
    }

    public static boolean isDiagnoseEnabled() {
        return readBooleanProperty(
                Config.RuntimeProperty.Debug.DIAGNOSE_ENABLED,
                Config.debugDiagnose || Config.RuntimeProperty.Debug.DEFAULT_DIAGNOSE_ENABLED
        );
    }

    private static boolean readBooleanProperty(String propertyName, boolean fallback) {
        String rawValue = System.getProperty(propertyName);
        if (rawValue == null || rawValue.isBlank()) {
            return fallback;
        }
        return Boolean.parseBoolean(rawValue);
    }
}
