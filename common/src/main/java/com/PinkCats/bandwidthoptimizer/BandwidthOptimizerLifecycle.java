package com.PinkCats.bandwidthoptimizer;

import com.PinkCats.bandwidthoptimizer.chunk.lifecycle.ChunkLifecycleCoordinator;
import com.PinkCats.bandwidthoptimizer.experient.ExperientChunkWatchEventTracker;
import com.PinkCats.bandwidthoptimizer.platform.TorqueNative;
import com.PinkCats.bandwidthoptimizer.server.stat.ServerBandwidthStatsRegistry;
import com.pinkcats.torque.layer.net.minecraft.world.level.ChunkPos;
import com.pinkcats.torque.layer.platform.Platform;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;

public final class BandwidthOptimizerLifecycle {
    private static boolean registered;

    private BandwidthOptimizerLifecycle() {}

    public static synchronized void register(Platform platform) {
        if (registered) {
            return;
        }
        registered = true;
        platform.lifecycle().onPlayerLogin(player -> {
            ServerPlayer serverPlayer = TorqueNative.serverPlayer(player);
            ServerBandwidthStatsRegistry.bindPlayer(serverPlayer);
            ExperientChunkWatchEventTracker.clearPlayer(serverPlayer);
            ChunkLifecycleCoordinator.onPlayerLogin(serverPlayer);
        });
        platform.lifecycle().onPlayerRespawn(player ->
                ChunkLifecycleCoordinator.onPlayerRespawn(TorqueNative.serverPlayer(player)));
        platform.lifecycle().onPlayerDimensionChange(player ->
                ChunkLifecycleCoordinator.onPlayerDimensionChange(TorqueNative.serverPlayer(player)));
        platform.lifecycle().onPlayerLogout(player -> {
            ServerPlayer serverPlayer = TorqueNative.serverPlayer(player);
            ChunkLifecycleCoordinator.onPlayerLogout(serverPlayer);
            ExperientChunkWatchEventTracker.clearPlayer(serverPlayer);
            ServerBandwidthStatsRegistry.unbindPlayer(serverPlayer);
        });
        platform.lifecycle().onChunkWatch((player, level, pos) ->
                ExperientChunkWatchEventTracker.recordWatch(TorqueNative.serverPlayer(player), nativeChunkPos(pos)));
        platform.lifecycle().onChunkUnwatch((player, level, pos) -> {
            ServerPlayer serverPlayer = TorqueNative.serverPlayer(player);
            net.minecraft.world.level.ChunkPos chunkPos = nativeChunkPos(pos);
            ExperientChunkWatchEventTracker.recordUnwatch(serverPlayer, chunkPos);
            ChunkLifecycleCoordinator.onPlayerStopWatchingChunk(serverPlayer, chunkPos, TorqueNative.serverLevel(level));
        });
        platform.lifecycle().onChunkUnload((level, pos) -> {
            ServerLevel serverLevel = TorqueNative.serverLevel(level);
            ChunkLifecycleCoordinator.onServerChunkUnload(serverLevel, nativeChunkPos(pos));
        });
    }

    private static net.minecraft.world.level.ChunkPos nativeChunkPos(ChunkPos pos) {
        return new net.minecraft.world.level.ChunkPos(pos.x(), pos.z());
    }
}
