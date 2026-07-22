package com.PinkCats.bandwidthoptimizer.experimental.runtime;

import com.PinkCats.bandwidthoptimizer.Config;

public final class ExperientServerCommandRuntimeConfig {

    public static final String COMMAND_PROPERTY = Config.RuntimeProperty.Experient.SERVER_COMMAND;
    public static final String COMMAND_DELAY_TICKS_PROPERTY =
            Config.RuntimeProperty.Experient.SERVER_COMMAND_DELAY_TICKS;

    private ExperientServerCommandRuntimeConfig() {}

    public static boolean isEnabled() {
        return !readCommand().isBlank();
    }

    public static String readCommand() {
        return System.getProperty(COMMAND_PROPERTY, Config.RuntimeProperty.Experient.DEFAULT_SERVER_COMMAND).trim();
    }

    public static int readDelayTicks() {
        String rawValue = System.getProperty(
                COMMAND_DELAY_TICKS_PROPERTY,
                Integer.toString(Config.RuntimeProperty.Experient.DEFAULT_SERVER_COMMAND_DELAY_TICKS)
        ).trim();
        if (rawValue.isEmpty()) {
            return 0;
        }
        try {
            return Math.max(Integer.parseInt(rawValue), 0);
        } catch (NumberFormatException ignored) {
            return 0;
        }
    }
}
