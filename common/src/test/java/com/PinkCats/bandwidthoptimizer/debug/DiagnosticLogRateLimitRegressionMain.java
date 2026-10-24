package com.PinkCats.bandwidthoptimizer.debug;

public final class DiagnosticLogRateLimitRegressionMain {
    private DiagnosticLogRateLimitRegressionMain() {}

    public static void main(String[] args) {
        DiagnosticToolRegistry.Tool tool = DiagnosticToolRegistry.Tool.KEEP_ALIVE_TIMEOUT;
        DiagnosticToolRegistry.enable(tool, 5);
        DiagnosticLog.resetLimitersForTest();

        if (!com.PinkCats.bandwidthoptimizer.Bandwidthoptimizer.LOGGER.isDebugEnabled()) {
            for (int attempt = 0; attempt < 150; attempt++) {
                DiagnosticLog.debug(tool, "event=disabled_debug_probe");
            }
            for (int attempt = 0; attempt < 20; attempt++) {
                check(DiagnosticLog.shouldEmitForTest(tool, "event=disabled_debug_probe", 0L),
                        "disabled DEBUG consumed diagnostic allowance");
            }
        }

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
        DiagnosticToolRegistry.disable(tool);
        DiagnosticToolRegistry.enable(DiagnosticToolRegistry.Tool.NETWORK_PROBE, 5);
        int fileGrants = 0;
        for (int attempt = 0; attempt < 150; attempt++) {
            if (DiagnosticLog.acquireFileEvent(DiagnosticToolRegistry.Tool.NETWORK_PROBE,
                    "v=1 domain=network event=network_probe_summary")) fileGrants++;
        }
        check(fileGrants <= DiagnosticLog.MAX_TOTAL_PER_EVENT,
                "file diagnostic bypassed the process limit");
        check(fileGrants <= 20,
                "file diagnostic bypassed the rolling-second limit");
        DiagnosticToolRegistry.disable(DiagnosticToolRegistry.Tool.NETWORK_PROBE);
        check(!DiagnosticLog.acquireFileEvent(DiagnosticToolRegistry.Tool.NETWORK_PROBE,
                        "v=1 domain=network event=network_probe_summary"),
                "disabled file diagnostic emitted output");
        System.out.println("Diagnostic log rate-limit regression passed");
    }

    private static void check(boolean condition, String message) {
        if (!condition) {
            throw new IllegalStateException(message);
        }
    }
}
