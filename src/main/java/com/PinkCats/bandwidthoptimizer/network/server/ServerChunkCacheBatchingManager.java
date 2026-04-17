package com.PinkCats.bandwidthoptimizer.network.server;

import com.PinkCats.bandwidthoptimizer.network.ModNetwork;
import com.PinkCats.bandwidthoptimizer.network.algorithm.play.ServerOptimizationTelemetryManager;
import com.PinkCats.bandwidthoptimizer.network.batch.ChunkCachePacketBatchCodec;
import com.PinkCats.bandwidthoptimizer.network.batch.ChunkCachePacketBatching;
import com.PinkCats.bandwidthoptimizer.network.batch.PayloadBatching;
import com.PinkCats.bandwidthoptimizer.network.message.ClientboundChunkCacheBatchPacket;
import com.PinkCats.bandwidthoptimizer.network.message.ClientboundChunkCacheDeltaPacket;
import com.PinkCats.bandwidthoptimizer.network.message.ClientboundChunkCacheRefreshPacket;
import com.PinkCats.bandwidthoptimizer.network.message.ClientboundChunkCacheUsePacket;
import net.minecraft.server.level.ServerPlayer;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

public final class ServerChunkCacheBatchingManager {

    public static final long WINDOW_MILLIS = ChunkCachePacketBatching.WINDOW_MILLIS;

    private static final ScheduledExecutorService SCHEDULER = Executors.newSingleThreadScheduledExecutor(new BatchThreadFactory());
    private static final Map<UUID, PendingBatch> PENDING_BATCHES = new ConcurrentHashMap<>();
    private static final Map<UUID, PlayerBatchSession> BATCH_SESSIONS = new ConcurrentHashMap<>();

    private ServerChunkCacheBatchingManager() {
    }

    public static void enqueueRefresh(ServerPlayer player, ClientboundChunkCacheRefreshPacket packet, long rawBaselineBytes) {
        enqueue(player, new PendingEntry(new ChunkCachePacketBatchCodec.RefreshEntry(packet), rawBaselineBytes, false, true));
    }

    public static void enqueueUse(ServerPlayer player, ClientboundChunkCacheUsePacket packet, long rawBaselineBytes) {
        enqueue(player, new PendingEntry(new ChunkCachePacketBatchCodec.UseEntry(packet), rawBaselineBytes, true, false));
    }

    public static void enqueueDelta(ServerPlayer player, ClientboundChunkCacheDeltaPacket packet) {
        enqueue(player, new PendingEntry(new ChunkCachePacketBatchCodec.DeltaEntry(packet), 0L, false, false));
    }

    public static void resetPlayer(ServerPlayer player) {
        if (player == null) {
            return;
        }
        UUID playerId = player.getUUID();
        PendingBatch pendingBatch = PENDING_BATCHES.remove(playerId);
        if (pendingBatch != null) {
            pendingBatch.clear();
        }
        BATCH_SESSIONS.remove(playerId);
    }

    public static void removePlayer(ServerPlayer player) {
        resetPlayer(player);
    }

    private static void enqueue(ServerPlayer player, PendingEntry entry) {
        if (player == null || entry == null) {
            return;
        }

        PendingBatch pendingBatch = PENDING_BATCHES.computeIfAbsent(player.getUUID(), ignored -> new PendingBatch());
        pendingBatch.add(entry);
        pendingBatch.scheduleFlush(player);
    }

    private static void flush(UUID playerId, ServerPlayer player, PendingBatch pendingBatch) {
        player.server.execute(() -> flushOnServerThread(playerId, player, pendingBatch));
    }

    private static void flushOnServerThread(UUID playerId, ServerPlayer player, PendingBatch pendingBatch) {
        List<PendingEntry> drainedEntries = pendingBatch.drain();
        if (drainedEntries.isEmpty()) {
            PENDING_BATCHES.remove(playerId, pendingBatch);
            return;
        }

        if (player.connection == null || player.connection.connection == null || !player.connection.connection.isConnected()) {
            PENDING_BATCHES.remove(playerId, pendingBatch);
            BATCH_SESSIONS.remove(playerId);
            return;
        }

        List<ChunkCachePacketBatchCodec.Entry> packets = drainedEntries.stream()
                .map(PendingEntry::entry)
                .toList();
        BatchCounts batchCounts = countEntries(packets);
        long totalRawBaselineBytes = drainedEntries.stream().mapToLong(PendingEntry::rawBaselineBytes).sum();
        PlayerBatchSession session = BATCH_SESSIONS.computeIfAbsent(playerId, ignored -> new PlayerBatchSession());
        boolean resetSession = session.prepareFor(player.connection.connection);
        ChunkCachePacketBatchCodec.EncodedChunkCacheBatch encodedBatch = ChunkCachePacketBatching.encodeBatch(
                packets,
                session.payloadSession()
        );
        ClientboundChunkCacheBatchPacket batchPacket = ChunkCachePacketBatching.createBatchPacket(encodedBatch, resetSession);
        long[] allocatedSentBytes = apportionBatchBytes(encodedBatch.entryPayloadSizes(), batchPacket.encodedSize());

        ModNetwork.sendChunkCacheBatchToPlayerDirect(player, batchPacket);
        for (int index = 0; index < drainedEntries.size(); index++) {
            PendingEntry entry = drainedEntries.get(index);
            ServerOptimizationTelemetryManager.recordChunkCache(
                    player,
                    entry.rawBaselineBytes(),
                    allocatedSentBytes[index],
                    entry.hit(),
                    entry.refresh()
            );
        }

        if (pendingBatch.isEmpty()) {
            PENDING_BATCHES.remove(playerId, pendingBatch);
        } else {
            pendingBatch.scheduleFlush(player);
        }
    }

