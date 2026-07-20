package com.PinkCats.bandwidthoptimizer.idle;

import com.PinkCats.bandwidthoptimizer.Bandwidthoptimizer;
import net.minecraft.client.Minecraft;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.client.event.ClientPlayerNetworkEvent;
import net.neoforged.neoforge.client.event.ClientTickEvent;

@EventBusSubscriber(modid = Bandwidthoptimizer.MODID, value = Dist.CLIENT)
public final class IdleGateClientTickHooks {

    private IdleGateClientTickHooks() {}

    @SubscribeEvent
    public static void onClientTick(ClientTickEvent.Post event) {
        IdleGateClientController.onClientTick(Minecraft.getInstance());
    }

    @SubscribeEvent
    public static void onLoggingOut(ClientPlayerNetworkEvent.LoggingOut event) {
        IdleGateClientController.onDisconnected();
    }
}
