package com.PinkCats.bandwidthoptimizer.network.client;

public final class ClientServerOptimizationStats {
    private static final Object LOCK = new Object();

    private static long totalRawBytes;
    private static long totalBatchedBytes;
    private static long totalBatchCount;
    private static long totalPacketCount;
    private static long totalBypassBytes;
    private static long totalChunkCacheSavedBytes;
    private static long totalChunkCacheHitPackets;
    private static long totalChunkCacheRefreshPackets;
    private static long recentRawBytes;
    private static long recentBatchedBytes;
    private static long recentBatchCount;
    private static long recentPacketCount;
    private static long recentBypassBytes;
    private static long recentChunkCacheSavedBytes;
    private static int activeConnections;
    private static String algorithmId = "-";
    private static long lastUpdateMillis;

    private ClientServerOptimizationStats() {
    }

    public static void update(
            long totalRaw,
            long totalBatched,
            long totalBatches,
            long totalPackets,
            long totalBypass,
            long totalChunkCacheSaved,
            long totalChunkCacheHits,
            long totalChunkCacheRefreshes,
            long recentRaw,
            long recentBatched,
            long recentBatches,
            long recentPackets,
            long recentBypass,
            long recentChunkCacheSaved,
            int connections,
            String algorithm
    ) {
        synchronized (LOCK) {
            totalRawBytes = Math.max(totalRaw, 0L);
            totalBatchedBytes = Math.max(totalBatched, 0L);
            totalBatchCount = Math.max(totalBatches, 0L);
            totalPacketCount = Math.max(totalPackets, 0L);
            totalBypassBytes = Math.max(totalBypass, 0L);
            totalChunkCacheSavedBytes = Math.max(totalChunkCacheSaved, 0L);
            totalChunkCacheHitPackets = Math.max(totalChunkCacheHits, 0L);
            totalChunkCacheRefreshPackets = Math.max(totalChunkCacheRefreshes, 0L);
            recentRawBytes = Math.max(recentRaw, 0L);
            recentBatchedBytes = Math.max(recentBatched, 0L);
            recentBatchCount = Math.max(recentBatches, 0L);
            recentPacketCount = Math.max(recentPackets, 0L);
            recentBypassBytes = Math.max(recentBypass, 0L);
            recentChunkCacheSavedBytes = Math.max(recentChunkCacheSaved, 0L);
            activeConnections = Math.max(connections, 0);
            algorithmId = algorithm == null || algorithm.isBlank() ? "-" : algorithm;
            lastUpdateMillis = System.currentTimeMillis();
        }
    }

    public static Snapshot snapshot() {
        synchronized (LOCK) {
            return new Snapshot(
                    totalRawBytes,
                    totalBatchedBytes,
                    totalBatchCount,
                    totalPacketCount,
                    totalBypassBytes,
                    totalChunkCacheSavedBytes,
                    totalChunkCacheHitPackets,
                    totalChunkCacheRefreshPackets,
                    recentRawBytes,
                    recentBatchedBytes,
                    recentBatchCount,
                    recentPacketCount,
                    recentBypassBytes,
                    recentChunkCacheSavedBytes,
                    activeConnections,
                    algorithmId,
                    lastUpdateMillis
            );
        }
    }

    public record Snapshot(
            long totalRawBytes,
            long totalBatchedBytes,
            long totalBatchCount,
            long totalPacketCount,
            long totalBypassBytes,
            long totalChunkCacheSavedBytes,
            long totalChunkCacheHitPackets,
            long totalChunkCacheRefreshPackets,
            long recentRawBytes,
            long recentBatchedBytes,
            long recentBatchCount,
            long recentPacketCount,
            long recentBypassBytes,
            long recentChunkCacheSavedBytes,
            int activeConnections,
            String algorithmId,
            long lastUpdateMillis
    ) {
        public boolean hasData() {
            return totalBatchCount > 0L || totalBypassBytes > 0L || totalChunkCacheSavedBytes > 0L;
        }
    }
}
