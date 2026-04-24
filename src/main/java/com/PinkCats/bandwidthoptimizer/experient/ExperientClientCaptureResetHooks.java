package com.PinkCats.bandwidthoptimizer.experient;

import com.PinkCats.bandwidthoptimizer.Bandwidthoptimizer;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.event.TickEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;

@Mod.EventBusSubscriber(modid = Bandwidthoptimizer.MODID, value = Dist.CLIENT, bus = Mod.EventBusSubscriber.Bus.FORGE)
public final class ExperientClientCaptureResetHooks {

    private ExperientClientCaptureResetHooks() {}

    @SubscribeEvent
    public static void onClientTick(TickEvent.ClientTickEvent event) {
        if (event.phase != TickEvent.Phase.END) {
            return;
        }
        ExperientCaptureResetCoordinator.applyPendingResetIfNeeded("client");
    }
}
