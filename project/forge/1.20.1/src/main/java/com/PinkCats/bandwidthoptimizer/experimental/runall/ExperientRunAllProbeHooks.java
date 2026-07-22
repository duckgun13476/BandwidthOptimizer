package com.PinkCats.bandwidthoptimizer.experimental.runall;

import com.PinkCats.bandwidthoptimizer.Bandwidthoptimizer;
import net.minecraft.server.level.ServerPlayer;
import net.minecraftforge.event.TickEvent;
import net.minecraftforge.event.entity.player.PlayerEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;

import java.io.IOException;
import java.nio.file.Files;


@Mod.EventBusSubscriber(modid = Bandwidthoptimizer.MODID, bus = Mod.EventBusSubscriber.Bus.FORGE)
public final class ExperientRunAllProbeHooks {


    private ExperientRunAllProbeHooks() {}

    @SubscribeEvent
    public static void onPlayerLoggedIn(PlayerEvent.PlayerLoggedInEvent event) {
        if (!com.PinkCats.bandwidthoptimizer.experimental.runtime.ExperientRuntimeFlags.isEnabled()) {
            return;
        }

        if (event.getEntity() instanceof ServerPlayer serverPlayer) {
            RunAllProbeFiles.markServerPlayerLoggedIn(serverPlayer);
        }
    }

    @SubscribeEvent
    public static void onServerTick(TickEvent.ServerTickEvent event) {
        if (event.phase != TickEvent.Phase.END || !com.PinkCats.bandwidthoptimizer.experimental.runtime.ExperientRuntimeFlags.isEnabled()) {
            return;
        }

        if (ExperientCaptureResetCoordinator.applyPendingResetIfNeeded("server")) {
            try {
                Files.deleteIfExists(RunAllProbeFiles.SERVER_PLAYER_LOGGED_IN_MARKER);
                Files.deleteIfExists(RunAllProbeFiles.SERVER_WATCH_BOUNDARY_REFRESH_PATCH_MARKER);
            } catch (IOException ignored) {
            }
        }
    }
}
