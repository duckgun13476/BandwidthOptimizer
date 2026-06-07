package com.PinkCats.bandwidthoptimizer.report;

import com.PinkCats.bandwidthoptimizer.Bandwidthoptimizer;
import com.PinkCats.bandwidthoptimizer.util.BandwidthOptimizerOutputPaths;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;
import java.util.concurrent.atomic.LongAdder;

public final class ChannelTransportSourceRankCore {

    private static final String ENABLED_PROPERTY = "bandwidthoptimizer.transport.sourceRankReportEnabled";
    private static final String INTERVAL_MILLIS_PROPERTY = "bandwidthoptimizer.transport.sourceRankReportIntervalMillis";
    private static final String TOP_N_PROPERTY = "bandwidthoptimizer.transport.sourceRankReportTopN";
    private static final String REPORT_DIRECTORY_PROPERTY = "bandwidthoptimizer.transport.sourceRankReportDirectory";
    private static final boolean DEFAULT_ENABLED = true;
    private static final long DEFAULT_INTERVAL_MILLIS = 10_000L;
    private static final int DEFAULT_TOP_N = 80;
    private static final String DEFAULT_REPORT_DIRECTORY = "transport-source-report";
    private static final String LATEST_REPORT_FILE_NAME = "latest-source-report.md";
    private static final DateTimeFormatter REPORT_DISPLAY_TIMESTAMP = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss", Locale.ROOT);
    private static final ConcurrentHashMap<SourceKey, SourceCounter> COUNTERS = new ConcurrentHashMap<>();
    private static final LongAdder CREATE_BLOCK_ENTITY_PACKET_COUNT = new LongAdder();
    private static final LongAdder CREATE_BLOCK_ENTITY_RAW_BYTES = new LongAdder();
    private static final LongAdder CREATE_BLOCK_ENTITY_ACTUAL_BYTES = new LongAdder();
    private static final AtomicLong NEXT_REPORT_AT_MILLIS = new AtomicLong();
    private static final AtomicLong NEXT_FAILURE_LOG_AT_MILLIS = new AtomicLong();
    private static final AtomicBoolean REPORT_SCHEDULED = new AtomicBoolean();
    private static final AtomicReference<String> PENDING_REPORT_REASON = new AtomicReference<>();
    private static final ExecutorService REPORT_EXECUTOR = Executors.newSingleThreadExecutor(runnable -> {
        Thread thread = new Thread(runnable, "bo-transport-source-rank");
        thread.setDaemon(true);
        thread.setContextClassLoader(ChannelTransportSourceRankCore.class.getClassLoader());
        return thread;
    });

    private ChannelTransportSourceRankCore() {}


    public static void recordOutboundPacket(
            String sourceKey,
            String packetClassName,
            int rawPacketId,
            int rawPacketBytes,
            int transportInputBytes,
            int actualFrameBytes,
            boolean chunkProtocolApplied,
            boolean actualFrameBytesEstimated,
            String actualPath,
            String actualFrameKind,
            int batchPacketCount,
            String channelId
    ) {
        recordCreateBlockEntityTransport(sourceKey, rawPacketBytes, actualFrameBytes);
        if (!isEnabled()) {
            return;
        }

        long nowMillis = System.currentTimeMillis();
        SourceKey key = new SourceKey(
                textOrFallback(sourceKey, "packet:<unknown>"),
                textOrFallback(packetClassName, "<unknown-packet>"),
                rawPacketId
        );
        COUNTERS.computeIfAbsent(key, ignored -> new SourceCounter()).record(
                rawPacketBytes,
                transportInputBytes,
                actualFrameBytes,
                chunkProtocolApplied,
                actualFrameBytesEstimated,
                textOrFallback(actualPath, "<unknown-path>"),
                textOrFallback(actualFrameKind, "<unknown-frame>"),
                Math.max(batchPacketCount, 1),
                textOrFallback(channelId, "<no-channel>")
        );
        scheduleReportIfDue(nowMillis);
    }

    public static CreateBlockEntityTransportSnapshot snapshotCreateBlockEntityTransportStats() {
        long rawBytes = CREATE_BLOCK_ENTITY_RAW_BYTES.sum();
        long actualBytes = CREATE_BLOCK_ENTITY_ACTUAL_BYTES.sum();
        return new CreateBlockEntityTransportSnapshot(
                CREATE_BLOCK_ENTITY_PACKET_COUNT.sum(),
                rawBytes,
                actualBytes
        );
    }

