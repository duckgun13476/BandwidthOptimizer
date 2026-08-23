package com.PinkCats.bandwidthoptimizer.gate;

import com.PinkCats.bandwidthoptimizer.Bandwidthoptimizer;
import com.PinkCats.bandwidthoptimizer.connection.ClientReconnectCoordinator;
import com.PinkCats.bandwidthoptimizer.connection.ClientReconnectPlatform;
import net.minecraft.client.Minecraft;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.client.event.ClientPlayerNetworkEvent;
import net.neoforged.neoforge.client.event.ClientTickEvent;

@EventBusSubscriber(modid = Bandwidthoptimizer.MODID, value = Dist.CLIENT)
public final class IdleGateClientTickHooks {

    private static boolean reconnectInstalled;

    private IdleGateClientTickHooks() {}

    @SubscribeEvent
    public static void onClientTick(ClientTickEvent.Post event) {
        ensureReconnectInstalled();
        ClientReconnectCoordinator.onClientTick();
        IdleGateClientController.onClientTick(Minecraft.getInstance());
    }

    @SubscribeEvent
    public static void onLoggingOut(ClientPlayerNetworkEvent.LoggingOut event) {
        IdleGateClientController.onDisconnected();
    }

    private static void ensureReconnectInstalled() {
        if (!reconnectInstalled) {
            ClientReconnectCoordinator.install(ClientReconnectPlatform.INSTANCE);
            reconnectInstalled = true;
        }
    }
}
