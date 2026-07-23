package com.PinkCats.bandwidthoptimizer.experimental.watch;

import com.PinkCats.bandwidthoptimizer.chunk.classify.packet.ChunkPacketCoordinate;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.level.ChunkPos;

import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;

public final class ExperientChunkWatchEventTracker {

    private static final ConcurrentHashMap<UUID, PlayerChunkWatchProgress> PLAYER_PROGRESS = new ConcurrentHashMap<>();

    private ExperientChunkWatchEventTracker() {}


    public static void recordWatch(ServerPlayer serverPlayer, ChunkPos chunkPos) {
        if (serverPlayer == null || chunkPos == null) {
            return;
        }
        resolvePlayerProgress(serverPlayer).recordWatch(com.PinkCats.bandwidthoptimizer.integration.minecraft.ChunkCoordinateCompat.packedKey(chunkPos));
    }

    public static void recordSent(ServerPlayer serverPlayer, ChunkPos chunkPos) {
        if (serverPlayer == null || chunkPos == null) {
            return;
        }
        resolvePlayerProgress(serverPlayer).recordSent(com.PinkCats.bandwidthoptimizer.integration.minecraft.ChunkCoordinateCompat.packedKey(chunkPos));
    }

    public static void recordUnwatch(ServerPlayer serverPlayer, ChunkPos chunkPos) {
        if (serverPlayer == null || chunkPos == null) {
            return;
        }
        resolvePlayerProgress(serverPlayer).recordUnwatch(com.PinkCats.bandwidthoptimizer.integration.minecraft.ChunkCoordinateCompat.packedKey(chunkPos));
    }

    public static ChunkWatchProgressSnapshot snapshot(ServerPlayer serverPlayer, ChunkPacketCoordinate coordinate) {
        if (serverPlayer == null || coordinate == null || !coordinate.present()) {
            return ChunkWatchProgressSnapshot.empty();
        }

        PlayerChunkWatchProgress playerChunkWatchProgress = PLAYER_PROGRESS.get(serverPlayer.getUUID());
        if (playerChunkWatchProgress == null) {
            return ChunkWatchProgressSnapshot.empty();
        }
        return playerChunkWatchProgress.snapshot(com.PinkCats.bandwidthoptimizer.integration.minecraft.ChunkCoordinateCompat.packedKey(
                coordinate.chunkX(), coordinate.chunkZ()));
    }

    public static void clearPlayer(ServerPlayer serverPlayer) {
        if (serverPlayer == null) {
            return;
        }
        PLAYER_PROGRESS.remove(serverPlayer.getUUID());
    }

    private static PlayerChunkWatchProgress resolvePlayerProgress(ServerPlayer serverPlayer) {
        return PLAYER_PROGRESS.computeIfAbsent(serverPlayer.getUUID(), ignored -> new PlayerChunkWatchProgress());
    }

    public record ChunkWatchProgressSnapshot(
            long watchCount,
            long sentCount,
            long unwatchCount
    ) {

        private static ChunkWatchProgressSnapshot empty() {
            return new ChunkWatchProgressSnapshot(0L, 0L, 0L);
        }
    }

    private static final class PlayerChunkWatchProgress {

        private final ConcurrentHashMap<Long, ChunkWatchProgress> chunkProgress = new ConcurrentHashMap<>();

        private void recordWatch(long chunkKey) {
            this.chunkProgress.computeIfAbsent(chunkKey, ignored -> new ChunkWatchProgress()).watchCount.incrementAndGet();
        }

        private void recordSent(long chunkKey) {
            this.chunkProgress.computeIfAbsent(chunkKey, ignored -> new ChunkWatchProgress()).sentCount.incrementAndGet();
        }

        private void recordUnwatch(long chunkKey) {
            this.chunkProgress.computeIfAbsent(chunkKey, ignored -> new ChunkWatchProgress()).unwatchCount.incrementAndGet();
        }

        private ChunkWatchProgressSnapshot snapshot(long chunkKey) {
            ChunkWatchProgress chunkWatchProgress = this.chunkProgress.get(chunkKey);
            if (chunkWatchProgress == null) {
                return ChunkWatchProgressSnapshot.empty();
            }
            return new ChunkWatchProgressSnapshot(
                    chunkWatchProgress.watchCount.get(),
                    chunkWatchProgress.sentCount.get(),
                    chunkWatchProgress.unwatchCount.get()
            );
        }
    }

    private static final class ChunkWatchProgress {

        private final AtomicLong watchCount = new AtomicLong();
        private final AtomicLong sentCount = new AtomicLong();
        private final AtomicLong unwatchCount = new AtomicLong();
    }
}