    public static void resetCreateBlockEntityTransportStats() {
        CREATE_BLOCK_ENTITY_PACKET_COUNT.reset();
        CREATE_BLOCK_ENTITY_RAW_BYTES.reset();
        CREATE_BLOCK_ENTITY_ACTUAL_BYTES.reset();
    }

    public static void dumpNow(String reason) {
        if (!isEnabled()) {
            return;
        }
        scheduleReport(textOrFallback(reason, "manual"));
        NEXT_REPORT_AT_MILLIS.set(System.currentTimeMillis() + readIntervalMillis());
    }

    private static void scheduleReportIfDue(long nowMillis) {
        long intervalMillis = readIntervalMillis();
        if (intervalMillis <= 0L) {
            return;
        }
        long currentTarget = NEXT_REPORT_AT_MILLIS.get();
        if (currentTarget <= 0L) {
            NEXT_REPORT_AT_MILLIS.compareAndSet(currentTarget, nowMillis + intervalMillis);
            return;
        }
        if (nowMillis < currentTarget || !NEXT_REPORT_AT_MILLIS.compareAndSet(currentTarget, nowMillis + intervalMillis)) {
            return;
        }
        scheduleReport("periodic");
    }

    private static void scheduleReport(String reason) {
        PENDING_REPORT_REASON.set(textOrFallback(reason, "manual"));
        scheduleReportWorker();
    }

    private static void scheduleReportWorker() {
        if (!REPORT_SCHEDULED.compareAndSet(false, true)) {
            return;
        }
        REPORT_EXECUTOR.execute(() -> {
            try {
                String reason;
                while ((reason = PENDING_REPORT_REASON.getAndSet(null)) != null) {
                    List<SourceReportEntry> entries = snapshotEntries();
                    if (!entries.isEmpty()) {
                        writeReport(reason, entries, readTopN());
                    }
                }
            } finally {
                REPORT_SCHEDULED.set(false);
                if (PENDING_REPORT_REASON.get() != null) {
                    scheduleReportWorker();
                }
            }
        });
    }

    private static List<SourceReportEntry> snapshotEntries() {
        List<SourceReportEntry> entries = new ArrayList<>(COUNTERS.size());
        for (Map.Entry<SourceKey, SourceCounter> entry : COUNTERS.entrySet()) {
            SourceCounterSnapshot snapshot = entry.getValue().snapshotAndResetWindow();
            if (snapshot.totalCount() <= 0L) {
                continue;
            }
            entries.add(new SourceReportEntry(entry.getKey(), snapshot));
        }
        return entries;
    }

    private static void writeReport(String reason, List<SourceReportEntry> entries, int topN) {
        long windowCount = 0L;
        long windowRawBytes = 0L;
        long windowActualBytes = 0L;
        long totalCount = 0L;
        long totalRawBytes = 0L;
        long totalActualBytes = 0L;
        for (SourceReportEntry entry : entries) {
            SourceCounterSnapshot counter = entry.counter();
            windowCount += counter.windowCount();
            windowRawBytes += counter.windowRawBytes();
            windowActualBytes += counter.windowActualBytes();
            totalCount += counter.totalCount();
            totalRawBytes += counter.totalRawBytes();
            totalActualBytes += counter.totalActualBytes();
        }

        StringBuilder builder = new StringBuilder(8192);
        builder.append("# BandwidthOptimizer Transport Source Report").append(System.lineSeparator());
        builder.append(System.lineSeparator());
        builder.append("- Generated at: `").append(REPORT_DISPLAY_TIMESTAMP.format(LocalDateTime.now())).append('`').append(System.lineSeparator());
        builder.append("- Flush reason: `").append(markdownCell(reason)).append('`').append(System.lineSeparator());
        builder.append("- Window: `").append(windowCount).append(" packets`, raw `")
                .append(windowRawBytes).append(" / ").append(formatBytes(windowRawBytes))
                .append("`, actual `").append(windowActualBytes).append(" / ").append(formatBytes(windowActualBytes)).append('`')
                .append(System.lineSeparator());
        builder.append("- Total: `").append(totalCount).append(" packets`, raw `")
                .append(totalRawBytes).append(" / ").append(formatBytes(totalRawBytes))
                .append("`, actual `").append(totalActualBytes).append(" / ").append(formatBytes(totalActualBytes)).append('`')
                .append(System.lineSeparator());
        builder.append("- Keys: `").append(entries.size()).append("`, TopN: `").append(topN).append('`').append(System.lineSeparator());
        builder.append("- Note: only aggregated counters are stored; payload bytes are not retained.").append(System.lineSeparator());
        builder.append(System.lineSeparator());

        appendSourceTable(builder, "## Source Rank By Window Raw Bytes", sortByWindowRaw(entries), topN);
        appendSourceTable(builder, "## Source Rank By Window Actual Bytes", sortByWindowActual(entries), topN);
        appendSourceTable(builder, "## Source Rank By Total Raw Bytes", sortByTotalRaw(entries), topN);
        appendSourceTable(builder, "## Source Rank By Total Actual Bytes", sortByTotalActual(entries), topN);

        try {
            Path reportDirectory = resolveReportDirectory();
            Files.createDirectories(reportDirectory);
            Files.writeString(reportDirectory.resolve(LATEST_REPORT_FILE_NAME), builder.toString(), StandardCharsets.UTF_8);
        } catch (IOException exception) {
            logReportFailureThrottled(exception);
        }
    }

