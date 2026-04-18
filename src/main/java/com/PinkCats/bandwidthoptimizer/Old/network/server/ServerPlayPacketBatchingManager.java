package com.PinkCats.bandwidthoptimizer.Old.network.server;

import com.PinkCats.bandwidthoptimizer.Bandwidthoptimizer;
import com.PinkCats.bandwidthoptimizer.Config;
import com.PinkCats.bandwidthoptimizer.Old.network.ModNetwork;
import com.PinkCats.bandwidthoptimizer.Old.network.algorithm.BatchAlgorithm;
import com.PinkCats.bandwidthoptimizer.Old.network.algorithm.BatchAlgorithmRegistry;
import com.PinkCats.bandwidthoptimizer.Old.network.algorithm.BatchAlgorithmSupport;
import com.PinkCats.bandwidthoptimizer.Old.network.algorithm.play.ClientboundPlayPacketBatchPacket;
import com.PinkCats.bandwidthoptimizer.Old.network.algorithm.play.OptimizedPlayPacketStats;
import com.PinkCats.bandwidthoptimizer.Old.network.algorithm.play.PlayPacketBatchCodec;
import com.PinkCats.bandwidthoptimizer.Old.network.algorithm.play.PlayPacketReplaySupport;
import com.PinkCats.bandwidthoptimizer.Old.network.algorithm.play.ServerOptimizationTelemetryManager;
import com.PinkCats.bandwidthoptimizer.Old.network.batch.PayloadBatching;
import com.PinkCats.bandwidthoptimizer.Old.message.BatchCompressionStats;
import net.minecraft.network.protocol.Packet;
import net.minecraft.server.level.ServerPlayer;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ThreadLocalRandom;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

public final class ServerPlayPacketBatchingManager {

    public static final long WINDOW_MILLIS = 10L;
    private static final long STATS_LOG_INTERVAL_MILLIS = TimeUnit.MINUTES.toMillis(1);
    private static final long CONNECTION_WARMUP_MILLIS = 5_000L;

    private static final ScheduledExecutorService SCHEDULER = Executors.newSingleThreadScheduledExecutor(new BatchThreadFactory());
    private static final ExecutorService ENCODER = Executors.newSingleThreadExecutor(new EncoderThreadFactory());
    private static final Map<UUID, PendingBatch> PENDING_BATCHES = new ConcurrentHashMap<>();
    private static final Map<UUID, PlayerBatchSession> BATCH_SESSIONS = new ConcurrentHashMap<>();

    static {
        SCHEDULER.scheduleAtFixedRate(
                ServerPlayPacketBatchingManager::logCompressionStats,
                STATS_LOG_INTERVAL_MILLIS,
                STATS_LOG_INTERVAL_MILLIS,
                TimeUnit.MILLISECONDS
        );
    }

    private ServerPlayPacketBatchingManager() {
    }

    public static void enqueue(ServerPlayer player, Packet<?> packet) {
        if (player == null || packet == null) {
            return;
        }

        PendingBatch pendingBatch = PENDING_BATCHES.computeIfAbsent(player.getUUID(), ignored -> new PendingBatch());
        pendingBatch.add(packet);
        pendingBatch.scheduleFlush(player);
    }

    public static void flushPendingNow(ServerPlayer player) {
        if (player == null) {
            return;
        }
        PendingBatch pendingBatch = PENDING_BATCHES.get(player.getUUID());
        if (pendingBatch == null || pendingBatch.isEmpty()) {
            return;
        }
        player.server.execute(() -> flushOnServerThread(player.getUUID(), player, pendingBatch));
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
        PlayerBatchSession session = BATCH_SESSIONS.remove(playerId);
        if (session != null) {
            synchronized (session) {
                session.invalidate();
            }
        }
    }

    public static void removePlayer(ServerPlayer player) {
        resetPlayer(player);
    }

    public static boolean shouldBypassForConnectionWarmup(ServerPlayer player) {
        if (player == null || player.connection == null || player.connection.connection == null) {
            return true;
        }
        PlayerBatchSession session = BATCH_SESSIONS.computeIfAbsent(player.getUUID(), ignored -> new PlayerBatchSession());
        synchronized (session) {
            session.prepareFor(player.connection.connection, System.currentTimeMillis());
            return session.isWarmingUp(System.currentTimeMillis());
        }
    }

