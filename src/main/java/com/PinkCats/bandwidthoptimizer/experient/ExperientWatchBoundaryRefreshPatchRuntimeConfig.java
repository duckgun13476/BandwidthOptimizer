package com.PinkCats.bandwidthoptimizer.experient;

import com.PinkCats.bandwidthoptimizer.Config;

public final class ExperientWatchBoundaryRefreshPatchRuntimeConfig {

    public static final String ENABLED_PROPERTY =
            Config.RuntimeProperty.Experient.WATCH_BOUNDARY_REFRESH_PATCH_ENABLED;

    private ExperientWatchBoundaryRefreshPatchRuntimeConfig() {}

    public static boolean isEnabled() {
        return Boolean.parseBoolean(
                System.getProperty(
                        ENABLED_PROPERTY,
                        Boolean.toString(Config.RuntimeProperty.Experient.DEFAULT_ENABLED)
                )
        );
    }
}