    private static void appendSourceTable(StringBuilder builder, String title, List<SourceReportEntry> entries, int topN) {
        builder.append(title).append(System.lineSeparator());
        builder.append(System.lineSeparator());
        builder.append("| Rank | Source | Packet Class | Raw Packet ID | Window Count | Window Raw | Window Actual | Total Count | Total Raw | Total Actual | Ratio | Chunk | Estimated | Last Path | Last Frame | Last Channel |").append(System.lineSeparator());
        builder.append("|---:|---|---|---:|---:|---:|---:|---:|---:|---:|---:|---:|---:|---|---|---|").append(System.lineSeparator());
        int emitted = 0;
        for (SourceReportEntry entry : entries) {
            SourceCounterSnapshot counter = entry.counter();
            if (counter.windowCount() <= 0L && title.contains("Window")) {
                continue;
            }
            if (emitted >= topN) {
                break;
            }
            emitted++;
            SourceKey key = entry.key();
            builder.append("| ").append(emitted)
                    .append(" | `").append(markdownCell(key.sourceKey())).append('`')
                    .append(" | `").append(markdownCell(simpleClassName(key.packetClassName()))).append('`')
                    .append(" | ").append(key.rawPacketId())
                    .append(" | ").append(counter.windowCount())
                    .append(" | `").append(counter.windowRawBytes()).append(" / ").append(formatBytes(counter.windowRawBytes())).append('`')
                    .append(" | `").append(counter.windowActualBytes()).append(" / ").append(formatBytes(counter.windowActualBytes())).append('`')
                    .append(" | ").append(counter.totalCount())
                    .append(" | `").append(counter.totalRawBytes()).append(" / ").append(formatBytes(counter.totalRawBytes())).append('`')
                    .append(" | `").append(counter.totalActualBytes()).append(" / ").append(formatBytes(counter.totalActualBytes())).append('`')
                    .append(" | `").append(ratioText(counter.totalActualBytes(), counter.totalRawBytes())).append('`')
                    .append(" | ").append(counter.chunkWrappedCount())
                    .append(" | ").append(counter.estimatedActualCount())
                    .append(" | `").append(markdownCell(counter.lastActualPath())).append('`')
                    .append(" | `").append(markdownCell(counter.lastActualFrameKind())).append('`')
                    .append(" | `").append(markdownCell(counter.lastChannelId())).append('`')
                    .append(" |").append(System.lineSeparator());
        }
        builder.append(System.lineSeparator());
    }

    private static List<SourceReportEntry> sortByWindowRaw(List<SourceReportEntry> entries) {
        return entries.stream()
                .sorted(Comparator
                        .comparingLong((SourceReportEntry entry) -> entry.counter().windowRawBytes()).reversed()
                        .thenComparing(entry -> entry.key().sourceKey()))
                .toList();
    }

