package com.PinkCats.bandwidthoptimizer.optimise.chunkcache;

import com.PinkCats.bandwidthoptimizer.Bandwidthoptimizer;
import com.PinkCats.bandwidthoptimizer.Config;

import java.util.EnumMap;
import java.util.Map;
import java.util.concurrent.atomic.AtomicLong;

public final class ChunkCacheStats {

    public enum RefreshReason {
        FORCE_REFRESH_RADIUS("force_refresh_radius"),
        TTL_SINCE_REFRESH("ttl_since_refresh"),
        PRUNED_OUT_OF_RADIUS("pruned_out_of_radius"),
        PRUNED_IDLE("pruned_idle"),
        NO_ENTRY("no_entry"),
        CLIENT_MISS("client_miss");

        private final String logName;

        RefreshReason(String logName) {
            this.logName = logName;
        }

        public String logName() {
            return this.logName;
        }
    }

    private static final AtomicLong TOTAL_REFRESHES = new AtomicLong();
    private static final AtomicLong TOTAL_REFRESH_BYTES = new AtomicLong();
    private static final AtomicLong TOTAL_HITS = new AtomicLong();
    private static final AtomicLong TOTAL_HIT_BYTES = new AtomicLong();
    private static final AtomicLong TOTAL_SAVED_BYTES = new AtomicLong();
    private static final AtomicLong TOTAL_MISS_REFRESHES = new AtomicLong();
    private static final AtomicLong TOTAL_MISS_REFRESH_BYTES = new AtomicLong();
    private static final AtomicLong TOTAL_DELTA_PACKETS = new AtomicLong();
    private static final AtomicLong TOTAL_DELTA_BYTES = new AtomicLong();
    private static final AtomicLong TOTAL_DELTA_BACKED_HITS = new AtomicLong();
    private static final AtomicLong TOTAL_DELTA_BACKED_HIT_PACKETS = new AtomicLong();
    private static final AtomicLong TOTAL_DELTA_BACKED_HIT_BYTES = new AtomicLong();
    private static final AtomicLong LAST_REFRESHES = new AtomicLong();
    private static final AtomicLong LAST_REFRESH_BYTES = new AtomicLong();
    private static final AtomicLong LAST_HITS = new AtomicLong();
    private static final AtomicLong LAST_HIT_BYTES = new AtomicLong();
    private static final AtomicLong LAST_SAVED_BYTES = new AtomicLong();
    private static final AtomicLong LAST_MISS_REFRESHES = new AtomicLong();
    private static final AtomicLong LAST_MISS_REFRESH_BYTES = new AtomicLong();
    private static final AtomicLong LAST_DELTA_PACKETS = new AtomicLong();
    private static final AtomicLong LAST_DELTA_BYTES = new AtomicLong();
    private static final AtomicLong LAST_DELTA_BACKED_HITS = new AtomicLong();
    private static final AtomicLong LAST_DELTA_BACKED_HIT_PACKETS = new AtomicLong();
    private static final AtomicLong LAST_DELTA_BACKED_HIT_BYTES = new AtomicLong();
    private static final Map<RefreshReason, AtomicLong> TOTAL_REFRESH_REASONS = createReasonCounters();
    private static final Map<RefreshReason, AtomicLong> LAST_REFRESH_REASONS = createReasonCounters();
    private static volatile long lastLogMillis;

    private ChunkCacheStats() {
    }

    public static void recordRefresh(long rawBytes, long sentBytes, RefreshReason reason) {
        TOTAL_REFRESHES.incrementAndGet();
        TOTAL_REFRESH_BYTES.addAndGet(Math.max(rawBytes, 0L));
        LAST_REFRESHES.incrementAndGet();
        LAST_REFRESH_BYTES.addAndGet(Math.max(sentBytes, 0L));
        incrementReason(reason, TOTAL_REFRESH_REASONS);
        incrementReason(reason, LAST_REFRESH_REASONS);
        maybeLog();
    }

    public static void recordHit(long rawBytes, long sentBytes, long savedBytes, long deltaPackets, long deltaBytes) {
        TOTAL_HITS.incrementAndGet();
        TOTAL_HIT_BYTES.addAndGet(Math.max(sentBytes, 0L));
        TOTAL_SAVED_BYTES.addAndGet(Math.max(savedBytes, 0L));
        LAST_HITS.incrementAndGet();
        LAST_HIT_BYTES.addAndGet(Math.max(sentBytes, 0L));
        LAST_SAVED_BYTES.addAndGet(Math.max(savedBytes, 0L));
        long safeDeltaPackets = Math.max(deltaPackets, 0L);
        long safeDeltaBytes = Math.max(deltaBytes, 0L);
        if (safeDeltaPackets > 0L || safeDeltaBytes > 0L) {
            TOTAL_DELTA_BACKED_HITS.incrementAndGet();
            TOTAL_DELTA_BACKED_HIT_PACKETS.addAndGet(safeDeltaPackets);
            TOTAL_DELTA_BACKED_HIT_BYTES.addAndGet(safeDeltaBytes);
            LAST_DELTA_BACKED_HITS.incrementAndGet();
            LAST_DELTA_BACKED_HIT_PACKETS.addAndGet(safeDeltaPackets);
            LAST_DELTA_BACKED_HIT_BYTES.addAndGet(safeDeltaBytes);
        }
        maybeLog();
    }

    public static void recordMissRefresh(long rawBytes, long sentBytes) {
        TOTAL_MISS_REFRESHES.incrementAndGet();
        TOTAL_MISS_REFRESH_BYTES.addAndGet(Math.max(sentBytes, 0L));
        LAST_MISS_REFRESHES.incrementAndGet();
        LAST_MISS_REFRESH_BYTES.addAndGet(Math.max(sentBytes, 0L));
        maybeLog();
    }

