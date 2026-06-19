package com.PinkCats.bandwidthoptimizer;

import com.PinkCats.bandwidthoptimizer.chunk.lifecycle.ChunkLifecycleCoordinator;
import com.PinkCats.bandwidthoptimizer.compat.create.CreateBlockEntityUpdateGate;
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

    public static void onPlayerLogin(ServerPlayer serverPlayer) {
        ServerBandwidthStatsRegistry.bindPlayer(serverPlayer);
        ExperientChunkWatchEventTracker.clearPlayer(serverPlayer);
        ChunkLifecycleCoordinator.onPlayerLogin(serverPlayer);
        CreateBlockEntityUpdateGate.bindPlayer(serverPlayer);
    }

    public static void onPlayerRespawn(ServerPlayer serverPlayer) {
        CreateBlockEntityUpdateGate.clearPlayer(serverPlayer, "respawn");
        ChunkLifecycleCoordinator.onPlayerRespawn(serverPlayer);
        CreateBlockEntityUpdateGate.bindPlayer(serverPlayer);
    }

    public static void onPlayerDimensionChange(ServerPlayer serverPlayer) {
        CreateBlockEntityUpdateGate.clearPlayer(serverPlayer, "dimension_change");
        ChunkLifecycleCoordinator.onPlayerDimensionChange(serverPlayer);
        CreateBlockEntityUpdateGate.bindPlayer(serverPlayer);
    }

    public static void onPlayerLogout(ServerPlayer serverPlayer) {
        CreateBlockEntityUpdateGate.clearPlayer(serverPlayer, "logout");
        ChunkLifecycleCoordinator.onPlayerLogout(serverPlayer);
        ExperientChunkWatchEventTracker.clearPlayer(serverPlayer);
        ServerBandwidthStatsRegistry.unbindPlayer(serverPlayer);
    }

    public static void onChunkWatch(ServerPlayer serverPlayer, net.minecraft.world.level.ChunkPos chunkPos) {
        ExperientChunkWatchEventTracker.recordWatch(serverPlayer, chunkPos);
    }

    public static void onChunkUnwatch(ServerPlayer serverPlayer, ServerLevel serverLevel, net.minecraft.world.level.ChunkPos chunkPos) {
        ExperientChunkWatchEventTracker.recordUnwatch(serverPlayer, chunkPos);
        CreateBlockEntityUpdateGate.dropPendingChunk(serverPlayer, chunkPos, "watch_remove");
        ChunkLifecycleCoordinator.onPlayerStopWatchingChunk(serverPlayer, chunkPos, serverLevel);
    }

    public static void onChunkUnload(ServerLevel serverLevel, net.minecraft.world.level.ChunkPos chunkPos) {
        ChunkLifecycleCoordinator.onServerChunkUnload(serverLevel, chunkPos);
    }

    public static void onServerTickEnd() {
        CreateBlockEntityUpdateGate.onServerTick();
    }

    private static net.minecraft.world.level.ChunkPos nativeChunkPos(ChunkPos pos) {
        return new net.minecraft.world.level.ChunkPos(pos.x(), pos.z());
    }
}
