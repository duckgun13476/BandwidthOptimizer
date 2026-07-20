package com.PinkCats.bandwidthoptimizer.idle;

import com.PinkCats.bandwidthoptimizer.Bandwidthoptimizer;
import net.minecraftforge.event.entity.player.PlayerEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;

@Mod.EventBusSubscriber(modid = Bandwidthoptimizer.MODID, bus = Mod.EventBusSubscriber.Bus.FORGE)
public final class IdleGateServerLifecycleHooks {

    private IdleGateServerLifecycleHooks() {}

    @SubscribeEvent
    public static void onPlayerLoggedOut(PlayerEvent.PlayerLoggedOutEvent event) {
        if (event.getEntity() instanceof net.minecraft.server.level.ServerPlayer player) {
            IdleGateServerState.onPlayerLoggedOut(player);
        }
    }
}
