package com.PinkCats.bandwidthoptimizer.chunk.lifecycle;

import com.PinkCats.bandwidthoptimizer.chunk.state.peer.ChunkPeerStateManager;
import net.minecraft.server.level.ServerPlayer;

public final class ChunkLifecycleCoordinator {

    private ChunkLifecycleCoordinator() {}

    public static void onPlayerLogin(ServerPlayer player) {
        ChunkPeerStateManager.bumpPlayerEpoch(player, "login");
        ChunkPeerStateManager.attachPlayerEpochToChannel(player, "login");
    }

    public static void onPlayerRespawn(ServerPlayer player) {
        ChunkPeerStateManager.bumpPlayerEpoch(player, "respawn");
        ChunkPeerStateManager.attachPlayerEpochToChannel(player, "respawn");
    }

    public static void onPlayerDimensionChange(ServerPlayer player) {
        ChunkPeerStateManager.bumpPlayerEpoch(player, "dimension_change");
        ChunkPeerStateManager.attachPlayerEpochToChannel(player, "dimension_change");
    }

    public static void onPlayerLogout(ServerPlayer player) {
        ChunkPeerStateManager.clearPlayerState(player, "logout");
    }

    public static void prepareForClientRespawnBoundary(ServerPlayer player) {
        ChunkPeerStateManager.bumpPlayerEpoch(player, "prepare_client_respawn_boundary");
        ChunkPeerStateManager.attachPlayerEpochToChannel(player, "prepare_client_respawn_boundary");
    }
}
