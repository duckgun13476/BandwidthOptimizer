package com.PinkCats.bandwidthoptimizer.debug;

public final class DiagnosticSilencer {

    private DiagnosticSilencer() {}

    public static void disableAll() {
        DiagnosticRuntimeSwitch.setAll(false);
        DiagnosticToolRegistry.setAll(false);
    }

    public static String disabledText(String scope) {
        String safeScope = scope == null || scope.isBlank() ? "current side" : scope;
        return "BO diagnostics disabled for " + safeScope + ".";
    }
}
