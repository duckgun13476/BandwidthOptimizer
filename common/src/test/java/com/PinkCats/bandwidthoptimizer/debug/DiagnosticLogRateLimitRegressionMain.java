package com.PinkCats.bandwidthoptimizer.debug;

public final class DiagnosticLogRateLimitRegressionMain {
    private DiagnosticLogRateLimitRegressionMain() {}

    public static void main(String[] args) {
        DiagnosticToolRegistry.Tool tool = DiagnosticToolRegistry.Tool.CONNECTION_CLOSE;
        DiagnosticLog.resetLimitersForTest();

        int emitted = 0;
        for (int window = 0; window < 6; window++) {
            long nowMillis = window * 1_000L;
            for (int attempt = 0; attempt < 25; attempt++) {
                if (DiagnosticLog.shouldEmitForTest(tool, "event=rate_limit_probe", nowMillis)) {
                    emitted++;
                }
            }
        }
        check(emitted == DiagnosticLog.MAX_TOTAL_PER_EVENT,
                "diagnostic event exceeded its total limit: " + emitted);
        check(!DiagnosticLog.shouldEmitForTest(tool, "event=rate_limit_probe", 10_000L),
                "diagnostic event resumed after reaching its total limit");
        check(DiagnosticLog.shouldEmitForTest(tool, "event=independent_probe", 10_000L),
                "independent diagnostic event did not retain its own allowance");

        DiagnosticToolRegistry.disable(DiagnosticToolRegistry.Tool.STREAMING_EPOCH);
        check(!DiagnosticLog.shouldEmitForTest(
                        DiagnosticToolRegistry.Tool.STREAMING_EPOCH,
                        "event=disabled_probe",
                        10_000L),
                "disabled diagnostic tool emitted output");
        System.out.println("Diagnostic log rate-limit regression passed");
    }

    private static void check(boolean condition, String message) {
        if (!condition) {
            throw new IllegalStateException(message);
        }
    }
}
