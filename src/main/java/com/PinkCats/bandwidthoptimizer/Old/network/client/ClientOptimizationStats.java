package com.PinkCats.bandwidthoptimizer.Old.network.client;

import java.util.ArrayDeque;

public final class ClientOptimizationStats {

    private static final long RECENT_WINDOW_MILLIS = 120_000L;
    private static final Object LOCK = new Object();
    private static final ArrayDeque<Sample> RECENT_SAMPLES = new ArrayDeque<>();

    private static long totalRawBytes;
    private static long totalBatchedBytes;
    private static long totalBatchCount;
    private static long totalPacketCount;
    private static long bypassTotalBytes;
    private static long bypassTotalPackets;
    private static long bypassRecentBytes;
    private static long bypassRecentPackets;
    private static long chunkCacheRawTotalBytes;
    private static long chunkCacheRawRecentBytes;
    private static long chunkCacheSentTotalBytes;
    private static long chunkCacheSentRecentBytes;
    private static long chunkCacheHitTotalPackets;
    private static long chunkCacheHitRecentPackets;
    private static long chunkCacheRefreshTotalPackets;
    private static long chunkCacheRefreshRecentPackets;
    private static String lastAlgorithmId = "-";
    private static long lastUpdateMillis;

    private ClientOptimizationStats() {
    }

    public static void recordBatch(long rawBytes, long batchedBytes, int packetCount, String algorithmId) {
        long now = System.currentTimeMillis();
        synchronized (LOCK) {
            totalRawBytes += Math.max(rawBytes, 0L);
            totalBatchedBytes += Math.max(batchedBytes, 0L);
            totalBatchCount++;
            totalPacketCount += Math.max(packetCount, 0);
            lastAlgorithmId = algorithmId == null || algorithmId.isBlank() ? "-" : algorithmId;
            lastUpdateMillis = now;
            RECENT_SAMPLES.addLast(new Sample(now, totalRawBytes, totalBatchedBytes, totalBatchCount, totalPacketCount));
            prune(now);
        }
    }

    public static Snapshot snapshot() {
        long now = System.currentTimeMillis();
        synchronized (LOCK) {
            prune(now);
            Sample first = RECENT_SAMPLES.peekFirst();
            long recentRaw = 0L;
            long recentBatched = 0L;
            long recentBatches = 0L;
            long recentPackets = 0L;
            if (first != null) {
                recentRaw = Math.max(totalRawBytes - first.rawBytes(), 0L);
                recentBatched = Math.max(totalBatchedBytes - first.batchedBytes(), 0L);
                recentBatches = Math.max(totalBatchCount - first.batchCount(), 0L);
                recentPackets = Math.max(totalPacketCount - first.packetCount(), 0L);
            }
            return new Snapshot(
                    totalRawBytes,
                    totalBatchedBytes,
                    totalBatchCount,
                    totalPacketCount,
                    bypassTotalBytes,
                    bypassTotalPackets,
                    bypassRecentBytes,
                    bypassRecentPackets,
                    chunkCacheRawTotalBytes,
                    chunkCacheRawRecentBytes,
                    chunkCacheSentTotalBytes,
                    chunkCacheSentRecentBytes,
                    chunkCacheHitTotalPackets,
                    chunkCacheHitRecentPackets,
                    chunkCacheRefreshTotalPackets,
                    chunkCacheRefreshRecentPackets,
                    recentRaw,
                    recentBatched,
                    recentBatches,
                    recentPackets,
                    lastAlgorithmId,
                    lastUpdateMillis
            );
        }
    }

    public static void updateBypassTelemetry(
            long totalBytes,
            long totalPackets,
            long recentBytes,
            long recentPackets,
            long chunkCacheRawTotal,
            long chunkCacheRawRecent,
            long chunkCacheSentTotal,
            long chunkCacheSentRecent,
            long chunkCacheHitTotal,
            long chunkCacheHitRecent,
            long chunkCacheRefreshTotal,
            long chunkCacheRefreshRecent
    ) {
        synchronized (LOCK) {
            bypassTotalBytes = Math.max(totalBytes, 0L);
            bypassTotalPackets = Math.max(totalPackets, 0L);
            bypassRecentBytes = Math.max(recentBytes, 0L);
            bypassRecentPackets = Math.max(recentPackets, 0L);
            chunkCacheRawTotalBytes = Math.max(chunkCacheRawTotal, 0L);
            chunkCacheRawRecentBytes = Math.max(chunkCacheRawRecent, 0L);
            chunkCacheSentTotalBytes = Math.max(chunkCacheSentTotal, 0L);
            chunkCacheSentRecentBytes = Math.max(chunkCacheSentRecent, 0L);
            chunkCacheHitTotalPackets = Math.max(chunkCacheHitTotal, 0L);
            chunkCacheHitRecentPackets = Math.max(chunkCacheHitRecent, 0L);
            chunkCacheRefreshTotalPackets = Math.max(chunkCacheRefreshTotal, 0L);
            chunkCacheRefreshRecentPackets = Math.max(chunkCacheRefreshRecent, 0L);
            lastUpdateMillis = System.currentTimeMillis();
        }
    }

    private static void prune(long now) {
        while (RECENT_SAMPLES.size() > 1 && now - RECENT_SAMPLES.peekFirst().timestampMillis() > RECENT_WINDOW_MILLIS) {
            RECENT_SAMPLES.removeFirst();
        }
    }

    private record Sample(
            long timestampMillis,
            long rawBytes,
            long batchedBytes,
            long batchCount,
            long packetCount
    ) {
    }

    public record Snapshot(
            long totalRawBytes,
            long totalBatchedBytes,
            long totalBatchCount,
            long totalPacketCount,
            long bypassTotalBytes,
            long bypassTotalPackets,
            long bypassRecentBytes,
            long bypassRecentPackets,
            long chunkCacheRawTotalBytes,
            long chunkCacheRawRecentBytes,
            long chunkCacheSentTotalBytes,
            long chunkCacheSentRecentBytes,
            long chunkCacheHitTotalPackets,
            long chunkCacheHitRecentPackets,
            long chunkCacheRefreshTotalPackets,
            long chunkCacheRefreshRecentPackets,
            long recentRawBytes,
            long recentBatchedBytes,
            long recentBatchCount,
            long recentPacketCount,
            String algorithmId,
            long lastUpdateMillis
    ) {
        public boolean hasData() {
            return totalBatchCount > 0L
                    || bypassTotalBytes > 0L
                    || chunkCacheRawTotalBytes > 0L
                    || chunkCacheSentTotalBytes > 0L
                    || chunkCacheHitTotalPackets > 0L
                    || chunkCacheRefreshTotalPackets > 0L;
        }
    }
}