    private static List<SourceReportEntry> sortByWindowActual(List<SourceReportEntry> entries) {
        return entries.stream()
                .sorted(Comparator
                        .comparingLong((SourceReportEntry entry) -> entry.counter().windowActualBytes()).reversed()
                        .thenComparing(entry -> entry.key().sourceKey()))
                .toList();
    }

    private static List<SourceReportEntry> sortByTotalRaw(List<SourceReportEntry> entries) {
        return entries.stream()
                .sorted(Comparator
                        .comparingLong((SourceReportEntry entry) -> entry.counter().totalRawBytes()).reversed()
                        .thenComparing(entry -> entry.key().sourceKey()))
                .toList();
    }

    private static List<SourceReportEntry> sortByTotalActual(List<SourceReportEntry> entries) {
        return entries.stream()
                .sorted(Comparator
                        .comparingLong((SourceReportEntry entry) -> entry.counter().totalActualBytes()).reversed()
                        .thenComparing(entry -> entry.key().sourceKey()))
                .toList();
    }

    private static boolean isEnabled() {
        return readBoolean(ENABLED_PROPERTY, DEFAULT_ENABLED);
    }

    private static Path resolveReportDirectory() {
        return BandwidthOptimizerOutputPaths.resolveDirectory(readString(REPORT_DIRECTORY_PROPERTY, DEFAULT_REPORT_DIRECTORY));
    }

    private static long readIntervalMillis() {
        return readLong(INTERVAL_MILLIS_PROPERTY, DEFAULT_INTERVAL_MILLIS);
    }

    private static int readTopN() {
        return (int) Math.max(readLong(TOP_N_PROPERTY, DEFAULT_TOP_N), 1L);
    }

    private static boolean readBoolean(String propertyName, boolean fallback) {
        String rawValue = System.getProperty(propertyName);
        if (rawValue == null || rawValue.isBlank()) {
            return fallback;
        }
        return Boolean.parseBoolean(rawValue);
    }

    private static String readString(String propertyName, String fallback) {
        String rawValue = System.getProperty(propertyName);
        return rawValue == null || rawValue.isBlank() ? fallback : rawValue;
    }

    private static long readLong(String propertyName, long fallback) {
        String rawValue = System.getProperty(propertyName);
        if (rawValue == null || rawValue.isBlank()) {
            return fallback;
        }
        try {
            return Math.max(Long.parseLong(rawValue), 0L);
        } catch (NumberFormatException ignored) {
            return fallback;
        }
    }

    private static void logReportFailureThrottled(IOException exception) {
        long nowMillis = System.currentTimeMillis();
        long nextFailureLogAt = NEXT_FAILURE_LOG_AT_MILLIS.get();
        if (nowMillis < nextFailureLogAt || !NEXT_FAILURE_LOG_AT_MILLIS.compareAndSet(nextFailureLogAt, nowMillis + 60_000L)) {
            return;
        }
        Bandwidthoptimizer.LOGGER.warn("[Transport][SourceRank] Failed to write source report", exception);
    }

    private static String markdownCell(String value) {
        return textOrFallback(value, "")
                .replace("\\", "\\\\")
                .replace("|", "\\|")
                .replace('\n', ' ')
                .replace('\r', ' ')
                .replace('`', '\'');
    }

    private static void recordCreateBlockEntityTransport(String sourceKey, int rawPacketBytes, int actualFrameBytes) {
        if (sourceKey == null || !sourceKey.startsWith("block_entity:create:")) {
            return;
        }
        CREATE_BLOCK_ENTITY_PACKET_COUNT.increment();
        CREATE_BLOCK_ENTITY_RAW_BYTES.add(Math.max(rawPacketBytes, 0));
        CREATE_BLOCK_ENTITY_ACTUAL_BYTES.add(Math.max(actualFrameBytes, 0));
    }

    private static String simpleClassName(String className) {
        String value = textOrFallback(className, "<unknown>");
        int lastDotIndex = value.lastIndexOf('.');
        return lastDotIndex < 0 ? value : value.substring(lastDotIndex + 1);
    }

    private static String ratioText(long currentBytes, long baselineBytes) {
        if (baselineBytes <= 0L) {
            return "n/a";
        }
        return String.format(Locale.ROOT, "%.3fx", currentBytes / (double) baselineBytes);
    }

