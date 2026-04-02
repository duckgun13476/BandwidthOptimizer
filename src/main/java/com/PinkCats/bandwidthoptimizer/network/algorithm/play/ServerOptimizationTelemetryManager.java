package com.PinkCats.bandwidthoptimizer.network.algorithm.play;

import com.PinkCats.bandwidthoptimizer.network.ModNetwork;
import com.PinkCats.bandwidthoptimizer.network.message.ServerOptimizationTelemetryPacket;
import com.PinkCats.bandwidthoptimizer.network.message.ServerOverallOptimizationTelemetryPacket;
import net.minecraft.server.level.ServerPlayer;

import java.util.ArrayDeque;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

public final class ServerOptimizationTelemetryManager {

    private static final long RECENT_WINDOW_MILLIS = 120_000L;
    private static final long PUSH_INTERVAL_MILLIS = 250L;
    private static final Map<UUID, PlayerState> STATES = new ConcurrentHashMap<>();
    private static final GlobalState GLOBAL_STATE = new GlobalState();

    private ServerOptimizationTelemetryManager() {
    }

    public static void recordBypass(ServerPlayer player, long bytes) {
        if (player == null) {
            return;
        }
        long now = System.currentTimeMillis();
        PlayerState state = STATES.computeIfAbsent(player.getUUID(), ignored -> new PlayerState());
        synchronized (GLOBAL_STATE) {
            long safeBytes = Math.max(bytes, 0L);
            GLOBAL_STATE.totalBypassBytes += safeBytes;
            GLOBAL_STATE.recent.addLast(new GlobalSample(now, 0L, 0L, 0L, 0L, safeBytes, 0L, 0L, 0L));
            prune(GLOBAL_STATE.recent, now);
        }
        synchronized (state) {
            state.totalBytes += Math.max(bytes, 0L);
            state.totalPackets++;
            state.recent.addLast(new Sample(now, Math.max(bytes, 0L), 1L, 0L, 0L, 0L));
            prune(state.recent, now);
            pushIfNeeded(player, state, now);
        }
    }

    public static void recordChunkCache(ServerPlayer player, long savedBytes, boolean hit, boolean refresh) {
        if (player == null) {
            return;
        }
        long now = System.currentTimeMillis();
        PlayerState state = STATES.computeIfAbsent(player.getUUID(), ignored -> new PlayerState());
        synchronized (GLOBAL_STATE) {
            long safeSavedBytes = Math.max(savedBytes, 0L);
            GLOBAL_STATE.totalChunkCacheSavedBytes += safeSavedBytes;
            if (hit) {
                GLOBAL_STATE.totalChunkCacheHitTotalPackets++;
            }
            if (refresh) {
                GLOBAL_STATE.totalChunkCacheRefreshTotalPackets++;
            }
            GLOBAL_STATE.recent.addLast(new GlobalSample(now, 0L, 0L, 0L, 0L, 0L, safeSavedBytes, hit ? 1L : 0L, refresh ? 1L : 0L));
            prune(GLOBAL_STATE.recent, now);
        }
        synchronized (state) {
            long safeSavedBytes = Math.max(savedBytes, 0L);
            state.chunkCacheSavedTotalBytes += safeSavedBytes;
            if (hit) {
                state.chunkCacheHitTotalPackets++;
            }
            if (refresh) {
                state.chunkCacheRefreshTotalPackets++;
            }
            state.recent.addLast(new Sample(now, 0L, 0L, safeSavedBytes, hit ? 1L : 0L, refresh ? 1L : 0L));
            prune(state.recent, now);
            pushIfNeeded(player, state, now);
        }
    }

    public static void recordOptimizedBatch(ServerPlayer player, long rawBytes, long batchedBytes, int packetCount, String algorithmId) {
        if (player == null) {
            return;
        }
        long now = System.currentTimeMillis();
        synchronized (GLOBAL_STATE) {
            GLOBAL_STATE.totalRawBytes += Math.max(rawBytes, 0L);
            GLOBAL_STATE.totalBatchedBytes += Math.max(batchedBytes, 0L);
            GLOBAL_STATE.totalBatchCount++;
            GLOBAL_STATE.totalPacketCount += Math.max(packetCount, 0);
            if (algorithmId != null && !algorithmId.isBlank()) {
                GLOBAL_STATE.algorithmId = algorithmId;
            }
            GLOBAL_STATE.recent.addLast(new GlobalSample(
                    now,
                    Math.max(rawBytes, 0L),
                    Math.max(batchedBytes, 0L),
                    1L,
                    Math.max(packetCount, 0),
                    0L,
                    0L,
                    0L,
                    0L
            ));
            prune(GLOBAL_STATE.recent, now);
        }

        PlayerState state = STATES.computeIfAbsent(player.getUUID(), ignored -> new PlayerState());
        synchronized (state) {
            prune(state.recent, now);
            pushIfNeeded(player, state, now);
        }
    }

