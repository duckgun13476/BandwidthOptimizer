package com.PinkCats.bandwidthoptimizer.experient;

import com.PinkCats.bandwidthoptimizer.Config;

public final class ExperientRuntimeFlags {

    public static final String ENABLED_PROPERTY = Config.RuntimeProperty.Experient.ENABLED;
    private ExperientRuntimeFlags() {}
    public static boolean isEnabled() {
        return Boolean.parseBoolean(
                System.getProperty(
                        ENABLED_PROPERTY,
                        Boolean.toString(Config.RuntimeProperty.Experient.DEFAULT_ENABLED)
                )
        );
    }
}
