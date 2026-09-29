package com.PinkCats.bandwidthoptimizer.channel.algorithm.zstd;

public final class EmbeddedZstdResourceCorruptionException extends IllegalStateException {

    EmbeddedZstdResourceCorruptionException(String message) {
        super(message);
    }

    public static EmbeddedZstdResourceCorruptionException findCause(Throwable failure) {
        for (Throwable current = failure; current != null; current = current.getCause()) {
            if (current instanceof EmbeddedZstdResourceCorruptionException corruption) {
                return corruption;
            }
        }
        return null;
    }
}
