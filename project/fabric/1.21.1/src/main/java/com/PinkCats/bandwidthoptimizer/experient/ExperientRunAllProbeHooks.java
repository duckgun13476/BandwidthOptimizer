package com.PinkCats.bandwidthoptimizer.experient;

import com.PinkCats.bandwidthoptimizer.Bandwidthoptimizer;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;

import java.io.IOException;
import java.nio.file.Files;


public final class ExperientRunAllProbeHooks {


    private ExperientRunAllProbeHooks() {}

    public static void onPlayerLoggedIn(ServerPlayer serverPlayer) {
        if (!ExperientRuntimeFlags.isEnabled()) {
            return;
        }

        RunAllProbeFiles.markServerPlayerLoggedIn(serverPlayer);
    }

    public static void onServerTick(MinecraftServer server) {
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

