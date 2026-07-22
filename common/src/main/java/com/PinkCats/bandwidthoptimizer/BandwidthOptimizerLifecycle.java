package com.PinkCats.bandwidthoptimizer;

import com.PinkCats.bandwidthoptimizer.chunk.lifecycle.ChunkLifecycleCoordinator;
import com.PinkCats.bandwidthoptimizer.gate.integration.create.CreateBlockEntityUpdateGate;
import com.PinkCats.bandwidthoptimizer.gate.recovery.IdleGateRecoveryRegistry;
import com.PinkCats.bandwidthoptimizer.experimental.watch.ExperientChunkWatchEventTracker;
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
            com.PinkCats.bandwidthoptimizer.experimental.watch.ExperientChunkWatchEventTracker.clearPlayer(serverPlayer);
            ChunkLifecycleCoordinator.onPlayerLogin(serverPlayer);
            CreateBlockEntityUpdateGate.bindPlayer(serverPlayer);
        });
        platform.lifecycle().onPlayerRespawn(player -> {
            ServerPlayer serverPlayer = TorqueNative.serverPlayer(player);
            CreateBlockEntityUpdateGate.clearPlayer(serverPlayer, "respawn");
            ChunkLifecycleCoordinator.onPlayerRespawn(serverPlayer);
            CreateBlockEntityUpdateGate.bindPlayer(serverPlayer);
        });
        platform.lifecycle().onPlayerDimensionChange(player -> {
            ServerPlayer serverPlayer = TorqueNative.serverPlayer(player);
            CreateBlockEntityUpdateGate.clearPlayer(serverPlayer, "dimension_change");
            ChunkLifecycleCoordinator.onPlayerDimensionChange(serverPlayer);
            CreateBlockEntityUpdateGate.bindPlayer(serverPlayer);
        });
        platform.lifecycle().onPlayerLogout(player -> {
            ServerPlayer serverPlayer = TorqueNative.serverPlayer(player);
            CreateBlockEntityUpdateGate.clearPlayer(serverPlayer, "logout");
            ChunkLifecycleCoordinator.onPlayerLogout(serverPlayer);
            com.PinkCats.bandwidthoptimizer.experimental.watch.ExperientChunkWatchEventTracker.clearPlayer(serverPlayer);
            ServerBandwidthStatsRegistry.unbindPlayer(serverPlayer);
        });
        platform.lifecycle().onChunkWatch((player, level, pos) ->
                com.PinkCats.bandwidthoptimizer.experimental.watch.ExperientChunkWatchEventTracker.recordWatch(TorqueNative.serverPlayer(player), nativeChunkPos(pos)));
        platform.lifecycle().onChunkUnwatch((player, level, pos) -> {
            ServerPlayer serverPlayer = TorqueNative.serverPlayer(player);
            net.minecraft.world.level.ChunkPos chunkPos = nativeChunkPos(pos);
            com.PinkCats.bandwidthoptimizer.experimental.watch.ExperientChunkWatchEventTracker.recordUnwatch(serverPlayer, chunkPos);
            CreateBlockEntityUpdateGate.dropPendingChunk(serverPlayer, chunkPos, "watch_remove");
            IdleGateRecoveryRegistry.discardChunk(serverPlayer, chunkPos);
            ChunkLifecycleCoordinator.onPlayerStopWatchingChunk(serverPlayer, chunkPos, TorqueNative.serverLevel(level));
        });
        platform.lifecycle().onChunkUnload((level, pos) -> {
            ServerLevel serverLevel = TorqueNative.serverLevel(level);
            ChunkLifecycleCoordinator.onServerChunkUnload(serverLevel, nativeChunkPos(pos));
        });
        platform.lifecycle().onServerTickEnd(() -> {
            IdleGateRecoveryRegistry.onServerTick();
            CreateBlockEntityUpdateGate.onServerTick();
        });
    }

    private static net.minecraft.world.level.ChunkPos nativeChunkPos(ChunkPos pos) {
        return new net.minecraft.world.level.ChunkPos(pos.x(), pos.z());
    }
}