    private static long[] apportionBatchBytes(List<Integer> entryPayloadSizes, long totalSentBytes) {
        long[] allocated = new long[entryPayloadSizes.size()];
        long weightTotal = 0L;
        for (int size : entryPayloadSizes) {
            weightTotal += Math.max(size, 1);
        }
        if (weightTotal <= 0L || totalSentBytes <= 0L) {
            return allocated;
        }

        long assigned = 0L;
        double[] remainders = new double[entryPayloadSizes.size()];
        for (int index = 0; index < entryPayloadSizes.size(); index++) {
            long weight = Math.max(entryPayloadSizes.get(index), 1);
            double exactShare = (double) totalSentBytes * (double) weight / (double) weightTotal;
            long baseShare = (long) Math.floor(exactShare);
            allocated[index] = baseShare;
            assigned += baseShare;
            remainders[index] = exactShare - baseShare;
        }

        long remaining = totalSentBytes - assigned;
        while (remaining > 0L) {
            int bestIndex = 0;
            for (int index = 1; index < remainders.length; index++) {
                if (remainders[index] > remainders[bestIndex]) {
                    bestIndex = index;
                }
            }
            allocated[bestIndex]++;
            remainders[bestIndex] = 0.0D;
            remaining--;
        }
        return allocated;
    }

    private static BatchCounts countEntries(List<ChunkCachePacketBatchCodec.Entry> packets) {
        int refreshes = 0;
        int uses = 0;
        int deltas = 0;
        for (ChunkCachePacketBatchCodec.Entry packet : packets) {
            if (packet instanceof ChunkCachePacketBatchCodec.RefreshEntry) {
                refreshes++;
            } else if (packet instanceof ChunkCachePacketBatchCodec.UseEntry) {
                uses++;
            } else if (packet instanceof ChunkCachePacketBatchCodec.DeltaEntry) {
                deltas++;
            }
        }
        return new BatchCounts(refreshes, uses, deltas);
    }

    private record BatchCounts(
            int refreshes,
            int uses,
            int deltas
    ) {
    }

    private record PendingEntry(
            ChunkCachePacketBatchCodec.Entry entry,
            long rawBaselineBytes,
            boolean hit,
            boolean refresh
    ) {
    }

    private static final class PendingBatch {
        private final List<PendingEntry> entries = new ArrayList<>();
        private final AtomicBoolean flushScheduled = new AtomicBoolean();

        private void add(PendingEntry entry) {
            synchronized (this.entries) {
                this.entries.add(entry);
            }
        }

        private List<PendingEntry> drain() {
            synchronized (this.entries) {
                List<PendingEntry> drained = new ArrayList<>(this.entries);
                this.entries.clear();
                this.flushScheduled.set(false);
                return drained;
            }
        }

        private boolean isEmpty() {
            synchronized (this.entries) {
                return this.entries.isEmpty();
            }
        }

        private void clear() {
            synchronized (this.entries) {
                this.entries.clear();
                this.flushScheduled.set(false);
            }
        }

        private void scheduleFlush(ServerPlayer player) {
            if (!this.flushScheduled.compareAndSet(false, true)) {
                return;
            }

            UUID playerId = player.getUUID();
            SCHEDULER.schedule(() -> flush(playerId, player, this), WINDOW_MILLIS, TimeUnit.MILLISECONDS);
        }
    }

    private static final class PlayerBatchSession {
        private final PayloadBatching.Session payloadSession = new PayloadBatching.Session();
        private Object connectionIdentity;

        private boolean prepareFor(Object currentConnectionIdentity) {
            if (this.connectionIdentity != currentConnectionIdentity) {
                this.payloadSession.resetAll();
                this.connectionIdentity = currentConnectionIdentity;
                return true;
            }
            return false;
        }

        private PayloadBatching.Session payloadSession() {
            return this.payloadSession;
        }
    }

    private static final class BatchThreadFactory implements ThreadFactory {
        @Override
        public Thread newThread(Runnable runnable) {
            Thread thread = new Thread(runnable, "bandwidthoptimizer-chunk-cache-batch-flush");
            thread.setDaemon(true);
            return thread;
        }
    }
}