    private static void flush(UUID playerId, ServerPlayer player, PendingBatch pendingBatch) {
        player.server.execute(() -> flushOnServerThread(playerId, player, pendingBatch));
    }

    private static void flushOnServerThread(UUID playerId, ServerPlayer player, PendingBatch pendingBatch) {
        List<Packet<?>> drainedEntries = pendingBatch.drain();
        if (drainedEntries.isEmpty()) {
            PENDING_BATCHES.remove(playerId, pendingBatch);
            return;
        }

        if (player.connection == null || player.connection.connection == null || !player.connection.connection.isConnected()) {
            PENDING_BATCHES.remove(playerId, pendingBatch);
            BATCH_SESSIONS.remove(playerId);
            return;
        }

        PlayerBatchSession session = BATCH_SESSIONS.computeIfAbsent(playerId, ignored -> new PlayerBatchSession());
        Object connectionIdentity = player.connection.connection;
        long connectionGeneration;
        synchronized (session) {
            session.prepareFor(connectionIdentity, System.currentTimeMillis());
            connectionGeneration = session.connectionGeneration();
        }
        if (Config.enableAsyncPlayBatchEncoding) {
            submitAsyncEncode(playerId, player, pendingBatch, session, drainedEntries, connectionIdentity, connectionGeneration);
            return;
        }

        EncodedPlayBatchResult result;
        synchronized (session) {
            PlayPacketBatchCodec.EncodedPlayPacketBatch encodedBatch = PlayPacketBatchCodec.encode(drainedEntries, session.payloadSession());
            long sequence = session.nextSequence();
            boolean resetSession = session.consumeResetSessionFlag();
            long totalRawBytes = PlayPacketReplaySupport.totalEncodedBytes(drainedEntries);
            result = new EncodedPlayBatchResult(session.sessionId(), sequence, resetSession, totalRawBytes, encodedBatch);
        }
        sendEncodedBatch(playerId, player, pendingBatch, drainedEntries, result);
    }

    private static void submitAsyncEncode(
            UUID playerId,
            ServerPlayer player,
            PendingBatch pendingBatch,
            PlayerBatchSession session,
            List<Packet<?>> drainedEntries,
            Object connectionIdentity,
            long connectionGeneration
    ) {
        PlayBatchSnapshot snapshot = snapshotBatch(drainedEntries);
        ENCODER.execute(() -> {
            EncodedPlayBatchResult result;
            try {
                synchronized (session) {
                    if (!session.matchesConnection(connectionIdentity, connectionGeneration)) {
                        return;
                    }
                    PlayPacketBatchCodec.EncodedPlayPacketBatch encodedBatch = PlayPacketBatchCodec.encodeInputs(
                            snapshot.packetIdTable(),
                            snapshot.entries(),
                            session.payloadSession()
                    );
                    long sequence = session.nextSequence();
                    boolean resetSession = session.consumeResetSessionFlag();
                    result = new EncodedPlayBatchResult(session.sessionId(), sequence, resetSession, snapshot.totalRawBytes(), encodedBatch);
                }
            } catch (Throwable error) {
                Bandwidthoptimizer.LOGGER.warn(
                        "[PlayBatch][Server][AsyncEncodeFailed] target={}, entries={}, reason={}",
                        player.getGameProfile().getName(),
                        snapshot.entries().size(),
                        error.toString(),
                        error
                );
                player.server.execute(() -> {
                    BATCH_SESSIONS.remove(playerId, session);
                    finishPendingBatch(playerId, player, pendingBatch);
                });
                return;
            }

            player.server.execute(() -> {
                synchronized (session) {
                    if (!session.matchesConnection(connectionIdentity, connectionGeneration)) {
                        finishPendingBatch(playerId, player, pendingBatch);
                        return;
                    }
                }
                if (player.connection == null
                        || player.connection.connection == null
                        || player.connection.connection != connectionIdentity
                        || !player.connection.connection.isConnected()) {
                    BATCH_SESSIONS.remove(playerId, session);
                    finishPendingBatch(playerId, player, pendingBatch);
                    return;
                }
                sendEncodedBatch(playerId, player, pendingBatch, List.of(), result);
            });
        });
    }

