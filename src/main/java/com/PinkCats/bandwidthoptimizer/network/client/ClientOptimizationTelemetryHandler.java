package com.PinkCats.bandwidthoptimizer.network.client;

import com.PinkCats.bandwidthoptimizer.network.message.ServerOptimizationTelemetryPacket;

public final class ClientOptimizationTelemetryHandler {

    private ClientOptimizationTelemetryHandler() {
    }

    public static void handle(ServerOptimizationTelemetryPacket packet) {
        ClientOptimizationStats.updateBypassTelemetry(
                packet.bypassTotalBytes(),
                packet.bypassTotalPackets(),
                packet.bypassRecentBytes(),
                packet.bypassRecentPackets(),
                packet.chunkCacheSavedTotalBytes(),
                packet.chunkCacheSavedRecentBytes(),
                packet.chunkCacheHitTotalPackets(),
                packet.chunkCacheHitRecentPackets(),
                packet.chunkCacheRefreshTotalPackets(),
                packet.chunkCacheRefreshRecentPackets()
        );
    }
}
