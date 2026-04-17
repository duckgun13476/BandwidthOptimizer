package com.PinkCats.bandwidthoptimizer.network.client;

import com.PinkCats.bandwidthoptimizer.Bandwidthoptimizer;
import com.PinkCats.bandwidthoptimizer.Config;

public final class ClientRespawnTransportBarrier {

    private static final long DROP_WINDOW_MILLIS = 5_000L;

    private static volatile long dropUntilMillis;

    private ClientRespawnTransportBarrier() {
    }

    public static void onRespawnBoundary() {
        long now = System.currentTimeMillis();
        dropUntilMillis = now + DROP_WINDOW_MILLIS;
        ClientPlayPacketBatchHandler.resetForRespawnBoundary();
        ClientChunkCacheManager.resetForRespawnBoundary();
        if (Config.enableOptimizerStatsLogs && Config.enableTestMode) {
            Bandwidthoptimizer.LOGGER.info(
                    "[TransportBarrier][Client] respawn boundary armed for {} ms",
                    DROP_WINDOW_MILLIS
            );
        }
    }

    public static boolean shouldDropInternalTransport() {
        return System.currentTimeMillis() < dropUntilMillis;
    }
}
