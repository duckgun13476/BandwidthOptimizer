package com.PinkCats.bandwidthoptimizer.server.stat;

import java.util.ArrayDeque;

public final class ServerBandwidthRecentWindow {

    private static final long WINDOW_MILLIS = 120_000L;
    private static final Object LOCK = new Object();
    private static final ArrayDeque<Sample> SAMPLES = new ArrayDeque<>();

    private ServerBandwidthRecentWindow() {}

    public static Snapshot update(ServerBandwidthStatsRegistry.TotalsSnapshot totals) {
        if (totals == null) {
            return Snapshot.empty();
        }

        long nowMillis = System.currentTimeMillis();
        Sample current = new Sample(
                nowMillis,
                Math.max(totals.outboundRawEncodedBytes() + totals.serverIdleGateSavedBytes(), 0L),
                Math.max(totals.outboundWireBytes(), 0L)
        );
        synchronized (LOCK) {
            SAMPLES.addLast(current);
            pruneSamples(nowMillis);
            Sample first = SAMPLES.peekFirst();
            if (first == null || current.rawBytes() < first.rawBytes() || current.wireBytes() < first.wireBytes()) {
                SAMPLES.clear();
                SAMPLES.addLast(current);
                return Snapshot.empty();
            }
            return new Snapshot(
                    current.rawBytes() - first.rawBytes(),
                    current.wireBytes() - first.wireBytes()
            );
        }
    }

    public static void reset() {
        synchronized (LOCK) {
            SAMPLES.clear();
        }
    }

    private static void pruneSamples(long nowMillis) {
        while (SAMPLES.size() > 1 && nowMillis - SAMPLES.peekFirst().timestampMillis() > WINDOW_MILLIS) {
            SAMPLES.removeFirst();
        }
    }

    private record Sample(long timestampMillis, long rawBytes, long wireBytes) {
    }

    public record Snapshot(long outboundRawEncodedBytes, long outboundWireBytes) {
        public static Snapshot empty() {
            return new Snapshot(0L, 0L);
        }
    }
}
