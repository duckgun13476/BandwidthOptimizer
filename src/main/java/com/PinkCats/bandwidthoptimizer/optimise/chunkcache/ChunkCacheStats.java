package com.PinkCats.bandwidthoptimizer.optimise.chunkcache;

import com.PinkCats.bandwidthoptimizer.Bandwidthoptimizer;
import com.PinkCats.bandwidthoptimizer.Config;

import java.util.concurrent.atomic.AtomicLong;

public final class ChunkCacheStats {

    private static final AtomicLong TOTAL_REFRESHES = new AtomicLong();
    private static final AtomicLong TOTAL_REFRESH_BYTES = new AtomicLong();
    private static final AtomicLong TOTAL_HITS = new AtomicLong();
    private static final AtomicLong TOTAL_HIT_BYTES = new AtomicLong();
    private static final AtomicLong TOTAL_SAVED_BYTES = new AtomicLong();
    private static final AtomicLong TOTAL_MISS_REFRESHES = new AtomicLong();
    private static final AtomicLong TOTAL_MISS_REFRESH_BYTES = new AtomicLong();
    private static final AtomicLong LAST_REFRESHES = new AtomicLong();
    private static final AtomicLong LAST_REFRESH_BYTES = new AtomicLong();
    private static final AtomicLong LAST_HITS = new AtomicLong();
    private static final AtomicLong LAST_HIT_BYTES = new AtomicLong();
    private static final AtomicLong LAST_SAVED_BYTES = new AtomicLong();
    private static final AtomicLong LAST_MISS_REFRESHES = new AtomicLong();
    private static final AtomicLong LAST_MISS_REFRESH_BYTES = new AtomicLong();
    private static volatile long lastLogMillis;

    private ChunkCacheStats() {
    }

    public static void recordRefresh(long rawBytes, long sentBytes) {
        TOTAL_REFRESHES.incrementAndGet();
        TOTAL_REFRESH_BYTES.addAndGet(Math.max(rawBytes, 0L));
        LAST_REFRESHES.incrementAndGet();
        LAST_REFRESH_BYTES.addAndGet(Math.max(sentBytes, 0L));
        maybeLog();
    }

    public static void recordHit(long rawBytes, long sentBytes, long savedBytes) {
        TOTAL_HITS.incrementAndGet();
        TOTAL_HIT_BYTES.addAndGet(Math.max(sentBytes, 0L));
        TOTAL_SAVED_BYTES.addAndGet(Math.max(savedBytes, 0L));
        LAST_HITS.incrementAndGet();
        LAST_HIT_BYTES.addAndGet(Math.max(sentBytes, 0L));
        LAST_SAVED_BYTES.addAndGet(Math.max(savedBytes, 0L));
        maybeLog();
    }

    public static void recordMissRefresh(long rawBytes, long sentBytes) {
        TOTAL_MISS_REFRESHES.incrementAndGet();
        TOTAL_MISS_REFRESH_BYTES.addAndGet(Math.max(sentBytes, 0L));
        LAST_MISS_REFRESHES.incrementAndGet();
        LAST_MISS_REFRESH_BYTES.addAndGet(Math.max(sentBytes, 0L));
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
            Bandwidthoptimizer.LOGGER.info(
                    "[ChunkCache] refreshes={}, refreshBytes={}, hits={}, hitBytes={}, savedBytes={}, missRefreshes={}, missRefreshBytes={} | recent refreshes={}, recentRefreshBytes={}, recentHits={}, recentHitBytes={}, recentSavedBytes={}, recentMissRefreshes={}, recentMissRefreshBytes={}",
                    TOTAL_REFRESHES.get(),
                    TOTAL_REFRESH_BYTES.get(),
                    TOTAL_HITS.get(),
                    TOTAL_HIT_BYTES.get(),
                    TOTAL_SAVED_BYTES.get(),
                    TOTAL_MISS_REFRESHES.get(),
                    TOTAL_MISS_REFRESH_BYTES.get(),
                    recentRefreshes,
                    recentRefreshBytes,
                    recentHits,
                    recentHitBytes,
                    recentSavedBytes,
                    recentMissRefreshes,
                    recentMissRefreshBytes
            );
        }
    }
}
