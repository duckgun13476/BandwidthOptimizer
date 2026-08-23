package com.PinkCats.bandwidthoptimizer.gate;

import com.PinkCats.bandwidthoptimizer.Bandwidthoptimizer;
import com.PinkCats.bandwidthoptimizer.connection.ClientReconnectCoordinator;
import com.PinkCats.bandwidthoptimizer.connection.ClientReconnectPlatform;
import net.minecraft.client.Minecraft;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.client.event.ClientPlayerNetworkEvent;
import net.minecraftforge.event.TickEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;

@Mod.EventBusSubscriber(modid = Bandwidthoptimizer.MODID, value = Dist.CLIENT, bus = Mod.EventBusSubscriber.Bus.FORGE)
public final class IdleGateClientTickHooks {

    private static boolean reconnectInstalled;

    private IdleGateClientTickHooks() {}

    @SubscribeEvent
    public static void onClientTick(TickEvent.ClientTickEvent event) {
        if (event.phase == TickEvent.Phase.END) {
            ensureReconnectInstalled();
            ClientReconnectCoordinator.onClientTick();
            IdleGateClientController.onClientTick(Minecraft.getInstance());
        }
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
