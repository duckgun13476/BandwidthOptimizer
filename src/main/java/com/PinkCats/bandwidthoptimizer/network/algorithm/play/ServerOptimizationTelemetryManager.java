package com.PinkCats.bandwidthoptimizer.network.algorithm.play;

import com.PinkCats.bandwidthoptimizer.network.ModNetwork;
import com.PinkCats.bandwidthoptimizer.network.message.ServerOptimizationTelemetryPacket;
import com.PinkCats.bandwidthoptimizer.network.message.ServerOverallOptimizationTelemetryPacket;
import net.minecraft.server.level.ServerPlayer;
import net.minecraftforge.event.entity.player.PlayerEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;

import java.util.ArrayDeque;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

@Mod.EventBusSubscriber(modid = "bandwidthoptimizer", bus = Mod.EventBusSubscriber.Bus.FORGE)
public final class ServerOptimizationTelemetryManager {

    private static final long RECENT_WINDOW_MILLIS = 120_000L;
    private static final long SAMPLE_BUCKET_MILLIS = 500L;
    private static final long PUSH_INTERVAL_MILLIS = 1_000L;
    private static final Map<UUID, PlayerState> STATES = new ConcurrentHashMap<>();
    private static final Map<UUID, Boolean> SUBSCRIPTIONS = new ConcurrentHashMap<>();
    private static final GlobalState GLOBAL_STATE = new GlobalState();

    private ServerOptimizationTelemetryManager() {
    }

    public static void setSubscribed(ServerPlayer player, boolean subscribed) {
        if (player == null) {
            return;
        }
        UUID playerId = player.getUUID();
        if (subscribed) {
            SUBSCRIPTIONS.put(playerId, Boolean.TRUE);
            PlayerState state = STATES.computeIfAbsent(playerId, ignored -> new PlayerState());
            synchronized (state) {
                state.lastPushMillis = 0L;
                pushIfNeeded(player, state, System.currentTimeMillis());
            }
        } else {
            SUBSCRIPTIONS.remove(playerId);
        }
    }

    @SubscribeEvent
    public static void onPlayerLoggedOut(PlayerEvent.PlayerLoggedOutEvent event) {
        UUID playerId = event.getEntity().getUUID();
        SUBSCRIPTIONS.remove(playerId);
        STATES.remove(playerId);
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
            GlobalSample globalSample = currentGlobalSample(now);
            globalSample.bypassBytes += safeBytes;
            prune(GLOBAL_STATE.recent, now);
        }
        synchronized (state) {
            state.totalBytes += Math.max(bytes, 0L);
            state.totalPackets++;
            Sample sample = currentPlayerSample(state, now);
            sample.bytes += Math.max(bytes, 0L);
            sample.packets++;
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
            GlobalSample globalSample = currentGlobalSample(now);
            globalSample.chunkCacheSavedBytes += safeSavedBytes;
            globalSample.chunkCacheHitPackets += hit ? 1L : 0L;
            globalSample.chunkCacheRefreshPackets += refresh ? 1L : 0L;
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
            Sample sample = currentPlayerSample(state, now);
            sample.chunkCacheSavedBytes += safeSavedBytes;
            sample.chunkCacheHitPackets += hit ? 1L : 0L;
            sample.chunkCacheRefreshPackets += refresh ? 1L : 0L;
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
            GlobalSample globalSample = currentGlobalSample(now);
            globalSample.rawBytes += Math.max(rawBytes, 0L);
            globalSample.batchedBytes += Math.max(batchedBytes, 0L);
            globalSample.batchCount++;
            globalSample.packetCount += Math.max(packetCount, 0);
            prune(GLOBAL_STATE.recent, now);
        }

        PlayerState state = STATES.computeIfAbsent(player.getUUID(), ignored -> new PlayerState());
        synchronized (state) {
            prune(state.recent, now);
            pushIfNeeded(player, state, now);
        }
    }

    private static void pushIfNeeded(ServerPlayer player, PlayerState state, long now) {
        if (!Boolean.TRUE.equals(SUBSCRIPTIONS.get(player.getUUID()))) {
            return;
        }
        if (now - state.lastPushMillis < PUSH_INTERVAL_MILLIS) {
            return;
        }
        state.lastPushMillis = now;
        ModNetwork.sendOptimizationTelemetryToPlayer(
                player,
                new ServerOptimizationTelemetryPacket(
                        state.totalBytes,
                        state.totalPackets,
                        sumPlayerRecent(state.recent, sample -> sample.bytes),
                        sumPlayerRecent(state.recent, sample -> sample.packets),
                        state.chunkCacheSavedTotalBytes,
                        sumPlayerRecent(state.recent, sample -> sample.chunkCacheSavedBytes),
                        state.chunkCacheHitTotalPackets,
                        sumPlayerRecent(state.recent, sample -> sample.chunkCacheHitPackets),
                        state.chunkCacheRefreshTotalPackets,
                        sumPlayerRecent(state.recent, sample -> sample.chunkCacheRefreshPackets)
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
                            sumGlobalRecent(GLOBAL_STATE.recent, sample -> sample.rawBytes),
                            sumGlobalRecent(GLOBAL_STATE.recent, sample -> sample.batchedBytes),
                            sumGlobalRecent(GLOBAL_STATE.recent, sample -> sample.batchCount),
                            sumGlobalRecent(GLOBAL_STATE.recent, sample -> sample.packetCount),
                            sumGlobalRecent(GLOBAL_STATE.recent, sample -> sample.bypassBytes),
                            sumGlobalRecent(GLOBAL_STATE.recent, sample -> sample.chunkCacheSavedBytes),
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

    private static Sample currentPlayerSample(PlayerState state, long now) {
        long bucketTimestamp = bucketTimestamp(now);
        Sample sample = state.recent.peekLast();
        if (sample == null || sample.timestampMillis != bucketTimestamp) {
            sample = new Sample(bucketTimestamp);
            state.recent.addLast(sample);
        }
        return sample;
    }

    private static GlobalSample currentGlobalSample(long now) {
        long bucketTimestamp = bucketTimestamp(now);
        GlobalSample sample = GLOBAL_STATE.recent.peekLast();
        if (sample == null || sample.timestampMillis != bucketTimestamp) {
            sample = new GlobalSample(bucketTimestamp);
            GLOBAL_STATE.recent.addLast(sample);
        }
        return sample;
    }

    private static long bucketTimestamp(long now) {
        return now - Math.floorMod(now, SAMPLE_BUCKET_MILLIS);
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

    private static final class Sample implements TimestampedSample {
        private final long timestampMillis;
        private long bytes;
        private long packets;
        private long chunkCacheSavedBytes;
        private long chunkCacheHitPackets;
        private long chunkCacheRefreshPackets;

        private Sample(long timestampMillis) {
            this.timestampMillis = timestampMillis;
        }

        @Override
        public long timestampMillis() {
            return this.timestampMillis;
        }
    }

    private static final class GlobalSample implements TimestampedSample {
        private final long timestampMillis;
        private long rawBytes;
        private long batchedBytes;
        private long batchCount;
        private long packetCount;
        private long bypassBytes;
        private long chunkCacheSavedBytes;
        private long chunkCacheHitPackets;
        private long chunkCacheRefreshPackets;

        private GlobalSample(long timestampMillis) {
            this.timestampMillis = timestampMillis;
        }

        @Override
        public long timestampMillis() {
            return this.timestampMillis;
        }
    }

    private interface TimestampedSample {
        long timestampMillis();
    }
}
