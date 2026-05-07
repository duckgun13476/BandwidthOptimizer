package com.PinkCats.bandwidthoptimizer.server.stat;

import com.PinkCats.bandwidthoptimizer.Bandwidthoptimizer;
import net.minecraftforge.event.server.ServerStoppingEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;

@Mod.EventBusSubscriber(modid = Bandwidthoptimizer.MODID, bus = Mod.EventBusSubscriber.Bus.FORGE)
public final class ServerBandwidthStatsLifecycleHooks {

    private ServerBandwidthStatsLifecycleHooks() {
    }

    @SubscribeEvent
    public static void onServerStopping(ServerStoppingEvent event) {
        ServerBandwidthStatsPersistence.flushOnServerStopping(event.getServer());
    }
}
