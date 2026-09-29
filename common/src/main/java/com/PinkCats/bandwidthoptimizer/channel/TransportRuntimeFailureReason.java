package com.PinkCats.bandwidthoptimizer.channel;

import java.io.IOException;

final class TransportRuntimeFailureReason {

    private TransportRuntimeFailureReason() {
    }

    static String describe(Throwable failure) {
        if (failure == null) {
            return "Unknown transport runtime failure";
        }
        Throwable current = failure;
        String deepest = null;
        for (int depth = 0; depth < 12 && current != null; depth++) {
            String message = current.getMessage();
            if (message != null && !message.isBlank()) {
                deepest = current.getClass().getSimpleName() + ": " + message;
                if (current instanceof IOException) {
                    return deepest;
                }
            }
            Throwable cause = current.getCause();
            if (cause == current) {
                break;
            }
            current = cause;
        }
        return deepest == null ? failure.getClass().getSimpleName() : deepest;
    }
}