    private static void sendEncodedBatch(
            UUID playerId,
            ServerPlayer player,
            PendingBatch pendingBatch,
            List<Packet<?>> drainedEntries,
            EncodedPlayBatchResult result
    ) {
        PlayPacketBatchCodec.EncodedPlayPacketBatch encodedBatch = result.encodedBatch();
        ClientboundPlayPacketBatchPacket batchPacket = new ClientboundPlayPacketBatchPacket(
                result.sessionId(),
                result.sequence(),
                encodedBatch.packetIdTable(),
                encodedBatch.algorithmId(),
                result.resetSession(),
                encodedBatch.bytes(),
                encodedBatch.packetCount()
        );

        BatchCompressionStats.record(
                result.totalRawBytes(),
                batchPacket.encodedSize(),
                1,
                encodedBatch.packetCount()
        );
        if (!drainedEntries.isEmpty()) {
            OptimizedPlayPacketStats.recordBatch(drainedEntries, result.totalRawBytes(), batchPacket.encodedSize());
        }
        ServerOptimizationTelemetryManager.recordOptimizedBatch(
                player,
                result.totalRawBytes(),
                batchPacket.encodedSize(),
                encodedBatch.packetCount(),
                encodedBatch.algorithmId()
        );

        if (Config.optimizerDebugLoggingEnabled() && Bandwidthoptimizer.LOGGER.isDebugEnabled()) {
            Bandwidthoptimizer.LOGGER.debug(
                    "[PlayBatch][Server][Flush] target={}, entries={}, windowMs={}, algorithm={}, encodedBytes={}, addedMappings={}, removedMappings={}, resetSession={}",
                    player.getGameProfile().getName(),
                    encodedBatch.packetCount(),
                    WINDOW_MILLIS,
                    encodedBatch.algorithmId(),
                    encodedBatch.bytes().length,
                    encodedBatch.addedMappings(),
                    encodedBatch.removedMappings(),
                    result.resetSession()
            );
        }
        ModNetwork.sendPlayBatchToPlayerDirect(player, batchPacket);

        finishPendingBatch(playerId, player, pendingBatch);
    }

    private static void finishPendingBatch(UUID playerId, ServerPlayer player, PendingBatch pendingBatch) {
        if (pendingBatch.isEmpty()) {
            PENDING_BATCHES.remove(playerId, pendingBatch);
        } else {
            pendingBatch.scheduleFlush(player);
        }
    }

    private static PlayBatchSnapshot snapshotBatch(List<Packet<?>> packets) {
        List<BatchAlgorithm.BatchInput> entries = new ArrayList<>(packets.size());
        Map<Integer, Integer> localPacketIds = new LinkedHashMap<>();
        long totalRawBytes = 0L;
        for (Packet<?> packet : packets) {
            int packetId = PlayPacketReplaySupport.packetId(packet);
            int localId = localPacketIds.computeIfAbsent(packetId, ignored -> localPacketIds.size());
            byte[] bodyBytes = PlayPacketReplaySupport.encodePacketBody(packet);
            entries.add(new BatchAlgorithm.BatchInput(packet.getClass().getName(), encodeMappedPacket(localId, bodyBytes)));
            totalRawBytes += BatchAlgorithmSupport.varIntSize(packetId) + bodyBytes.length;
        }
        return new PlayBatchSnapshot(buildPacketIdTable(localPacketIds), List.copyOf(entries), totalRawBytes);
    }

    private static byte[] encodeMappedPacket(int localId, byte[] bodyBytes) {
        net.minecraft.network.FriendlyByteBuf buffer = new net.minecraft.network.FriendlyByteBuf(io.netty.buffer.Unpooled.buffer());
        try {
            buffer.writeVarInt(localId);
            buffer.writeBytes(bodyBytes);
            byte[] bytes = new byte[buffer.readableBytes()];
            buffer.getBytes(0, bytes);
            return bytes;
        } finally {
            buffer.release();
        }
    }

