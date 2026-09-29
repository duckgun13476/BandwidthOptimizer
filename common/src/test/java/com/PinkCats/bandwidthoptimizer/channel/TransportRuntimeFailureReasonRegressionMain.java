package com.PinkCats.bandwidthoptimizer.channel;

import java.io.IOException;

public final class TransportRuntimeFailureReasonRegressionMain {

    private TransportRuntimeFailureReasonRegressionMain() {
    }

    public static void main(String[] args) {
        IOException source = new IOException("BO jar embedded zstd-jni is damaged: source=test.jar, actualBytes=1");
        Throwable startup = new ExceptionInInitializerError(new IllegalStateException("zstd-jni is unavailable", source));
        String reason = TransportRuntimeFailureReason.describe(startup);
        require(reason.contains("source=test.jar") && reason.contains("actualBytes=1"),
                "startup warning hid the specific embedded resource error: " + reason);
        require(!reason.contains("ExceptionInInitializerError"), "startup warning kept the unhelpful wrapper");
        require("IllegalStateException: other failure".equals(
                TransportRuntimeFailureReason.describe(new IllegalStateException("other failure"))),
                "non-Zstd failure reason changed unexpectedly");
        System.out.println("transport-runtime-failure-reason: passed");
    }

    private static void require(boolean condition, String message) {
        if (!condition) {
            throw new AssertionError(message);
        }
    }
}
