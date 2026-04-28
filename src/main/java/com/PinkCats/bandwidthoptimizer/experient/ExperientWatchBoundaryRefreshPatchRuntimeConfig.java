package com.PinkCats.bandwidthoptimizer.experient;

public final class ExperientWatchBoundaryRefreshPatchRuntimeConfig {

    public static final String ENABLED_PROPERTY =
            "bandwidthoptimizer.experient.watchBoundaryRefreshPatchEnabled";

    private ExperientWatchBoundaryRefreshPatchRuntimeConfig() {}

    public static boolean isEnabled() {
        return Boolean.parseBoolean(System.getProperty(ENABLED_PROPERTY, "false"));
    }
}
