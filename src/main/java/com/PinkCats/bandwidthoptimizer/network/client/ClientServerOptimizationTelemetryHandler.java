package com.PinkCats.bandwidthoptimizer.network.client;

import com.PinkCats.bandwidthoptimizer.network.message.ServerOverallOptimizationTelemetryPacket;

public final class ClientServerOptimizationTelemetryHandler {

    private ClientServerOptimizationTelemetryHandler() {
    }

    public static void handle(ServerOverallOptimizationTelemetryPacket packet) {
        ClientServerOptimizationStats.update(
                packet.totalRawBytes(),
                packet.totalBatchedBytes(),
                packet.totalBatchCount(),
                packet.totalPacketCount(),
                packet.totalBypassBytes(),
                packet.totalChunkCacheRawBytes(),
                packet.totalChunkCacheSentBytes(),
                packet.totalChunkCacheHitPackets(),
                packet.totalChunkCacheRefreshPackets(),
                packet.recentRawBytes(),
                packet.recentBatchedBytes(),
                packet.recentBatchCount(),
                packet.recentPacketCount(),
                packet.recentBypassBytes(),
                packet.recentChunkCacheRawBytes(),
                packet.recentChunkCacheSentBytes(),
                packet.activeConnections(),
                packet.algorithmId()
        );
    }
}
