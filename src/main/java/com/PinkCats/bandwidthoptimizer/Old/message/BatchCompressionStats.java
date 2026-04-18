package com.PinkCats.bandwidthoptimizer.Old.message;

import com.PinkCats.bandwidthoptimizer.Bandwidthoptimizer;
import com.PinkCats.bandwidthoptimizer.Config;

import java.time.LocalTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayDeque;
import java.util.Locale;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.LongAdder;

public final class BatchCompressionStats {

    private static final DateTimeFormatter TIME_FORMAT = DateTimeFormatter.ofPattern("HH:mm:ss");
    private static final long RECENT_WINDOW_MILLIS = TimeUnit.MINUTES.toMillis(2);

    private static final LongAdder TOTAL_RAW_BYTES = new LongAdder();
    private static final LongAdder TOTAL_BATCHED_BYTES = new LongAdder();
    private static final LongAdder TOTAL_BATCH_COUNT = new LongAdder();
    private static final LongAdder TOTAL_PACKET_COUNT = new LongAdder();

    private static final AtomicLong LAST_RAW_BYTES = new AtomicLong();
    private static final AtomicLong LAST_BATCHED_BYTES = new AtomicLong();
    private static final AtomicLong LAST_BATCH_COUNT = new AtomicLong();
    private static final AtomicLong LAST_PACKET_COUNT = new AtomicLong();
    private static final AtomicLong LAST_LOG_MILLIS = new AtomicLong();
    private static final Object RECENT_LOCK = new Object();
    private static final ArrayDeque<Sample> RECENT_SAMPLES = new ArrayDeque<>();

    private BatchCompressionStats() {
    }

    public static void record(long rawBytes, long batchedBytes, int batchCount, int packetCount) {
        if (rawBytes < 0L || batchedBytes < 0L || batchCount < 0 || packetCount < 0) {
            return;
        }
        TOTAL_RAW_BYTES.add(rawBytes);
        TOTAL_BATCHED_BYTES.add(batchedBytes);
        TOTAL_BATCH_COUNT.add(batchCount);
        TOTAL_PACKET_COUNT.add(packetCount);
    }

    public static void logMinuteSnapshot(int activeConnections, String algorithmId, long windowMillis) {
        if (!Config.enableOptimizerStatsLogs) {
            return;
        }
        long now = System.currentTimeMillis();
        long lastLogMillis = LAST_LOG_MILLIS.get();
        if (now - lastLogMillis < Config.optimizerStatsLogIntervalMillis()
                || !LAST_LOG_MILLIS.compareAndSet(lastLogMillis, now)) {
            return;
        }

        long totalRaw = TOTAL_RAW_BYTES.sum();
        long totalBatched = TOTAL_BATCHED_BYTES.sum();
        long totalBatches = TOTAL_BATCH_COUNT.sum();
        long totalPackets = TOTAL_PACKET_COUNT.sum();

        long minuteRaw = totalRaw - LAST_RAW_BYTES.getAndSet(totalRaw);
        long minuteBatched = totalBatched - LAST_BATCHED_BYTES.getAndSet(totalBatched);
        long minuteBatches = totalBatches - LAST_BATCH_COUNT.getAndSet(totalBatches);
        long minutePackets = totalPackets - LAST_PACKET_COUNT.getAndSet(totalPackets);

        double totalRatio = totalRaw == 0L ? 100.0D : (double) totalBatched * 100.0D / (double) totalRaw;
        double minuteRawPerSecond = minuteRaw / 60.0D;
        double minuteBatchedPerSecond = minuteBatched / 60.0D;
        RecentWindow recentWindow = recordRecentAndGetWindow(now, totalRaw, totalBatched, totalBatches, totalPackets);
        double recentRatio = recentWindow.rawBytes() == 0L ? 100.0D : (double) recentWindow.batchedBytes() * 100.0D / (double) recentWindow.rawBytes();
        String totalDeltaLabel = formatDeltaLabel(100.0D - totalRatio);
        String recentDeltaLabel = formatDeltaLabel(100.0D - recentRatio);

        Bandwidthoptimizer.LOGGER.info(
                "[{}] Total Raw: {} ({}/s) | Total Batched: {} ({}/s) | Total Ratio: {} | Total {}"
                        + " | Recent2m Raw: {} ({}/s) | Recent2m Batched: {} ({}/s) | Recent2m Ratio: {} | Recent2m {}"
                        + " | Batches: {} | Packets: {} | Conns: {} | Algo: {} | Window: {}ms",
                LocalTime.now().format(TIME_FORMAT),
                formatBytes(totalRaw),
                formatBytes((long) minuteRawPerSecond),
                formatBytes(totalBatched),
                formatBytes((long) minuteBatchedPerSecond),
                formatPercent(totalRatio),
                totalDeltaLabel,
                formatBytes(recentWindow.rawBytes()),
                formatBytes((long) recentWindow.rawBytesPerSecond()),
                formatBytes(recentWindow.batchedBytes()),
                formatBytes((long) recentWindow.batchedBytesPerSecond()),
                formatPercent(recentRatio),
                recentDeltaLabel,
                totalBatches + " (+" + minuteBatches + "/interval)",
                totalPackets + " (+" + minutePackets + "/interval)",
                activeConnections,
                algorithmId,
                windowMillis
        );
    }

