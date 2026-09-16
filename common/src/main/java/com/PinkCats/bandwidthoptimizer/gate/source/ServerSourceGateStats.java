package com.PinkCats.bandwidthoptimizer.gate.source;

import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;

public final class ServerSourceGateStats {

    private static final ConcurrentHashMap<UUID, ClientBaseline> CLIENT_BASELINES = new ConcurrentHashMap<>();
    private static final AtomicLong ESTIMATED_SAVED_BYTES = new AtomicLong();
    private static final AtomicLong ESTIMATED_OUTBOUND_SAVED_BYTES = new AtomicLong();
    private static final AtomicLong SUPPRESSED_FRAMES = new AtomicLong();

    private ServerSourceGateStats() {}

    public static void accept(UUID playerId, long cumulativeBytes, long cumulativeOutboundBytes, long cumulativeFrames) {
        if (playerId == null) {
            return;
        }
        long safeBytes = Math.max(cumulativeBytes, 0L);
        long safeOutboundBytes = Math.max(cumulativeOutboundBytes, 0L);
        long safeFrames = Math.max(cumulativeFrames, 0L);
        CLIENT_BASELINES.compute(playerId, (ignored, previous) -> {
            if (previous != null) {
                addDelta(ESTIMATED_SAVED_BYTES, previous.bytes(), safeBytes);
                addDelta(ESTIMATED_OUTBOUND_SAVED_BYTES, previous.outboundBytes(), safeOutboundBytes);
                addDelta(SUPPRESSED_FRAMES, previous.frames(), safeFrames);
            }
            return new ClientBaseline(safeBytes, safeOutboundBytes, safeFrames);
        });
    }

    public static void removeClient(UUID playerId) {
        if (playerId != null) {
            CLIENT_BASELINES.remove(playerId);
        }
    }

    public static Snapshot snapshot() {
        return new Snapshot(
                ESTIMATED_SAVED_BYTES.get(),
                ESTIMATED_OUTBOUND_SAVED_BYTES.get(),
                SUPPRESSED_FRAMES.get());
    }

    public static void reset() {
        CLIENT_BASELINES.clear();
        ESTIMATED_SAVED_BYTES.set(0L);
        ESTIMATED_OUTBOUND_SAVED_BYTES.set(0L);
        SUPPRESSED_FRAMES.set(0L);
    }

    private static void addDelta(AtomicLong total, long previous, long current) {
        if (current <= previous) {
            return;
        }
        long delta = current - previous;
        total.updateAndGet(value -> value >= Long.MAX_VALUE - delta ? Long.MAX_VALUE : value + delta);
    }

    private record ClientBaseline(long bytes, long outboundBytes, long frames) {}

    public record Snapshot(long estimatedSavedBytes, long estimatedOutboundSavedBytes, long suppressedFrames) {}
}
