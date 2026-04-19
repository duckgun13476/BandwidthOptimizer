package com.PinkCats.bandwidthoptimizer.experient;

import com.PinkCats.bandwidthoptimizer.Bandwidthoptimizer;
import net.minecraft.server.level.ServerPlayer;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

public final class RunAllProbeFiles {

    public static final Path SERVER_PLAYER_LOGGED_IN_MARKER = Path.of("bo-runall-player-logged-in.marker");

    private RunAllProbeFiles() {}

    public static void markServerPlayerLoggedIn(ServerPlayer serverPlayer) {
        if (serverPlayer == null || !ExperientRuntimeFlags.isEnabled()) {
            return;
        }

        String markerText = "captured_at_ms=" + System.currentTimeMillis()
                + System.lineSeparator()
                + "player_name=" + serverPlayer.getGameProfile().getName()
                + System.lineSeparator()
                + "player_uuid=" + serverPlayer.getUUID()
                + System.lineSeparator();

        try {
            Files.writeString(
                    SERVER_PLAYER_LOGGED_IN_MARKER,
                    markerText,
                    StandardCharsets.UTF_8
            );
        } catch (IOException exception) {
            Bandwidthoptimizer.LOGGER.warn(
                    "[RunAllProbe] Failed to write player-login marker: {}",
                    SERVER_PLAYER_LOGGED_IN_MARKER.toAbsolutePath(),
                    exception
            );
        }
    }
}
