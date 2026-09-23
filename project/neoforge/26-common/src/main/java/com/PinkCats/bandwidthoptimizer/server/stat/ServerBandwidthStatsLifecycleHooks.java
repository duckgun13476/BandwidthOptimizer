package com.PinkCats.bandwidthoptimizer.server.stat;

import com.PinkCats.bandwidthoptimizer.Bandwidthoptimizer;
import net.neoforged.neoforge.event.server.ServerStoppingEvent;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.Mod;
import net.neoforged.fml.common.EventBusSubscriber;

@EventBusSubscriber(modid = Bandwidthoptimizer.MODID)
public final class ServerBandwidthStatsLifecycleHooks {

    private ServerBandwidthStatsLifecycleHooks() {
    }

    @SubscribeEvent
    public static void onServerStopping(ServerStoppingEvent event) {
        ServerBandwidthStatsPersistence.flushOnServerStopping(event.getServer());
    }
}
