package com.PinkCats.bandwidthoptimizer.debug;

import com.PinkCats.bandwidthoptimizer.Bandwidthoptimizer;

public final class DiagnosticLog {

    private DiagnosticLog() {}

    public static String prefix(DiagnosticToolRegistry.Tool tool) {
        return "[BO:Diag:" + id(tool) + "]";
    }

    public static void info(DiagnosticToolRegistry.Tool tool, String message, Object... args) {
        if (!DiagnosticToolRegistry.isEnabled(tool)) {
            return;
        }
        Bandwidthoptimizer.LOGGER.info(format(tool, message), args);
    }

    public static void warn(DiagnosticToolRegistry.Tool tool, String message, Object... args) {
        if (!DiagnosticToolRegistry.isEnabled(tool)) {
            return;
        }
        Bandwidthoptimizer.LOGGER.warn(format(tool, message), args);
    }

    public static void error(DiagnosticToolRegistry.Tool tool, String message, Object... args) {
        if (!DiagnosticToolRegistry.isEnabled(tool)) {
            return;
        }
        Bandwidthoptimizer.LOGGER.error(format(tool, message), args);
    }

    private static String format(DiagnosticToolRegistry.Tool tool, String message) {
        String safeMessage = message == null ? "" : message;
        return prefix(tool) + (safeMessage.isEmpty() ? "" : " " + safeMessage);
    }

    private static String id(DiagnosticToolRegistry.Tool tool) {
        return tool == null ? "unknown" : tool.id();
    }
}
