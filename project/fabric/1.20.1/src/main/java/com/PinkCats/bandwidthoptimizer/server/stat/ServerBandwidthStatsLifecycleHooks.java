package com.PinkCats.bandwidthoptimizer.server.stat;

import net.minecraft.server.MinecraftServer;

public final class ServerBandwidthStatsLifecycleHooks {

    private ServerBandwidthStatsLifecycleHooks() {
    }

    public static void onServerStopping(MinecraftServer server) {
        ServerBandwidthStatsPersistence.flushOnServerStopping(server);
    }
}

