package com.PinkCats.bandwidthoptimizer.network.client;

public final class ClientServerOptimizationStats {
    private static final Object LOCK = new Object();

    private static long totalRawBytes;
    private static long totalBatchedBytes;
    private static long totalBatchCount;
    private static long totalPacketCount;
    private static long totalBypassBytes;
    private static long totalChunkCacheRawBytes;
    private static long totalChunkCacheSentBytes;
    private static long totalChunkCacheHitPackets;
    private static long totalChunkCacheRefreshPackets;
    private static long recentRawBytes;
    private static long recentBatchedBytes;
    private static long recentBatchCount;
    private static long recentPacketCount;
    private static long recentBypassBytes;
    private static long recentChunkCacheRawBytes;
    private static long recentChunkCacheSentBytes;
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
            long totalChunkCacheRaw,
            long totalChunkCacheSent,
            long totalChunkCacheHits,
            long totalChunkCacheRefreshes,
            long recentRaw,
            long recentBatched,
            long recentBatches,
            long recentPackets,
            long recentBypass,
            long recentChunkCacheRaw,
            long recentChunkCacheSent,
            int connections,
            String algorithm
    ) {
        synchronized (LOCK) {
            totalRawBytes = Math.max(totalRaw, 0L);
            totalBatchedBytes = Math.max(totalBatched, 0L);
            totalBatchCount = Math.max(totalBatches, 0L);
            totalPacketCount = Math.max(totalPackets, 0L);
            totalBypassBytes = Math.max(totalBypass, 0L);
            totalChunkCacheRawBytes = Math.max(totalChunkCacheRaw, 0L);
            totalChunkCacheSentBytes = Math.max(totalChunkCacheSent, 0L);
            totalChunkCacheHitPackets = Math.max(totalChunkCacheHits, 0L);
            totalChunkCacheRefreshPackets = Math.max(totalChunkCacheRefreshes, 0L);
            recentRawBytes = Math.max(recentRaw, 0L);
            recentBatchedBytes = Math.max(recentBatched, 0L);
            recentBatchCount = Math.max(recentBatches, 0L);
            recentPacketCount = Math.max(recentPackets, 0L);
            recentBypassBytes = Math.max(recentBypass, 0L);
            recentChunkCacheRawBytes = Math.max(recentChunkCacheRaw, 0L);
            recentChunkCacheSentBytes = Math.max(recentChunkCacheSent, 0L);
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
                    totalChunkCacheRawBytes,
                    totalChunkCacheSentBytes,
                    totalChunkCacheHitPackets,
                    totalChunkCacheRefreshPackets,
                    recentRawBytes,
                    recentBatchedBytes,
                    recentBatchCount,
                    recentPacketCount,
                    recentBypassBytes,
                    recentChunkCacheRawBytes,
                    recentChunkCacheSentBytes,
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
            long totalChunkCacheRawBytes,
            long totalChunkCacheSentBytes,
            long totalChunkCacheHitPackets,
            long totalChunkCacheRefreshPackets,
            long recentRawBytes,
            long recentBatchedBytes,
            long recentBatchCount,
            long recentPacketCount,
            long recentBypassBytes,
            long recentChunkCacheRawBytes,
            long recentChunkCacheSentBytes,
            int activeConnections,
            String algorithmId,
            long lastUpdateMillis
    ) {
        public boolean hasData() {
            return totalBatchCount > 0L
                    || totalBypassBytes > 0L
                    || totalChunkCacheRawBytes > 0L
                    || totalChunkCacheSentBytes > 0L
                    || totalChunkCacheHitPackets > 0L
                    || totalChunkCacheRefreshPackets > 0L;
        }
    }
}
