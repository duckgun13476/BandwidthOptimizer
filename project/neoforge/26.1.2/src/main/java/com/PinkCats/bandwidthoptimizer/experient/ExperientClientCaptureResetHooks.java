package com.PinkCats.bandwidthoptimizer.experient;

import net.neoforged.neoforge.client.event.ClientTickEvent;

import com.PinkCats.bandwidthoptimizer.Bandwidthoptimizer;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.Mod;
import net.neoforged.fml.common.EventBusSubscriber;

@EventBusSubscriber(modid = Bandwidthoptimizer.MODID, value = Dist.CLIENT)
public final class ExperientClientCaptureResetHooks {

    private ExperientClientCaptureResetHooks() {}

    @SubscribeEvent
    public static void onClientTick(ClientTickEvent.Post event) {
        ExperientCaptureResetCoordinator.applyPendingResetIfNeeded("client");
    }
}
