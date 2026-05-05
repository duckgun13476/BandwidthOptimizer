package com.PinkCats.bandwidthoptimizer.experient;

import net.neoforged.neoforge.event.tick.ServerTickEvent;

import com.PinkCats.bandwidthoptimizer.Bandwidthoptimizer;
import net.minecraft.server.level.ServerPlayer;
import net.neoforged.neoforge.event.entity.player.PlayerEvent;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.Mod;
import net.neoforged.fml.common.EventBusSubscriber;

import java.io.IOException;
import java.nio.file.Files;


@EventBusSubscriber(modid = Bandwidthoptimizer.MODID)
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

    @SubscribeEvent
    public static void onServerTick(ServerTickEvent.Post event) {
        if (!ExperientRuntimeFlags.isEnabled()) {
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