    private static RecentWindow recordRecentAndGetWindow(
            long now,
            long totalRaw,
            long totalBatched,
            long totalBatches,
            long totalPackets
    ) {
        synchronized (RECENT_LOCK) {
            RECENT_SAMPLES.addLast(new Sample(now, totalRaw, totalBatched, totalBatches, totalPackets));
            while (RECENT_SAMPLES.size() > 1 && now - RECENT_SAMPLES.peekFirst().timestampMillis() > RECENT_WINDOW_MILLIS) {
                RECENT_SAMPLES.removeFirst();
            }
            Sample first = RECENT_SAMPLES.peekFirst();
            if (first == null) {
                return new RecentWindow(0L, 0L, 0L, 0L, 0.0D, 0.0D);
            }
            long windowMillis = Math.max(now - first.timestampMillis(), 1L);
            long rawBytes = Math.max(totalRaw - first.rawBytes(), 0L);
            long batchedBytes = Math.max(totalBatched - first.batchedBytes(), 0L);
            long batches = Math.max(totalBatches - first.batchCount(), 0L);
            long packets = Math.max(totalPackets - first.packetCount(), 0L);
            double seconds = windowMillis / 1000.0D;
            return new RecentWindow(
                    rawBytes,
                    batchedBytes,
                    batches,
                    packets,
                    rawBytes / seconds,
                    batchedBytes / seconds
            );
        }
    }

    private static String formatPercent(double value) {
        return String.format(Locale.ROOT, "%.3f%%", value);
    }

    private static String formatDeltaLabel(double savedPercent) {
        if (savedPercent >= 0.0D) {
            return "Saved: " + formatPercent(savedPercent);
        }
        return "Overhead: " + formatPercent(-savedPercent);
    }

    private static String formatBytes(long bytes) {
        if (bytes < 1024L) {
            return bytes + " B";
        }
        double value = bytes;
        String[] units = {"KB", "MB", "GB", "TB"};
        int unitIndex = -1;
        while (value >= 1024.0D && unitIndex + 1 < units.length) {
            value /= 1024.0D;
            unitIndex++;
        }
        return String.format(Locale.ROOT, "%.2f %s", value, units[unitIndex]);
    }

    private record Sample(
            long timestampMillis,
            long rawBytes,
            long batchedBytes,
            long batchCount,
            long packetCount
    ) {
    }

    private record RecentWindow(
            long rawBytes,
            long batchedBytes,
            long batchCount,
            long packetCount,
            double rawBytesPerSecond,
            double batchedBytesPerSecond
    ) {
    }
}
