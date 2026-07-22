package com.PinkCats.bandwidthoptimizer.chunk.lifecycle;

import com.PinkCats.bandwidthoptimizer.debug.DiagnosticLog;

import com.PinkCats.bandwidthoptimizer.Bandwidthoptimizer;
import com.PinkCats.bandwidthoptimizer.chunk.classify.packet.ChunkPacketCoordinate;
import com.PinkCats.bandwidthoptimizer.chunk.PeerState.ChunkPeerChunkStateSnapshot;
import com.PinkCats.bandwidthoptimizer.chunk.PeerState.ChunkPeerStateManager;
import com.PinkCats.bandwidthoptimizer.debug.DiagnosticToolRegistry;
import com.PinkCats.bandwidthoptimizer.debug.HotpathCostProbe;
import com.PinkCats.bandwidthoptimizer.experient.ExperientChunkHotspotPathRuntimeConfig;
import net.minecraft.resources.ResourceKey;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.Level;

import java.util.List;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

public final class ChunkLifecycleCoordinator {

    private static final ConcurrentHashMap<UUID, ResourceKey<Level>> PLAYER_DIMENSIONS = new ConcurrentHashMap<>();

    private ChunkLifecycleCoordinator() {}


    public static void onPlayerLogin(ServerPlayer player) {
        ChunkPeerStateManager.bindPlayerDimensionScope(player, "login");
        rememberPlayerDimension(player);
    }

    public static void onPlayerRespawn(ServerPlayer player) {
        if (player == null) {
            return;
        }

        ResourceKey<Level> previousDimension = PLAYER_DIMENSIONS.get(player.getUUID());
        ResourceKey<Level> currentDimension = com.PinkCats.bandwidthoptimizer.integration.minecraft.ServerPlayerLevelCompat.serverLevel(player).dimension();
        boolean sameDimensionRespawn = previousDimension != null && previousDimension.equals(currentDimension);
        if (sameDimensionRespawn) {
            if (BO_Diag_chunkLifecycle()) {
                DiagnosticLog.info(DiagnosticToolRegistry.Tool.CHUNK_LIFECYCLE, "event=respawn_same_dimension_new_scope player={}, uuid={}, dimension={}",
                        player.getGameProfile().getName(),
                        player.getUUID(),
                        currentDimension.location()
                );
            }
            ChunkPeerStateManager.bindPlayerDimensionScope(player, "respawn_same_dimension_rebind");
        } else {
            ChunkPeerStateManager.bindPlayerDimensionScope(player, "respawn_dimension_scope");
        }
        rememberPlayerDimension(player);
    }

    public static void onPlayerDimensionChange(ServerPlayer player) {
        ChunkPeerStateManager.bindPlayerDimensionScope(player, "dimension_change");
        rememberPlayerDimension(player);
    }

    public static void onPlayerLogout(ServerPlayer player) {
        ChunkPeerStateManager.clearPlayerState(player, "logout");
        forgetPlayerDimension(player);
    }



    public static void prepareForClientRespawnBoundary(ServerPlayer player) {
        ChunkPeerStateManager.bindPlayerDimensionScope(player, "prepare_client_respawn_boundary");
        rememberPlayerDimension(player);
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

        HotpathCostProbe.Trace trace = HotpathCostProbe.begin(reason.equals("watch_remove") ? "watchRemoveRetain" : "chunkLifecycleRetain");
        if (trace.isActive()) {
            trace.detail("reason=" + reason
                    + ", player=" + player.getGameProfile().getName()
                    + ", chunk=" + chunkPos.x + "," + chunkPos.z);
        }
        try (trace) {
            long stageStartNanos = HotpathCostProbe.start();
            ChunkPacketCoordinate coordinate = ChunkPacketCoordinate.ofChunk(chunkPos.x, chunkPos.z);
            HotpathCostProbe.end("coordinate", stageStartNanos);

            stageStartNanos = HotpathCostProbe.start();
            ChunkPeerChunkStateSnapshot knownChunkSnapshot = ChunkPeerStateManager.snapshotPlayerChunk(player, coordinate);
            HotpathCostProbe.end("snapshotBefore", stageStartNanos);

            stageStartNanos = HotpathCostProbe.start();
            boolean shouldProcess = shouldProcessLifecycleChunk(knownChunkSnapshot);
            HotpathCostProbe.end("skipCheck", stageStartNanos);
            if (!shouldProcess) {
                stageStartNanos = HotpathCostProbe.start();
                logTwoPointLifecycleSkip(player, coordinate, reason, knownChunkSnapshot);
                HotpathCostProbe.end("skipLog", stageStartNanos);
                return;
            }

            stageStartNanos = HotpathCostProbe.start();
            ChunkPeerChunkStateSnapshot retainedChunkSnapshot =
                    ChunkPeerStateManager.retainPlayerChunkForWatchBoundary(player, coordinate, reason);
            HotpathCostProbe.end("retainState", stageStartNanos);

            if (BO_Diag_chunkLifecycle()) {
                stageStartNanos = HotpathCostProbe.start();
                DiagnosticLog.info(DiagnosticToolRegistry.Tool.CHUNK_LIFECYCLE, "event=retain player={}, uuid={}, reason={}, chunk={}, snapshotBefore={}, snapshotAfter={}",
                        player.getGameProfile().getName(),
                        player.getUUID(),
                        reason,
                        coordinate.logText(),
                        knownChunkSnapshot.summaryText(),
                        retainedChunkSnapshot == null ? "<missing>" : retainedChunkSnapshot.summaryText()
                );
                HotpathCostProbe.end("diagnosticLog", stageStartNanos);
            }
        }
    }


    private static void logTwoPointLifecycleSkip(
            ServerPlayer player,
            ChunkPacketCoordinate coordinate,
            String reason,
            ChunkPeerChunkStateSnapshot knownChunkSnapshot
    ) {
        if (!BO_Diag_chunkLifecycle()
                || !ExperientChunkHotspotPathRuntimeConfig.isTwoPointReuseMode()
                || player == null
                || coordinate == null) {
            return;
        }

        DiagnosticLog.info(DiagnosticToolRegistry.Tool.CHUNK_LIFECYCLE, "event=two_point_lifecycle_skip player={}, uuid={}, reason={}, chunk={}, snapshotBefore={}",
                player.getGameProfile().getName(),
                player.getUUID(),
                reason,
                coordinate.logText(),
                knownChunkSnapshot == null ? "<missing>" : knownChunkSnapshot.summaryText()
        );
    }

    private static boolean shouldProcessLifecycleChunk(ChunkPeerChunkStateSnapshot knownChunkSnapshot) {
        return knownChunkSnapshot != null
                && (knownChunkSnapshot.knownSnapshotPublished() || knownChunkSnapshot.receiverSnapshotAcknowledged());
    }

    private static void rememberPlayerDimension(ServerPlayer player) {
        if (player == null) {
            return;
        }
        PLAYER_DIMENSIONS.put(player.getUUID(), com.PinkCats.bandwidthoptimizer.integration.minecraft.ServerPlayerLevelCompat.serverLevel(player).dimension());
    }

    private static void forgetPlayerDimension(ServerPlayer player) {
        if (player == null) {
            return;
        }
        PLAYER_DIMENSIONS.remove(player.getUUID());
    }

    private static boolean BO_Diag_chunkLifecycle() {
        return DiagnosticToolRegistry.isEnabled(DiagnosticToolRegistry.Tool.CHUNK_LIFECYCLE);
    }
}
