package com.PinkCats.bandwidthoptimizer.chunk.lifecycle;

import com.PinkCats.bandwidthoptimizer.Bandwidthoptimizer;
import com.PinkCats.bandwidthoptimizer.chunk.classify.packet.ChunkPacketCoordinate;
import com.PinkCats.bandwidthoptimizer.chunk.PeerState.ChunkPeerChunkStateSnapshot;
import com.PinkCats.bandwidthoptimizer.chunk.PeerState.ChunkPeerStateManager;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.level.ChunkPos;

import java.util.List;

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


    public static void onPlayerStopWatchingChunk(ServerPlayer player, ChunkPos chunkPos, ServerLevel level) {
        retainPlayerChunkBoundary(player, chunkPos, "watch_remove");
    }

    public static void onServerChunkUnload(ServerLevel level, ChunkPos chunkPos) {
        if (level == null || chunkPos == null) {
            return;
        }

        List<ServerPlayer> watchingPlayers = level.getChunkSource().chunkMap.getPlayers(chunkPos, false);
        if (watchingPlayers.isEmpty()) {
            return;
        }

        for (ServerPlayer watchingPlayer : watchingPlayers) {
            retainPlayerChunkBoundary(watchingPlayer, chunkPos, "level_chunk_unload");
        }
    }


    private static void retainPlayerChunkBoundary(ServerPlayer player, ChunkPos chunkPos, String reason) {
        if (player == null || chunkPos == null) {
            return;
        }

        ChunkPacketCoordinate coordinate = ChunkPacketCoordinate.ofChunk(chunkPos.x, chunkPos.z);
        ChunkPeerChunkStateSnapshot knownChunkSnapshot = ChunkPeerStateManager.snapshotPlayerChunk(player, coordinate);
        if (!shouldInvalidateLifecycleChunk(knownChunkSnapshot)) {
            return;
        }

        ChunkPeerChunkStateSnapshot retainedChunkSnapshot =
                ChunkPeerStateManager.retainPlayerChunkForWatchBoundary(player, coordinate, reason);
        Bandwidthoptimizer.LOGGER.info(
                "[ChunkLifecycle][Retain] player={}, uuid={}, reason={}, chunk={}, snapshotBefore={}, snapshotAfter={}",
                player.getGameProfile().getName(),
                player.getUUID(),
                reason,
                coordinate.logText(),
                knownChunkSnapshot.summaryText(),
                retainedChunkSnapshot == null ? "<missing>" : retainedChunkSnapshot.summaryText()
        );
    }

    private static boolean shouldInvalidateLifecycleChunk(ChunkPeerChunkStateSnapshot knownChunkSnapshot) {
        return knownChunkSnapshot != null
                && (knownChunkSnapshot.knownSnapshotPublished() || knownChunkSnapshot.receiverSnapshotAcknowledged());
    }
}
