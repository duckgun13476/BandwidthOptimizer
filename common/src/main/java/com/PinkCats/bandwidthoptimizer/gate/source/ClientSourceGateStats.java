package com.PinkCats.bandwidthoptimizer.gate.source;

import java.util.concurrent.atomic.AtomicLong;

public final class ClientSourceGateStats {

    private static final AtomicLong ESTIMATED_SAVED_BYTES = new AtomicLong();
    private static final AtomicLong ESTIMATED_OUTBOUND_SAVED_BYTES = new AtomicLong();
    private static final AtomicLong SUPPRESSED_FRAMES = new AtomicLong();

    private ClientSourceGateStats() {}

    public static void recordEstimatedSavings(long bytes, long outboundBytes, long frames) {
        addSaturated(ESTIMATED_SAVED_BYTES, Math.max(bytes, 0L));
        addSaturated(ESTIMATED_OUTBOUND_SAVED_BYTES, Math.max(outboundBytes, 0L));
        addSaturated(SUPPRESSED_FRAMES, Math.max(frames, 0L));
    }

    public static Snapshot snapshot() {
        return new Snapshot(
                ESTIMATED_SAVED_BYTES.get(),
                ESTIMATED_OUTBOUND_SAVED_BYTES.get(),
                SUPPRESSED_FRAMES.get());
    }

    public static void reset() {
        ESTIMATED_SAVED_BYTES.set(0L);
        ESTIMATED_OUTBOUND_SAVED_BYTES.set(0L);
        SUPPRESSED_FRAMES.set(0L);
    }

    private static void addSaturated(AtomicLong counter, long delta) {
        if (delta <= 0L) {
            return;
        }
        counter.updateAndGet(current -> current >= Long.MAX_VALUE - delta ? Long.MAX_VALUE : current + delta);
    }

    public record Snapshot(long estimatedSavedBytes, long estimatedOutboundSavedBytes, long suppressedFrames) {}
}
