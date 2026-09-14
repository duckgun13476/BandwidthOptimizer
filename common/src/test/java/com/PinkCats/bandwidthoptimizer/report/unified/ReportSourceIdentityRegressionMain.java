package com.PinkCats.bandwidthoptimizer.report.unified;

import com.PinkCats.bandwidthoptimizer.util.BandwidthOptimizerOutputPaths;

import java.nio.file.Files;
import java.nio.file.Path;

public final class ReportSourceIdentityRegressionMain {
    private ReportSourceIdentityRegressionMain() {
    }

    public static void main(String[] args) throws Exception {
        Path root = Files.createTempDirectory("bo-report-source-");
        String previous = System.getProperty(BandwidthOptimizerOutputPaths.OUTPUT_DIRECTORY_PROPERTY);
        try {
            System.setProperty(BandwidthOptimizerOutputPaths.OUTPUT_DIRECTORY_PROPERTY, root.toString());
            ReportSourceIdentity.resetForTesting();
            String first = ReportSourceIdentity.loadOrCreate();
            require(first.matches("[0-9a-f]{64}"), "fingerprint format mismatch");
            ReportSourceIdentity.resetForTesting();
            require(first.equals(ReportSourceIdentity.loadOrCreate()), "fingerprint was not persistent");

            Files.writeString(root.resolve("reports/.source-fingerprint"), "invalid");
            ReportSourceIdentity.resetForTesting();
            String repaired = ReportSourceIdentity.loadOrCreate();
            require(repaired.matches("[0-9a-f]{64}") && !repaired.equals(first), "invalid fingerprint was not replaced");
            System.out.println("Report source identity regression passed.");
        } finally {
            ReportSourceIdentity.resetForTesting();
            if (previous == null) {
                System.clearProperty(BandwidthOptimizerOutputPaths.OUTPUT_DIRECTORY_PROPERTY);
            } else {
                System.setProperty(BandwidthOptimizerOutputPaths.OUTPUT_DIRECTORY_PROPERTY, previous);
            }
        }
    }

    private static void require(boolean condition, String message) {
        if (!condition) {
            throw new AssertionError(message);
        }
    }
}