    private static String formatBytes(long bytes) {
        if (bytes >= 1024L * 1024L) {
            return String.format(Locale.ROOT, "%.2fMiB", bytes / 1024.0D / 1024.0D);
        }
        if (bytes >= 1024L) {
            return String.format(Locale.ROOT, "%.2fKiB", bytes / 1024.0D);
        }
        return bytes + "B";
    }

    private static String textOrFallback(String value, String fallback) {
        return value == null || value.isBlank() ? fallback : value;
    }

    private record SourceKey(String sourceKey, String packetClassName, int rawPacketId) {
    }

    private record SourceReportEntry(SourceKey key, SourceCounterSnapshot counter) {
    }

    public record CreateBlockEntityTransportSnapshot(
            long packets,
            long rawBytes,
            long actualBytes
    ) {
        public long savedBytes() {
            return Math.max(this.rawBytes - this.actualBytes, 0L);
        }
    }

    private record SourceCounterSnapshot(
            long windowCount,
            long windowRawBytes,
            long windowTransportInputBytes,
            long windowActualBytes,
            long totalCount,
            long totalRawBytes,
            long totalTransportInputBytes,
            long totalActualBytes,
            long chunkWrappedCount,
            long estimatedActualCount,
            String lastActualPath,
            String lastActualFrameKind,
            String lastChannelId
    ) {
    }

    private static final class SourceCounter {
        private final LongAdder windowCount = new LongAdder();
        private final LongAdder windowRawBytes = new LongAdder();
        private final LongAdder windowTransportInputBytes = new LongAdder();
        private final LongAdder windowActualBytes = new LongAdder();
        private final LongAdder totalCount = new LongAdder();
        private final LongAdder totalRawBytes = new LongAdder();
        private final LongAdder totalTransportInputBytes = new LongAdder();
        private final LongAdder totalActualBytes = new LongAdder();
        private final LongAdder chunkWrappedCount = new LongAdder();
        private final LongAdder estimatedActualCount = new LongAdder();
        private volatile String lastActualPath = "<unknown-path>";
        private volatile String lastActualFrameKind = "<unknown-frame>";
        private volatile String lastChannelId = "<no-channel>";

        private void record(
                int rawPacketBytes,
                int transportInputBytes,
                int actualFrameBytes,
                boolean chunkProtocolApplied,
                boolean actualFrameBytesEstimated,
                String actualPath,
                String actualFrameKind,
                int batchPacketCount,
                String channelId
        ) {
            int safeRawBytes = Math.max(rawPacketBytes, 0);
            int safeTransportInputBytes = Math.max(transportInputBytes, 0);
            int safeActualFrameBytes = Math.max(actualFrameBytes, 0);
            this.windowCount.increment();
            this.windowRawBytes.add(safeRawBytes);
            this.windowTransportInputBytes.add(safeTransportInputBytes);
            this.windowActualBytes.add(safeActualFrameBytes);
            this.totalCount.increment();
            this.totalRawBytes.add(safeRawBytes);
            this.totalTransportInputBytes.add(safeTransportInputBytes);
            this.totalActualBytes.add(safeActualFrameBytes);
            if (chunkProtocolApplied) {
                this.chunkWrappedCount.increment();
            }
            if (actualFrameBytesEstimated || batchPacketCount > 1) {
                this.estimatedActualCount.increment();
            }
            this.lastActualPath = actualPath;
            this.lastActualFrameKind = actualFrameKind;
            this.lastChannelId = channelId;
        }

        private SourceCounterSnapshot snapshotAndResetWindow() {
            return new SourceCounterSnapshot(
                    this.windowCount.sumThenReset(),
                    this.windowRawBytes.sumThenReset(),
                    this.windowTransportInputBytes.sumThenReset(),
                    this.windowActualBytes.sumThenReset(),
                    this.totalCount.sum(),
                    this.totalRawBytes.sum(),
                    this.totalTransportInputBytes.sum(),
                    this.totalActualBytes.sum(),
                    this.chunkWrappedCount.sum(),
                    this.estimatedActualCount.sum(),
                    this.lastActualPath,
                    this.lastActualFrameKind,
                    this.lastChannelId
            );
        }
    }
}
