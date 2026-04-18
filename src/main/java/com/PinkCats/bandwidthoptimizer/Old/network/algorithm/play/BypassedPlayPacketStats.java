package com.PinkCats.bandwidthoptimizer.Old.network.algorithm.play;

import com.PinkCats.bandwidthoptimizer.Bandwidthoptimizer;
import com.PinkCats.bandwidthoptimizer.Config;
import net.minecraft.network.protocol.Packet;
import net.minecraft.network.protocol.game.ClientboundCustomPayloadPacket;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.LongAdder;

public final class BypassedPlayPacketStats {

    private static final int MAX_LOG_ENTRIES = 8;
    private static final ConcurrentHashMap<BypassKey, Counter> STATS = new ConcurrentHashMap<>();
    private static final ScheduledExecutorService REPORTER = Executors.newSingleThreadScheduledExecutor(new ReporterThreadFactory());
    private static final AtomicLong LAST_LOG_MILLIS = new AtomicLong();

    static {
        REPORTER.scheduleAtFixedRate(BypassedPlayPacketStats::logSummary, 30L, 30L, TimeUnit.SECONDS);
    }

    private BypassedPlayPacketStats() {
    }

    public static void record(Packet<?> packet, String reason, int estimatedBytes) {
        if (packet == null || reason == null || reason.isEmpty()) {
            return;
        }
        STATS.computeIfAbsent(
                new BypassKey(packet.getClass().getName(), payloadId(packet), reason),
                ignored -> new Counter()
        ).add(estimatedBytes);
    }

    public static void clear() {
        STATS.clear();
    }

    private static void logSummary() {
        if (!Config.enableOptimizerStatsLogs || !Config.enableTestMode) {
            return;
        }
        long now = System.currentTimeMillis();
        long lastLogMillis = LAST_LOG_MILLIS.get();
        if (now - lastLogMillis < Config.optimizerStatsLogIntervalMillis()
                || !LAST_LOG_MILLIS.compareAndSet(lastLogMillis, now)
                || STATS.isEmpty()) {
            return;
        }

        List<Entry> entries = new ArrayList<>(STATS.size());
        long totalPackets = 0L;
        long totalBytes = 0L;
        for (Map.Entry<BypassKey, Counter> stat : STATS.entrySet()) {
            long packets = stat.getValue().packets.sum();
            long bytes = stat.getValue().bytes.sum();
            totalPackets += packets;
            totalBytes += bytes;
            entries.add(new Entry(stat.getKey(), packets, bytes));
        }
        entries.sort(Comparator.comparingLong(Entry::bytes).reversed());

        StringBuilder summary = new StringBuilder(512);
        summary.append("[PlayBypass] packets=").append(totalPackets)
                .append(", bytes=").append(totalBytes)
                .append(", unique=").append(entries.size());
        int limit = Math.min(MAX_LOG_ENTRIES, entries.size());
        for (int index = 0; index < limit; index++) {
            Entry entry = entries.get(index);
            summary.append("\n  #").append(index + 1)
                    .append(" bytes=").append(entry.bytes())
                    .append(", packets=").append(entry.packets())
                    .append(", packet=").append(entry.key().packetClass())
                    .append(", payload=").append(entry.key().payloadId())
                    .append(", reason=").append(entry.key().reason());
        }
        Bandwidthoptimizer.LOGGER.info(summary.toString());
    }

    private static String payloadId(Packet<?> packet) {
        if (packet instanceof ClientboundCustomPayloadPacket customPayloadPacket) {
            return String.valueOf(customPayloadPacket.getIdentifier());
        }
        return "-";
    }

    private record BypassKey(String packetClass, String payloadId, String reason) {
        private BypassKey {
            packetClass = Objects.requireNonNullElse(packetClass, "<unknown>");
            payloadId = Objects.requireNonNullElse(payloadId, "-");
            reason = Objects.requireNonNullElse(reason, "<unknown>");
        }
    }

    private record Entry(BypassKey key, long packets, long bytes) {
    }

    private static final class Counter {
        private final LongAdder packets = new LongAdder();
        private final LongAdder bytes = new LongAdder();

        private void add(int estimatedBytes) {
            this.packets.increment();
            this.bytes.add(Math.max(estimatedBytes, 0));
        }
    }

    private static final class ReporterThreadFactory implements ThreadFactory {
        @Override
        public Thread newThread(Runnable runnable) {
            Thread thread = new Thread(runnable, "bandwidthoptimizer-bypassed-play-reporter");
            thread.setDaemon(true);
            return thread;
        }
    }
}