    private static int[] buildPacketIdTable(Map<Integer, Integer> localPacketIds) {
        int[] packetIdTable = new int[localPacketIds.size()];
        for (Map.Entry<Integer, Integer> entry : localPacketIds.entrySet()) {
            packetIdTable[entry.getValue()] = entry.getKey();
        }
        return packetIdTable;
    }

    private static void logCompressionStats() {
        BatchCompressionStats.logMinuteSnapshot(
                BATCH_SESSIONS.size(),
                BatchAlgorithmRegistry.configured().id(),
                WINDOW_MILLIS
        );
    }

    private static final class PendingBatch {
        private final List<Packet<?>> entries = new ArrayList<>();
        private final AtomicBoolean flushScheduled = new AtomicBoolean();

        private void add(Packet<?> entry) {
            synchronized (this.entries) {
                this.entries.add(entry);
            }
        }

        private List<Packet<?>> drain() {
            synchronized (this.entries) {
                List<Packet<?>> drained = new ArrayList<>(this.entries);
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

    private static final class BatchThreadFactory implements ThreadFactory {
        @Override
        public Thread newThread(Runnable runnable) {
            Thread thread = new Thread(runnable, "bandwidthoptimizer-play-batch-flush");
            thread.setDaemon(true);
            return thread;
        }
    }

    private static final class EncoderThreadFactory implements ThreadFactory {
        @Override
        public Thread newThread(Runnable runnable) {
            Thread thread = new Thread(runnable, "bandwidthoptimizer-play-batch-encoder");
            thread.setDaemon(true);
            return thread;
        }
    }

    private record PlayBatchSnapshot(
            int[] packetIdTable,
            List<BatchAlgorithm.BatchInput> entries,
            long totalRawBytes
    ) {
    }

    private record EncodedPlayBatchResult(
            long sessionId,
            long sequence,
            boolean resetSession,
            long totalRawBytes,
            PlayPacketBatchCodec.EncodedPlayPacketBatch encodedBatch
    ) {
    }

    private static final class PlayerBatchSession {
        private final PayloadBatching.Session payloadSession = new PayloadBatching.Session();
        private Object connectionIdentity;
        private long sessionId = ThreadLocalRandom.current().nextLong();
        private long nextSequence;
        private long connectionGeneration;
        private long warmupUntilMillis;
        private boolean resetSessionPending = true;

        private void prepareFor(Object currentConnectionIdentity, long nowMillis) {
            if (this.connectionIdentity != currentConnectionIdentity) {
                this.payloadSession.resetAll();
                this.connectionIdentity = currentConnectionIdentity;
                this.sessionId = ThreadLocalRandom.current().nextLong();
                this.nextSequence = 0L;
                this.connectionGeneration++;
                this.warmupUntilMillis = nowMillis + CONNECTION_WARMUP_MILLIS;
                this.resetSessionPending = true;
            }
        }

        private void invalidate() {
            this.payloadSession.resetAll();
            this.connectionIdentity = null;
            this.sessionId = ThreadLocalRandom.current().nextLong();
            this.nextSequence = 0L;
            this.connectionGeneration++;
            this.warmupUntilMillis = 0L;
            this.resetSessionPending = true;
        }

        private boolean matchesConnection(Object expectedConnectionIdentity, long expectedGeneration) {
            return this.connectionIdentity == expectedConnectionIdentity && this.connectionGeneration == expectedGeneration;
        }

        private long connectionGeneration() {
            return this.connectionGeneration;
        }

        private boolean isWarmingUp(long nowMillis) {
            return nowMillis < this.warmupUntilMillis;
        }

        private boolean consumeResetSessionFlag() {
            boolean resetSession = this.resetSessionPending;
            this.resetSessionPending = false;
            return resetSession;
        }

        private PayloadBatching.Session payloadSession() {
            return this.payloadSession;
        }

        private long sessionId() {
            return this.sessionId;
        }

        private long nextSequence() {
            return this.nextSequence++;
        }
    }
}
