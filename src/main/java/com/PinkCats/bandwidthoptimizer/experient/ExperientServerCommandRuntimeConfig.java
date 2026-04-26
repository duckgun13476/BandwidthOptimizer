package com.PinkCats.bandwidthoptimizer.experient;

public final class ExperientServerCommandRuntimeConfig {

    public static final String COMMAND_PROPERTY = "bandwidthoptimizer.experient.serverCommand";
    public static final String COMMAND_DELAY_TICKS_PROPERTY = "bandwidthoptimizer.experient.serverCommandDelayTicks";

    private ExperientServerCommandRuntimeConfig() {}

    public static boolean isEnabled() {
        return !readCommand().isBlank();
    }

    public static String readCommand() {
        return System.getProperty(COMMAND_PROPERTY, "").trim();
    }

    public static int readDelayTicks() {
        String rawValue = System.getProperty(COMMAND_DELAY_TICKS_PROPERTY, "0").trim();
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
