package com.PinkCats.bandwidthoptimizer.experient;

import com.PinkCats.bandwidthoptimizer.Bandwidthoptimizer;
import net.minecraft.server.level.ServerPlayer;
import net.minecraftforge.event.entity.player.PlayerEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;


@Mod.EventBusSubscriber(modid = Bandwidthoptimizer.MODID, bus = Mod.EventBusSubscriber.Bus.FORGE)
public final class ExperientRunAllProbeHooks {


    private ExperientRunAllProbeHooks() {}

    @SubscribeEvent
    public static void onPlayerLoggedIn(PlayerEvent.PlayerLoggedInEvent event) {
        if (!ExperientRuntimeFlags.isEnabled()) {
            return;
        }

        if (event.getEntity() instanceof ServerPlayer serverPlayer) {
            RunAllProbeFiles.markServerPlayerLoggedIn(serverPlayer);
        }
    }
}
