package com.PinkCats.bandwidthoptimizer.Old.network.server;

import com.PinkCats.bandwidthoptimizer.Bandwidthoptimizer;
import com.PinkCats.bandwidthoptimizer.Config;
import com.PinkCats.bandwidthoptimizer.Old.network.ModNetwork;
import com.PinkCats.bandwidthoptimizer.Old.network.algorithm.BatchAlgorithmRegistry;
import com.PinkCats.bandwidthoptimizer.Old.network.batch.AttachmentPacketBatchCodec;
import com.PinkCats.bandwidthoptimizer.Old.network.batch.AttachmentPacketBatching;
import com.PinkCats.bandwidthoptimizer.Old.network.batch.PayloadBatching;
import com.PinkCats.bandwidthoptimizer.Old.network.message.ServerToClientAttachmentBatchPacket;
import com.PinkCats.bandwidthoptimizer.Old.network.message.ServerToClientAttachmentPacket;
import com.PinkCats.bandwidthoptimizer.Old.message.BatchCompressionStats;
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

public final class ServerToClientBatchingManager {

    public static final long WINDOW_MILLIS = AttachmentPacketBatching.WINDOW_MILLIS;
    private static final long STATS_LOG_INTERVAL_MILLIS = TimeUnit.MINUTES.toMillis(1);

    private static final ScheduledExecutorService SCHEDULER = Executors.newSingleThreadScheduledExecutor(new BatchThreadFactory());
    private static final Map<UUID, PendingBatch> PENDING_BATCHES = new ConcurrentHashMap<>();
    private static final Map<UUID, PlayerBatchSession> BATCH_SESSIONS = new ConcurrentHashMap<>();

    static {
        SCHEDULER.scheduleAtFixedRate(
                ServerToClientBatchingManager::logCompressionStats,
                STATS_LOG_INTERVAL_MILLIS,
                STATS_LOG_INTERVAL_MILLIS,
                TimeUnit.MILLISECONDS
        );
    }

    private ServerToClientBatchingManager() {
    }

    public static void enqueue(ServerPlayer player, ServerToClientAttachmentPacket packet) {
        if (player == null || packet == null) {
            return;
        }

        PendingBatch pendingBatch = PENDING_BATCHES.computeIfAbsent(player.getUUID(), ignored -> new PendingBatch());
        pendingBatch.add(packet);
        pendingBatch.scheduleFlush(player);
    }

    private static void flush(UUID playerId, ServerPlayer player, PendingBatch pendingBatch) {
        player.server.execute(() -> flushOnServerThread(playerId, player, pendingBatch));
    }

    private static void flushOnServerThread(UUID playerId, ServerPlayer player, PendingBatch pendingBatch) {
        List<ServerToClientAttachmentPacket> drainedEntries = pendingBatch.drain();
        if (drainedEntries.isEmpty()) {
            PENDING_BATCHES.remove(playerId, pendingBatch);
            return;
        }

        if (player.connection == null || player.connection.connection == null || !player.connection.connection.isConnected()) {
            Bandwidthoptimizer.LOGGER.warn(
                    "[ModChannelBatch][Server][Drop] target={}, reason=connection-unavailable, entries={}",
                    player.getGameProfile().getName(),
                    drainedEntries.size()
            );
            PENDING_BATCHES.remove(playerId, pendingBatch);
            BATCH_SESSIONS.remove(playerId);
            return;
        }

        List<ServerToClientAttachmentPacket> batchPackets = new ArrayList<>(drainedEntries);
        PlayerBatchSession session = BATCH_SESSIONS.computeIfAbsent(playerId, ignored -> new PlayerBatchSession());
        boolean resetSession = session.prepareFor(player.connection.connection);
        AttachmentPacketBatchCodec.EncodedAttachmentBatch encodedBatch = AttachmentPacketBatching.encodeBatch(
                batchPackets,
                session.payloadSession()
        );
        ServerToClientAttachmentBatchPacket batchPacket = AttachmentPacketBatching.createBatchPacket(
                encodedBatch,
                resetSession
        );
        long rawBytes = batchPackets.stream()
                .mapToLong(packet -> AttachmentPacketBatchCodec.encodePacket(packet).length)
                .sum();
        long batchedBytes = batchPacket.encodedSize();
        BatchCompressionStats.record(rawBytes, batchedBytes, 1, batchPackets.size());

        if (Config.optimizerDebugLoggingEnabled() && Bandwidthoptimizer.LOGGER.isDebugEnabled()) {
            Bandwidthoptimizer.LOGGER.debug(
                    "[ModChannelBatch][Server][Flush] target={}, entries={}, windowMs={}, algorithm={}, encodedBytes={}, addedMappings={}, removedMappings={}, resetSession={}",
                    player.getGameProfile().getName(),
                    batchPackets.size(),
                    WINDOW_MILLIS,
                    encodedBatch.algorithmId(),
                    encodedBatch.bytes().length,
                    encodedBatch.addedMappings(),
                    encodedBatch.removedMappings(),
                    resetSession
            );
        }
        ModNetwork.sendBatchToPlayerDirect(player, batchPacket);

        if (pendingBatch.isEmpty()) {
            PENDING_BATCHES.remove(playerId, pendingBatch);
        } else {
            pendingBatch.scheduleFlush(player);
        }
    }

    private static void logCompressionStats() {
        BatchCompressionStats.logMinuteSnapshot(
                BATCH_SESSIONS.size(),
                BatchAlgorithmRegistry.configured().id(),
                WINDOW_MILLIS
        );
    }

    private static final class PendingBatch {
        private final List<ServerToClientAttachmentPacket> entries = new ArrayList<>();
        private final AtomicBoolean flushScheduled = new AtomicBoolean();

        private void add(ServerToClientAttachmentPacket entry) {
            synchronized (this.entries) {
                this.entries.add(entry);
            }
        }

        private List<ServerToClientAttachmentPacket> drain() {
            synchronized (this.entries) {
                List<ServerToClientAttachmentPacket> drained = new ArrayList<>(this.entries);
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

        private void scheduleFlush(ServerPlayer player) {
            if (!this.flushScheduled.compareAndSet(false, true)) {
                return;
            }

            UUID playerId = player.getUUID();
            SCHEDULER.schedule(() -> flush(playerId, player, this), WINDOW_MILLIS, TimeUnit.MILLISECONDS);
        }
    }

    private static final class BatchThreadFactory implements ThreadFactory {
        @Override
        public Thread newThread(Runnable runnable) {
            Thread thread = new Thread(runnable, "bandwidthoptimizer-batch-flush");
            thread.setDaemon(true);
            return thread;
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
}
