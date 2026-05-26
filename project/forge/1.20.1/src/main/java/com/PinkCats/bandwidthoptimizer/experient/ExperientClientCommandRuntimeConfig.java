package com.PinkCats.bandwidthoptimizer.experient;

import com.PinkCats.bandwidthoptimizer.Config;

public final class ExperientClientCommandRuntimeConfig {

    private ExperientClientCommandRuntimeConfig() {}

    public static boolean isEnabled() {
        return !readCommand().isBlank();
    }

    public static String readCommand() {
        return System.getProperty(
                Config.RuntimeProperty.Experient.CLIENT_COMMAND,
                Config.RuntimeProperty.Experient.DEFAULT_CLIENT_COMMAND
        ).trim();
    }

    public static int readDelayTicks() {
        String propertyValue = System.getProperty(Config.RuntimeProperty.Experient.CLIENT_COMMAND_DELAY_TICKS);
        if (propertyValue == null || propertyValue.isBlank()) {
            return Config.RuntimeProperty.Experient.DEFAULT_CLIENT_COMMAND_DELAY_TICKS;
        }
        try {
            return Math.max(Integer.parseInt(propertyValue.trim()), 0);
        } catch (NumberFormatException ignored) {
            return Config.RuntimeProperty.Experient.DEFAULT_CLIENT_COMMAND_DELAY_TICKS;
        }
    }
}
