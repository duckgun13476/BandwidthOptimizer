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
            onPlayerLogin(TorqueNative.serverPlayer(player));
        });
        platform.lifecycle().onPlayerRespawn(player -> {
            onPlayerRespawn(TorqueNative.serverPlayer(player));
        });
        platform.lifecycle().onPlayerDimensionChange(player -> {
            onPlayerDimensionChange(TorqueNative.serverPlayer(player));
        });
        platform.lifecycle().onPlayerLogout(player -> {
            onPlayerLogout(TorqueNative.serverPlayer(player));
        });
        platform.lifecycle().onChunkWatch((player, level, pos) ->
                onChunkWatch(TorqueNative.serverPlayer(player), nativeChunkPos(pos)));
        platform.lifecycle().onChunkUnwatch((player, level, pos) -> {
            onChunkUnwatch(TorqueNative.serverPlayer(player), TorqueNative.serverLevel(level), nativeChunkPos(pos));
        });
        platform.lifecycle().onChunkUnload((level, pos) -> {
            onChunkUnload(TorqueNative.serverLevel(level), nativeChunkPos(pos));
        });
        platform.lifecycle().onServerTickEnd(() -> {
            onServerTickEnd();
        });
    }

    public static void onPlayerLogin(ServerPlayer player) {
        ServerBandwidthStatsRegistry.bindPlayer(player);
        ExperientChunkWatchEventTracker.clearPlayer(player);
        ChunkLifecycleCoordinator.onPlayerLogin(player);
        CreateBlockEntityUpdateGate.bindPlayer(player);
    }

    public static void onPlayerRespawn(ServerPlayer player) {
        CreateBlockEntityUpdateGate.clearPlayer(player, "respawn");
        ChunkLifecycleCoordinator.onPlayerRespawn(player);
        CreateBlockEntityUpdateGate.bindPlayer(player);
    }

    public static void onPlayerDimensionChange(ServerPlayer player) {
        CreateBlockEntityUpdateGate.clearPlayer(player, "dimension_change");
        ChunkLifecycleCoordinator.onPlayerDimensionChange(player);
        CreateBlockEntityUpdateGate.bindPlayer(player);
    }

    public static void onPlayerLogout(ServerPlayer player) {
        CreateBlockEntityUpdateGate.clearPlayer(player, "logout");
        ChunkLifecycleCoordinator.onPlayerLogout(player);
        ExperientChunkWatchEventTracker.clearPlayer(player);
        ServerBandwidthStatsRegistry.unbindPlayer(player);
    }

    public static void onChunkWatch(ServerPlayer player, net.minecraft.world.level.ChunkPos chunkPos) {
        ExperientChunkWatchEventTracker.recordWatch(player, chunkPos);
    }

    public static void onChunkUnwatch(
            ServerPlayer player,
            ServerLevel level,
            net.minecraft.world.level.ChunkPos chunkPos
    ) {
        ExperientChunkWatchEventTracker.recordUnwatch(player, chunkPos);
        CreateBlockEntityUpdateGate.dropPendingChunk(player, chunkPos, "watch_remove");
        IdleGateRecoveryRegistry.discardChunk(player, chunkPos);
        ChunkLifecycleCoordinator.onPlayerStopWatchingChunk(player, chunkPos, level);
    }

    public static void onChunkUnload(ServerLevel level, net.minecraft.world.level.ChunkPos chunkPos) {
        ChunkLifecycleCoordinator.onServerChunkUnload(level, chunkPos);
    }

    public static void onServerTickEnd() {
        IdleGateRecoveryRegistry.onServerTick();
        CreateBlockEntityUpdateGate.onServerTick();
    }

    private static net.minecraft.world.level.ChunkPos nativeChunkPos(ChunkPos pos) {
        return new net.minecraft.world.level.ChunkPos(pos.x(), pos.z());
    }
}