    public static void recordDelta(long sentBytes) {
        long safeSentBytes = Math.max(sentBytes, 0L);
        TOTAL_DELTA_PACKETS.incrementAndGet();
        TOTAL_DELTA_BYTES.addAndGet(safeSentBytes);
        LAST_DELTA_PACKETS.incrementAndGet();
        LAST_DELTA_BYTES.addAndGet(safeSentBytes);
        maybeLog();
    }

    private static void maybeLog() {
        if (!Config.enableOptimizerStatsLogs || !Config.enableTestMode) {
            return;
        }
        long now = System.currentTimeMillis();
        long logIntervalMillis = Config.optimizerStatsLogIntervalMillis();
        if (now - lastLogMillis < logIntervalMillis) {
            return;
        }
        synchronized (ChunkCacheStats.class) {
            if (now - lastLogMillis < logIntervalMillis) {
                return;
            }
            lastLogMillis = now;
            long recentRefreshes = LAST_REFRESHES.getAndSet(0L);
            long recentRefreshBytes = LAST_REFRESH_BYTES.getAndSet(0L);
            long recentHits = LAST_HITS.getAndSet(0L);
            long recentHitBytes = LAST_HIT_BYTES.getAndSet(0L);
            long recentSavedBytes = LAST_SAVED_BYTES.getAndSet(0L);
            long recentMissRefreshes = LAST_MISS_REFRESHES.getAndSet(0L);
            long recentMissRefreshBytes = LAST_MISS_REFRESH_BYTES.getAndSet(0L);
            long recentDeltaPackets = LAST_DELTA_PACKETS.getAndSet(0L);
            long recentDeltaBytes = LAST_DELTA_BYTES.getAndSet(0L);
            long recentDeltaBackedHits = LAST_DELTA_BACKED_HITS.getAndSet(0L);
            long recentDeltaBackedHitPackets = LAST_DELTA_BACKED_HIT_PACKETS.getAndSet(0L);
            long recentDeltaBackedHitBytes = LAST_DELTA_BACKED_HIT_BYTES.getAndSet(0L);
            String totalReasons = formatReasonCounters(TOTAL_REFRESH_REASONS, false);
            String recentReasons = formatReasonCounters(LAST_REFRESH_REASONS, true);
            Bandwidthoptimizer.LOGGER.info(
                    "[ChunkCache] refreshes={}, refreshBytes={}, hits={}, hitBytes={}, savedBytes={}, missRefreshes={}, missRefreshBytes={}, deltas={}, deltaBytes={}, deltaBackedHits={}, deltaBackedHitPackets={}, deltaBackedHitBytes={}, refreshReasons={} | recent refreshes={}, recentRefreshBytes={}, recentHits={}, recentHitBytes={}, recentSavedBytes={}, recentMissRefreshes={}, recentMissRefreshBytes={}, recentDeltas={}, recentDeltaBytes={}, recentDeltaBackedHits={}, recentDeltaBackedHitPackets={}, recentDeltaBackedHitBytes={}, recentRefreshReasons={}",
                    TOTAL_REFRESHES.get(),
                    TOTAL_REFRESH_BYTES.get(),
                    TOTAL_HITS.get(),
                    TOTAL_HIT_BYTES.get(),
                    TOTAL_SAVED_BYTES.get(),
                    TOTAL_MISS_REFRESHES.get(),
                    TOTAL_MISS_REFRESH_BYTES.get(),
                    TOTAL_DELTA_PACKETS.get(),
                    TOTAL_DELTA_BYTES.get(),
                    TOTAL_DELTA_BACKED_HITS.get(),
                    TOTAL_DELTA_BACKED_HIT_PACKETS.get(),
                    TOTAL_DELTA_BACKED_HIT_BYTES.get(),
                    totalReasons,
                    recentRefreshes,
                    recentRefreshBytes,
                    recentHits,
                    recentHitBytes,
                    recentSavedBytes,
                    recentMissRefreshes,
                    recentMissRefreshBytes,
                    recentDeltaPackets,
                    recentDeltaBytes,
                    recentDeltaBackedHits,
                    recentDeltaBackedHitPackets,
                    recentDeltaBackedHitBytes,
                    recentReasons
            );
        }
    }

    private static Map<RefreshReason, AtomicLong> createReasonCounters() {
        Map<RefreshReason, AtomicLong> counters = new EnumMap<>(RefreshReason.class);
        for (RefreshReason reason : RefreshReason.values()) {
            counters.put(reason, new AtomicLong());
        }
        return counters;
    }

    private static void incrementReason(RefreshReason reason, Map<RefreshReason, AtomicLong> counters) {
        RefreshReason safeReason = reason == null ? RefreshReason.NO_ENTRY : reason;
        AtomicLong counter = counters.get(safeReason);
        if (counter != null) {
            counter.incrementAndGet();
        }
    }

    private static String formatReasonCounters(Map<RefreshReason, AtomicLong> counters, boolean reset) {
        StringBuilder builder = new StringBuilder(128);
        boolean first = true;
        for (RefreshReason reason : RefreshReason.values()) {
            AtomicLong counter = counters.get(reason);
            long value = counter == null ? 0L : (reset ? counter.getAndSet(0L) : counter.get());
            if (!first) {
                builder.append(", ");
            }
            builder.append(reason.logName()).append('=').append(value);
            first = false;
        }
        return builder.toString();
    }
}
