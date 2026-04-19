package com.PinkCats.bandwidthoptimizer.experient;

public final class ExperientRuntimeFlags {

    public static final String ENABLED_PROPERTY = "bandwidthoptimizer.experient.enabled";
    private ExperientRuntimeFlags() {}
    public static boolean isEnabled() {
        return Boolean.parseBoolean(System.getProperty(ENABLED_PROPERTY, "false"));
    }
}
