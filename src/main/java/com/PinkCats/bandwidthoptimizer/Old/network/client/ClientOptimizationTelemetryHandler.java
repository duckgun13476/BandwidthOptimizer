package com.PinkCats.bandwidthoptimizer.Old.network.client;

import com.PinkCats.bandwidthoptimizer.Old.network.message.ServerOptimizationTelemetryPacket;

public final class ClientOptimizationTelemetryHandler {

    private ClientOptimizationTelemetryHandler() {
    }

    public static void handle(ServerOptimizationTelemetryPacket packet) {
        ClientOptimizationStats.updateBypassTelemetry(
                packet.bypassTotalBytes(),
                packet.bypassTotalPackets(),
                packet.bypassRecentBytes(),
                packet.bypassRecentPackets(),
                packet.chunkCacheRawTotalBytes(),
                packet.chunkCacheRawRecentBytes(),
                packet.chunkCacheSentTotalBytes(),
                packet.chunkCacheSentRecentBytes(),
                packet.chunkCacheHitTotalPackets(),
                packet.chunkCacheHitRecentPackets(),
                packet.chunkCacheRefreshTotalPackets(),
                packet.chunkCacheRefreshRecentPackets()
        );
    }
}
