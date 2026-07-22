package com.PinkCats.bandwidthoptimizer;

import com.PinkCats.bandwidthoptimizer.chunk.lifecycle.ChunkLifecycleCoordinator;
import com.PinkCats.bandwidthoptimizer.experimental.watch.ExperientChunkWatchEventTracker;
import com.PinkCats.bandwidthoptimizer.server.stat.ServerBandwidthStatsRegistry;
import net.fabricmc.fabric.api.entity.event.v1.ServerEntityWorldChangeEvents;
import net.fabricmc.fabric.api.entity.event.v1.ServerPlayerEvents;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerChunkEvents;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.chunk.LevelChunk;

public final class FabricBandwidthOptimizerLifecycle {

    private static boolean registered;

    private FabricBandwidthOptimizerLifecycle() {}

    public static synchronized void register() {
        if (registered) {
            return;
        }

        registered = true;
        ServerPlayerEvents.AFTER_RESPAWN.register((oldPlayer, newPlayer, alive) ->
                ChunkLifecycleCoordinator.onPlayerRespawn(newPlayer));
        ServerEntityWorldChangeEvents.AFTER_PLAYER_CHANGE_WORLD.register((player, origin, destination) ->
                ChunkLifecycleCoordinator.onPlayerDimensionChange(player));
        ServerChunkEvents.CHUNK_UNLOAD.register((level, chunk) ->
                onChunkUnload(level, chunk));
    }

    public static void onPlayerLogin(ServerPlayer serverPlayer) {
        if (serverPlayer == null) {
            return;
        }

        ServerBandwidthStatsRegistry.bindPlayer(serverPlayer);
        com.PinkCats.bandwidthoptimizer.experimental.watch.ExperientChunkWatchEventTracker.clearPlayer(serverPlayer);
        ChunkLifecycleCoordinator.onPlayerLogin(serverPlayer);
    }

    public static void onPlayerLogout(ServerPlayer serverPlayer) {
        if (serverPlayer == null) {
            return;
        }

        ChunkLifecycleCoordinator.onPlayerLogout(serverPlayer);
        com.PinkCats.bandwidthoptimizer.experimental.watch.ExperientChunkWatchEventTracker.clearPlayer(serverPlayer);
        ServerBandwidthStatsRegistry.unbindPlayer(serverPlayer);
    }

    private static void onChunkUnload(net.minecraft.server.level.ServerLevel level, LevelChunk chunk) {
        if (level == null || chunk == null) {
            return;
        }

        ChunkLifecycleCoordinator.onServerChunkUnload(level, chunk.getPos());
    }

    public static void onPlayerWatchChunk(ServerPlayer serverPlayer, ChunkPos chunkPos) {
        if (serverPlayer == null || chunkPos == null) {
            return;
        }

        com.PinkCats.bandwidthoptimizer.experimental.watch.ExperientChunkWatchEventTracker.recordWatch(serverPlayer, chunkPos);
    }

    public static void onPlayerUnwatchChunk(ServerPlayer serverPlayer, ChunkPos chunkPos) {
        if (serverPlayer == null || chunkPos == null) {
            return;
        }

        com.PinkCats.bandwidthoptimizer.experimental.watch.ExperientChunkWatchEventTracker.recordUnwatch(serverPlayer, chunkPos);
        ChunkLifecycleCoordinator.onPlayerStopWatchingChunk(serverPlayer, chunkPos, serverPlayer.serverLevel());
    }
}
