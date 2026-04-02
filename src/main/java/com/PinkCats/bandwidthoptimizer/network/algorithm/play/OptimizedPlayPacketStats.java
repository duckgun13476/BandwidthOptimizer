package com.PinkCats.bandwidthoptimizer.network.algorithm.play;

import com.PinkCats.bandwidthoptimizer.Bandwidthoptimizer;
import com.PinkCats.bandwidthoptimizer.Config;
import net.minecraft.network.protocol.Packet;
import net.minecraft.network.protocol.game.ClientboundBlockEntityDataPacket;
import net.minecraft.network.protocol.game.ClientboundCustomPayloadPacket;
import net.minecraft.network.protocol.game.ClientboundLevelChunkWithLightPacket;
import net.minecraft.network.protocol.game.ClientboundLightUpdatePacket;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.LongAdder;

public final class OptimizedPlayPacketStats {

    private static final int MAX_LOG_ENTRIES = 8;
    private static final ConcurrentHashMap<String, Counter> CATEGORY_STATS = new ConcurrentHashMap<>();
    private static final ScheduledExecutorService REPORTER = Executors.newSingleThreadScheduledExecutor(new ReporterThreadFactory());
    private static final AtomicLong LAST_LOG_MILLIS = new AtomicLong();

    static {
        REPORTER.scheduleAtFixedRate(OptimizedPlayPacketStats::logSummary, 30L, 30L, TimeUnit.SECONDS);
    }

    private OptimizedPlayPacketStats() {
    }

    public static void recordBatch(List<Packet<?>> packets, long totalRawBytes, long totalBatchedBytes) {
        if (packets == null || packets.isEmpty() || totalRawBytes <= 0L) {
            return;
        }

        for (Packet<?> packet : packets) {
            long rawBytes = PlayPacketReplaySupport.estimatedEncodedBytes(packet);
            long apportionedBatchedBytes = Math.round((double) totalBatchedBytes * ((double) rawBytes / (double) totalRawBytes));
            CATEGORY_STATS.computeIfAbsent(classify(packet), ignored -> new Counter()).add(rawBytes, apportionedBatchedBytes);
        }
    }

    public static void clear() {
        CATEGORY_STATS.clear();
    }

    private static void logSummary() {
        if (!Config.enableOptimizerStatsLogs) {
            return;
        }
        long now = System.currentTimeMillis();
        long lastLogMillis = LAST_LOG_MILLIS.get();
        if (now - lastLogMillis < Config.optimizerStatsLogIntervalMillis()
                || !LAST_LOG_MILLIS.compareAndSet(lastLogMillis, now)
                || CATEGORY_STATS.isEmpty()) {
            return;
        }

        List<Entry> entries = new ArrayList<>(CATEGORY_STATS.size());
        long totalRaw = 0L;
        long totalBatched = 0L;
        long totalPackets = 0L;
        for (Map.Entry<String, Counter> stat : CATEGORY_STATS.entrySet()) {
            long raw = stat.getValue().rawBytes.sum();
            long batched = stat.getValue().batchedBytes.sum();
            long packets = stat.getValue().packets.sum();
            totalRaw += raw;
            totalBatched += batched;
            totalPackets += packets;
            entries.add(new Entry(stat.getKey(), raw, batched, packets));
        }
        entries.sort(Comparator.comparingLong(Entry::rawBytes).reversed());

        StringBuilder summary = new StringBuilder(512);
        summary.append("[PlayOptimized] packets=").append(totalPackets)
                .append(", rawBytes=").append(totalRaw)
                .append(", batchedBytes=").append(totalBatched)
                .append(", unique=").append(entries.size());
        int limit = Math.min(MAX_LOG_ENTRIES, entries.size());
        for (int index = 0; index < limit; index++) {
            Entry entry = entries.get(index);
            double ratio = entry.rawBytes == 0L ? 100.0D : (double) entry.batchedBytes * 100.0D / (double) entry.rawBytes;
            summary.append("\n  #").append(index + 1)
                    .append(" category=").append(entry.category)
                    .append(", rawBytes=").append(entry.rawBytes)
                    .append(", batchedBytes=").append(entry.batchedBytes)
                    .append(", packets=").append(entry.packets)
                    .append(", ratio=").append(String.format(java.util.Locale.ROOT, "%.3f%%", ratio));
        }
        Bandwidthoptimizer.LOGGER.info(summary.toString());
    }

    private static String classify(Packet<?> packet) {
        if (packet instanceof ClientboundLevelChunkWithLightPacket) {
            return "level_chunk_with_light";
        }
        if (packet instanceof ClientboundLightUpdatePacket) {
            return "light_update";
        }
        if (packet instanceof ClientboundBlockEntityDataPacket) {
            return "block_entity_data";
        }
        if (packet instanceof ClientboundCustomPayloadPacket customPayloadPacket) {
            return "custom_payload:" + customPayloadPacket.getIdentifier();
        }
        return "other";
    }

    private static final class Counter {
        private final LongAdder rawBytes = new LongAdder();
        private final LongAdder batchedBytes = new LongAdder();
        private final LongAdder packets = new LongAdder();

        private void add(long raw, long batched) {
            this.rawBytes.add(Math.max(raw, 0L));
            this.batchedBytes.add(Math.max(batched, 0L));
            this.packets.increment();
        }
    }

    private record Entry(String category, long rawBytes, long batchedBytes, long packets) {
    }

    private static final class ReporterThreadFactory implements ThreadFactory {
        @Override
        public Thread newThread(Runnable runnable) {
            Thread thread = new Thread(runnable, "bandwidthoptimizer-optimized-play-reporter");
            thread.setDaemon(true);
            return thread;
        }
    }
}