    private static void pushIfNeeded(ServerPlayer player, PlayerState state, long now) {
        if (now - state.lastPushMillis < PUSH_INTERVAL_MILLIS) {
            return;
        }
        state.lastPushMillis = now;
        ModNetwork.sendOptimizationTelemetryToPlayer(
                player,
                new ServerOptimizationTelemetryPacket(
                        state.totalBytes,
                        state.totalPackets,
                        sumPlayerRecent(state.recent, Sample::bytes),
                        sumPlayerRecent(state.recent, Sample::packets),
                        state.chunkCacheSavedTotalBytes,
                        sumPlayerRecent(state.recent, Sample::chunkCacheSavedBytes),
                        state.chunkCacheHitTotalPackets,
                        sumPlayerRecent(state.recent, Sample::chunkCacheHitPackets),
                        state.chunkCacheRefreshTotalPackets,
                        sumPlayerRecent(state.recent, Sample::chunkCacheRefreshPackets)
                )
        );

        synchronized (GLOBAL_STATE) {
            ModNetwork.sendOverallOptimizationTelemetryToPlayer(
                    player,
                    new ServerOverallOptimizationTelemetryPacket(
                            GLOBAL_STATE.totalRawBytes,
                            GLOBAL_STATE.totalBatchedBytes,
                            GLOBAL_STATE.totalBatchCount,
                            GLOBAL_STATE.totalPacketCount,
                            GLOBAL_STATE.totalBypassBytes,
                            GLOBAL_STATE.totalChunkCacheSavedBytes,
                            GLOBAL_STATE.totalChunkCacheHitTotalPackets,
                            GLOBAL_STATE.totalChunkCacheRefreshTotalPackets,
                            sumGlobalRecent(GLOBAL_STATE.recent, GlobalSample::rawBytes),
                            sumGlobalRecent(GLOBAL_STATE.recent, GlobalSample::batchedBytes),
                            sumGlobalRecent(GLOBAL_STATE.recent, GlobalSample::batchCount),
                            sumGlobalRecent(GLOBAL_STATE.recent, GlobalSample::packetCount),
                            sumGlobalRecent(GLOBAL_STATE.recent, GlobalSample::bypassBytes),
                            sumGlobalRecent(GLOBAL_STATE.recent, GlobalSample::chunkCacheSavedBytes),
                            player.server.getPlayerCount(),
                            GLOBAL_STATE.algorithmId
                    )
            );
        }
    }

    private static long sumPlayerRecent(ArrayDeque<Sample> deque, java.util.function.ToLongFunction<Sample> getter) {
        long total = 0L;
        for (Sample sample : deque) {
            total += getter.applyAsLong(sample);
        }
        return total;
    }

    private static long sumGlobalRecent(ArrayDeque<GlobalSample> deque, java.util.function.ToLongFunction<GlobalSample> getter) {
        long total = 0L;
        for (GlobalSample sample : deque) {
            total += getter.applyAsLong(sample);
        }
        return total;
    }

    private static <T extends TimestampedSample> void prune(ArrayDeque<T> deque, long now) {
        while (deque.size() > 1 && now - deque.peekFirst().timestampMillis() > RECENT_WINDOW_MILLIS) {
            deque.removeFirst();
        }
    }

    private static final class PlayerState {
        private long totalBytes;
        private long totalPackets;
        private long chunkCacheSavedTotalBytes;
        private long chunkCacheHitTotalPackets;
        private long chunkCacheRefreshTotalPackets;
        private long lastPushMillis;
        private final ArrayDeque<Sample> recent = new ArrayDeque<>();
    }

    private static final class GlobalState {
        private long totalRawBytes;
        private long totalBatchedBytes;
        private long totalBatchCount;
        private long totalPacketCount;
        private long totalBypassBytes;
        private long totalChunkCacheSavedBytes;
        private long totalChunkCacheHitTotalPackets;
        private long totalChunkCacheRefreshTotalPackets;
        private String algorithmId = "-";
        private final ArrayDeque<GlobalSample> recent = new ArrayDeque<>();
    }

    private record Sample(
            long timestampMillis,
            long bytes,
            long packets,
            long chunkCacheSavedBytes,
            long chunkCacheHitPackets,
            long chunkCacheRefreshPackets
    ) implements TimestampedSample {
    }

    private record GlobalSample(
            long timestampMillis,
            long rawBytes,
            long batchedBytes,
            long batchCount,
            long packetCount,
            long bypassBytes,
            long chunkCacheSavedBytes,
            long chunkCacheHitPackets,
            long chunkCacheRefreshPackets
    ) implements TimestampedSample {
    }

    private interface TimestampedSample {
        long timestampMillis();
    }
}
