package com.PinkCats.bandwidthoptimizer.experient;

import com.PinkCats.bandwidthoptimizer.Bandwidthoptimizer;
import com.PinkCats.bandwidthoptimizer.chunk.classify.packet.ChunkPacketCoordinate;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerPlayer;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

public final class RunAllProbeFiles {

    public static final Path SERVER_PLAYER_LOGGED_IN_MARKER = Path.of("bo-runall-player-logged-in.marker");
    public static final Path SERVER_WATCH_BOUNDARY_REFRESH_PATCH_MARKER =
            Path.of("bo-watch-boundary-refresh-patch.marker");

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

    public static void markWatchBoundaryRefreshPatchCompleted(
            ServerPlayer serverPlayer,
            ChunkPacketCoordinate coordinate,
            BlockPos targetBlockPos,
            long initialFullSnapshotVersion,
            long finalFullSnapshotVersion,
            String originalBlockStateName,
            String mutatedBlockStateName,
            String initialFullSnapshotHash,
            String finalFullSnapshotHash
    ) {
        if (serverPlayer == null
                || coordinate == null
                || !coordinate.present()
                || targetBlockPos == null
                || !ExperientRuntimeFlags.isEnabled()) {
            return;
        }

        String markerText = "captured_at_ms=" + System.currentTimeMillis()
                + System.lineSeparator()
                + "player_name=" + serverPlayer.getGameProfile().getName()
                + System.lineSeparator()
                + "player_uuid=" + serverPlayer.getUUID()
                + System.lineSeparator()
                + "target_chunk_x=" + coordinate.chunkX()
                + System.lineSeparator()
                + "target_chunk_z=" + coordinate.chunkZ()
                + System.lineSeparator()
                + "target_block_x=" + targetBlockPos.getX()
                + System.lineSeparator()
                + "target_block_y=" + targetBlockPos.getY()
                + System.lineSeparator()
                + "target_block_z=" + targetBlockPos.getZ()
                + System.lineSeparator()
                + "initial_full_version=" + Math.max(initialFullSnapshotVersion, 0L)
                + System.lineSeparator()
                + "final_full_version=" + Math.max(finalFullSnapshotVersion, 0L)
                + System.lineSeparator()
                + "mutated_from=" + (originalBlockStateName == null ? "" : originalBlockStateName)
                + System.lineSeparator()
                + "mutated_to=" + (mutatedBlockStateName == null ? "" : mutatedBlockStateName)
                + System.lineSeparator()
                + "initial_full_hash=" + (initialFullSnapshotHash == null ? "" : initialFullSnapshotHash)
                + System.lineSeparator()
                + "final_full_hash=" + (finalFullSnapshotHash == null ? "" : finalFullSnapshotHash)
                + System.lineSeparator();

        try {
            Files.writeString(
                    SERVER_WATCH_BOUNDARY_REFRESH_PATCH_MARKER,
                    markerText,
                    StandardCharsets.UTF_8
            );
        } catch (IOException exception) {
            Bandwidthoptimizer.LOGGER.warn(
                    "[RunAllProbe] Failed to write watch-boundary marker: {}",
                    SERVER_WATCH_BOUNDARY_REFRESH_PATCH_MARKER.toAbsolutePath(),
                    exception
            );
        }
    }
}
