package com.PinkCats.bandwidthoptimizer.channel;

import com.PinkCats.bandwidthoptimizer.Bandwidthoptimizer;
import com.PinkCats.bandwidthoptimizer.Config;
import com.PinkCats.bandwidthoptimizer.debug.DebugRuntimeConfig;
import com.PinkCats.bandwidthoptimizer.util.BandwidthOptimizerOutputPaths;
import io.netty.channel.ChannelHandlerContext;
import net.minecraft.network.protocol.Packet;
import net.minecraft.network.protocol.PacketFlow;
import net.minecraft.network.protocol.common.ClientboundCustomPayloadPacket;
import net.minecraft.network.protocol.common.ServerboundCustomPayloadPacket;
import net.minecraft.resources.ResourceLocation;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

public final class ChannelTransportBypassRankLogger {

    private static final Object LOCK = new Object();
    private static final Map<BypassKey, BypassCounter> COUNTERS = new LinkedHashMap<>();
    private static long windowBypassCount;
    private static long windowBypassBytes;
    private static long totalBypassCount;
    private static long totalBypassBytes;
    private static long nextPeriodicDumpAtMillis;
    private static long nextReportFailureLogAtMillis;
    private static final DateTimeFormatter REPORT_DISPLAY_TIMESTAMP = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss", Locale.ROOT);
    private static final String LATEST_REPORT_FILE_NAME = "latest-bypass-report.md";

    private ChannelTransportBypassRankLogger() {}


    public static void recordPacket(
            ChannelHandlerContext context,
            String reason,
            String protocolName,
            Packet<?> packet,
            PacketFlow packetFlow,
            byte[] packetBytes
    ) {
        if (packet == null) {
            recordEncodedPacket(
                    context,
                    reason,
                    protocolName,
                    packetFlow,
                    "<unknown-packet>",
                    null,
                    tryReadLeadingVarInt(packetBytes),
                    lengthOf(packetBytes)
            );
            return;
        }

        recordEncodedPacket(
                context,
                reason,
                protocolName,
                packetFlow,
                packet.getClass().getName(),
                customPayloadChannel(packet),
                tryReadLeadingVarInt(packetBytes),
                lengthOf(packetBytes)
        );
    }

    public static void recordEncodedPacket(
            ChannelHandlerContext context,
            String reason,
            String protocolName,
            PacketFlow packetFlow,
            String packetClassName,
            ResourceLocation payloadChannel,
            int rawPacketId,
            int packetBytes
    ) {
        long nowMillis = System.currentTimeMillis();
        String channelId = channelIdText(context);
        BypassKey key = new BypassKey(
                textOrFallback(reason, "<unknown-reason>"),
                textOrFallback(protocolName, "<unknown-protocol>"),
                packetFlow == null ? "<unknown-flow>" : packetFlow.name(),
                textOrFallback(packetClassName, "<unknown-packet>"),
                payloadChannel == null ? "" : payloadChannel.toString(),
                rawPacketId
        );
        synchronized (LOCK) {
            if (nextPeriodicDumpAtMillis <= 0L) {
                nextPeriodicDumpAtMillis = nowMillis + readIntervalMillis();
            }

            BypassCounter counter = COUNTERS.computeIfAbsent(key, ignored -> new BypassCounter());
            counter.record(packetBytes, channelId);
            windowBypassCount++;
            windowBypassBytes += Math.max(packetBytes, 0);
            totalBypassCount++;
            totalBypassBytes += Math.max(packetBytes, 0);

            long intervalMillis = readIntervalMillis();
            if (intervalMillis > 0L && nowMillis >= nextPeriodicDumpAtMillis) {
                dumpLocked("periodic");
                nextPeriodicDumpAtMillis = nowMillis + intervalMillis;
            }
        }
    }

    public static void dumpNow(String reason) {
        synchronized (LOCK) {
            if (windowBypassCount <= 0L && windowBypassBytes <= 0L) {
                return;
            }
            dumpLocked(textOrFallback(reason, "manual"));
            nextPeriodicDumpAtMillis = System.currentTimeMillis() + readIntervalMillis();
        }
    }

    private static void dumpLocked(String reason) {
        List<Map.Entry<BypassKey, BypassCounter>> entries = new ArrayList<>(COUNTERS.entrySet());
        entries.sort(Comparator
                .<Map.Entry<BypassKey, BypassCounter>>comparingLong(entry -> entry.getValue().windowBytes())
                .reversed()
                .thenComparing(entry -> entry.getKey().packetClassName())
                .thenComparing(entry -> entry.getKey().reason()));

        int topN = Math.max(readTopN(), 1);
        if (isLogEnabled()) {
            Bandwidthoptimizer.LOGGER.info(
                    "[Transport][BypassRank] reason={}, windowCount={}, windowBytes={}({}), totalCount={}, totalBytes={}({}), keys={}, topN={}",
                    reason,
                    windowBypassCount,
                    windowBypassBytes,
                    formatBytes(windowBypassBytes),
                    totalBypassCount,
                    totalBypassBytes,
                    formatBytes(totalBypassBytes),
                    entries.size(),
                    topN
            );
            logEntries(entries, topN);
        }
        if (isReportEnabled()) {
            writeReportLocked(reason, entries, topN);
        }

        for (BypassCounter counter : COUNTERS.values()) {
            counter.resetWindow();
        }
        windowBypassCount = 0L;
        windowBypassBytes = 0L;
    }

    private static void logEntries(List<Map.Entry<BypassKey, BypassCounter>> entries, int topN) {
        int emitted = 0;
        for (Map.Entry<BypassKey, BypassCounter> entry : entries) {
            if (emitted >= topN) {
                break;
            }
            BypassCounter counter = entry.getValue();
            if (counter.windowCount() <= 0L && counter.windowBytes() <= 0L) {
                continue;
            }
            emitted++;
            BypassKey key = entry.getKey();
            Bandwidthoptimizer.LOGGER.info(
                    "[Transport][BypassRank][Entry] rank={}, windowCount={}, windowBytes={}({}), totalCount={}, totalBytes={}({}), avgBytes={}, reason={}, protocol={}, flow={}, packetClass={}, payloadChannel={}, rawPacketId={}, lastChannel={}",
                    emitted,
                    counter.windowCount(),
                    counter.windowBytes(),
                    formatBytes(counter.windowBytes()),
                    counter.totalCount(),
                    counter.totalBytes(),
                    formatBytes(counter.totalBytes()),
                    counter.averageBytes(),
                    key.reason(),
                    key.protocolName(),
                    key.packetFlow(),
                    key.packetClassName(),
                    key.payloadChannel().isEmpty() ? "<none>" : key.payloadChannel(),
                    key.rawPacketId(),
                    counter.lastChannelId()
            );
        }
    }

    private static ResourceLocation customPayloadChannel(Packet<?> packet) {
        if (packet instanceof ClientboundCustomPayloadPacket clientboundCustomPayloadPacket) {
            return clientboundCustomPayloadPacket.payload().type().id();
        }
        if (packet instanceof ServerboundCustomPayloadPacket serverboundCustomPayloadPacket) {
            return serverboundCustomPayloadPacket.payload().type().id();
        }
        return null;
    }

    private static int tryReadLeadingVarInt(byte[] packetBytes) {
        if (packetBytes == null || packetBytes.length == 0) {
            return -1;
        }
        int value = 0;
        int position = 0;
        for (int index = 0; index < packetBytes.length && index < 5; index++) {
            int current = packetBytes[index] & 0xFF;
            value |= (current & 0x7F) << position;
            if ((current & 0x80) == 0) {
                return value;
            }
            position += 7;
        }
        return -1;
    }

    private static String channelIdText(ChannelHandlerContext context) {
        if (context == null || context.channel() == null) {
            return "<no-channel>";
        }
        try {
            return context.channel().id().asShortText();
        } catch (Throwable ignored) {
            return "<unknown-channel>";
        }
    }

    private static int lengthOf(byte[] packetBytes) {
        return packetBytes == null ? 0 : packetBytes.length;
    }

    private static String textOrFallback(String value, String fallback) {
        return value == null || value.isBlank() ? fallback : value;
    }

    private static boolean isLogEnabled() {
        if (!DebugRuntimeConfig.isAnalysisEnabled()) {
            return false;
        }
        return readBoolean(
                Config.RuntimeProperty.Transport.BYPASS_RANK_LOG_ENABLED,
                Config.RuntimeProperty.Transport.DEFAULT_BYPASS_RANK_LOG_ENABLED
        );
    }

    private static boolean isReportEnabled() {
        return readBoolean(
                Config.RuntimeProperty.Transport.BYPASS_RANK_REPORT_ENABLED,
                Config.RuntimeProperty.Transport.DEFAULT_BYPASS_RANK_REPORT_ENABLED
        );
    }

    private static void writeReportLocked(String reason, List<Map.Entry<BypassKey, BypassCounter>> entries, int topN) {
        Path reportDirectory = resolveReportDirectory();
        LocalDateTime now = LocalDateTime.now();
        String reportTimestamp = REPORT_DISPLAY_TIMESTAMP.format(now);
        Path latestReport = reportDirectory.resolve(LATEST_REPORT_FILE_NAME);
        StringBuilder builder = new StringBuilder(4096);
        builder.append("# BandwidthOptimizer Transport Bypass Report").append(System.lineSeparator());
        builder.append(System.lineSeparator());
        builder.append("- Generated at: `").append(reportTimestamp).append('`').append(System.lineSeparator());
        builder.append("- Flush reason: `").append(markdownCell(reason)).append('`').append(System.lineSeparator());
        builder.append("- Window: `").append(windowBypassCount).append(" packets`, `")
                .append(windowBypassBytes).append(" bytes / ").append(formatBytes(windowBypassBytes)).append('`').append(System.lineSeparator());
        builder.append("- Total: `").append(totalBypassCount).append(" packets`, `")
                .append(totalBypassBytes).append(" bytes / ").append(formatBytes(totalBypassBytes)).append('`').append(System.lineSeparator());
        builder.append("- Keys: `").append(entries.size()).append("`, TopN: `").append(topN).append('`').append(System.lineSeparator());
        builder.append("- Note: only aggregated counters are stored; payload bytes are not retained.").append(System.lineSeparator());
        builder.append(System.lineSeparator());
        builder.append("## Bypass Rank").append(System.lineSeparator());
        builder.append(System.lineSeparator());
        builder.append("| Rank | Window Count | Window Bytes | Total Count | Total Bytes | Avg Bytes | Reason | Protocol | Flow | Packet Class | Payload Channel | Raw Packet ID | Last Channel |").append(System.lineSeparator());
        builder.append("|---:|---:|---:|---:|---:|---:|---|---|---|---|---|---:|---|").append(System.lineSeparator());

        int emitted = 0;
        for (Map.Entry<BypassKey, BypassCounter> entry : entries) {
            if (emitted >= topN) {
                break;
            }
            BypassCounter counter = entry.getValue();
            if (counter.windowCount() <= 0L && counter.windowBytes() <= 0L) {
                continue;
            }
            emitted++;
            appendReportEntry(builder, emitted, entry.getKey(), counter);
        }
        appendIncompleteOptimizationSection(builder, entries);

        try {
            Files.createDirectories(reportDirectory);
            String report = builder.toString();
            Files.writeString(latestReport, report, StandardCharsets.UTF_8);
        } catch (IOException exception) {
            logReportFailureThrottled(exception);
        }
    }

    private static void appendReportEntry(StringBuilder builder, int rank, BypassKey key, BypassCounter counter) {
        builder.append("| ").append(rank)
                .append(" | ").append(counter.windowCount())
                .append(" | `").append(counter.windowBytes()).append(" / ").append(formatBytes(counter.windowBytes())).append('`')
                .append(" | ").append(counter.totalCount())
                .append(" | `").append(counter.totalBytes()).append(" / ").append(formatBytes(counter.totalBytes())).append('`')
                .append(" | ").append(counter.averageBytes())
                .append(" | `").append(markdownCell(key.reason())).append('`')
                .append(" | `").append(markdownCell(key.protocolName())).append('`')
                .append(" | `").append(markdownCell(key.packetFlow())).append('`')
                .append(" | `").append(markdownCell(key.packetClassName())).append('`')
                .append(" | `").append(key.payloadChannel().isEmpty() ? "<none>" : markdownCell(key.payloadChannel())).append('`')
                .append(" | ").append(key.rawPacketId())
                .append(" | `").append(markdownCell(counter.lastChannelId())).append("` |")
                .append(System.lineSeparator());
    }

    private static void appendIncompleteOptimizationSection(StringBuilder builder, List<Map.Entry<BypassKey, BypassCounter>> entries) {
        List<Map.Entry<BypassKey, BypassCounter>> missedEntries = new ArrayList<>(entries);
        missedEntries.removeIf(entry -> entry.getValue().totalCount() <= 0L && entry.getValue().windowCount() <= 0L);
        missedEntries.sort(Comparator
                .<Map.Entry<BypassKey, BypassCounter>, String>comparing(entry -> classifyIncompleteOptimizationReason(entry.getKey()))
                .thenComparing(Comparator.<Map.Entry<BypassKey, BypassCounter>>comparingLong(entry -> entry.getValue().totalBytes()).reversed())
                .thenComparing(entry -> entry.getKey().packetClassName())
                .thenComparing(entry -> entry.getKey().reason()));

        builder.append(System.lineSeparator());
        builder.append("## Incomplete Batch/Zstd/Template Dictionary Packet List").append(System.lineSeparator());
        builder.append(System.lineSeparator());
        builder.append("Every row below is a direct/bypass/fallback packet key that did not fully enter batch, zstd, and template dictionary transport.").append(System.lineSeparator());
        builder.append(System.lineSeparator());
        builder.append("| Miss Stage | Category | Window Count | Window Bytes | Total Count | Total Bytes | Avg Bytes | Reason | Protocol | Flow | Packet Class | Payload Channel | Raw Packet ID | Last Channel |").append(System.lineSeparator());
        builder.append("|---|---|---:|---:|---:|---:|---:|---|---|---|---|---|---:|---|").append(System.lineSeparator());

        for (Map.Entry<BypassKey, BypassCounter> entry : missedEntries) {
            appendIncompleteOptimizationEntry(builder, entry.getKey(), entry.getValue());
        }
    }

    private static void appendIncompleteOptimizationEntry(StringBuilder builder, BypassKey key, BypassCounter counter) {
        builder.append("| `no_batch_zstd_template_dictionary`")
                .append(" | `").append(markdownCell(classifyIncompleteOptimizationReason(key))).append('`')
                .append(" | ").append(counter.windowCount())
                .append(" | `").append(counter.windowBytes()).append(" / ").append(formatBytes(counter.windowBytes())).append('`')
                .append(" | ").append(counter.totalCount())
                .append(" | `").append(counter.totalBytes()).append(" / ").append(formatBytes(counter.totalBytes())).append('`')
                .append(" | ").append(counter.averageBytes())
                .append(" | `").append(markdownCell(key.reason())).append('`')
                .append(" | `").append(markdownCell(key.protocolName())).append('`')
                .append(" | `").append(markdownCell(key.packetFlow())).append('`')
                .append(" | `").append(markdownCell(key.packetClassName())).append('`')
                .append(" | `").append(key.payloadChannel().isEmpty() ? "<none>" : markdownCell(key.payloadChannel())).append('`')
                .append(" | ").append(key.rawPacketId())
                .append(" | `").append(markdownCell(counter.lastChannelId())).append("` |")
                .append(System.lineSeparator());
    }

    private static String classifyIncompleteOptimizationReason(BypassKey key) {
        String reason = key == null ? "" : textOrFallback(key.reason(), "");
        if (reason.startsWith("connection_strong_boundary:")) {
            return "forced_strong_boundary";
        }
        if (reason.startsWith("connection_interaction_boundary:")) {
            return "forced_interaction_boundary";
        }
        if (reason.contains("bundle")) {
            return "forced_bundle_boundary";
        }
        if (reason.contains("keep_alive")) {
            return "forced_keep_alive_boundary";
        }
        if (reason.startsWith("protocol_boundary")) {
            return "forced_protocol_boundary";
        }
        if (reason.startsWith("packet_send_listener")) {
            return "forced_packet_send_listener";
        }
        if (reason.startsWith("transparent_bypass")) {
            return "transparent_direct_or_packet_blacklist";
        }
        if (reason.contains("serverbound_carrier_size")) {
            return "serverbound_carrier_size_fallback";
        }
        if (reason.contains("unprofitable")) {
            return "unprofitable_transport_fallback";
        }
        if (reason.contains("disabled")) {
            return "transport_disabled_fallback";
        }
        if (reason.contains("chunk")) {
            return "chunk_transport_fallback";
        }
        return "other_direct_or_fallback";
    }

    private static Path resolveReportDirectory() {
        String directory = readString(
                Config.RuntimeProperty.Transport.BYPASS_RANK_REPORT_DIRECTORY,
                Config.RuntimeProperty.Transport.DEFAULT_BYPASS_RANK_REPORT_DIRECTORY
        );
        return BandwidthOptimizerOutputPaths.resolveDirectory(directory);
    }

    private static void logReportFailureThrottled(IOException exception) {
        long nowMillis = System.currentTimeMillis();
        if (nowMillis < nextReportFailureLogAtMillis) {
            return;
        }
        nextReportFailureLogAtMillis = nowMillis + 60_000L;
        Bandwidthoptimizer.LOGGER.warn("[Transport][BypassRank] Failed to write bypass report", exception);
    }

    private static String markdownCell(String value) {
        return textOrFallback(value, "")
                .replace("\\", "\\\\")
                .replace("|", "\\|")
                .replace('\n', ' ')
                .replace('\r', ' ')
                .replace('`', '\'');
    }

    private static String sanitizeReportValue(String value) {
        return textOrFallback(value, "").replace('\t', ' ').replace('\n', ' ').replace('\r', ' ');
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
        if (rawValue == null || rawValue.isBlank()) {
            return fallback;
        }
        return rawValue;
    }

    private static long readIntervalMillis() {
        String rawValue = System.getProperty(Config.RuntimeProperty.Transport.BYPASS_RANK_LOG_INTERVAL_MILLIS);
        if (rawValue == null || rawValue.isBlank()) {
            return Config.RuntimeProperty.Transport.DEFAULT_BYPASS_RANK_LOG_INTERVAL_MILLIS;
        }
        try {
            return Math.max(Long.parseLong(rawValue), 0L);
        } catch (NumberFormatException ignored) {
            return Config.RuntimeProperty.Transport.DEFAULT_BYPASS_RANK_LOG_INTERVAL_MILLIS;
        }
    }

    private static int readTopN() {
        String rawValue = System.getProperty(Config.RuntimeProperty.Transport.BYPASS_RANK_LOG_TOP_N);
        if (rawValue == null || rawValue.isBlank()) {
            return Config.RuntimeProperty.Transport.DEFAULT_BYPASS_RANK_LOG_TOP_N;
        }
        try {
            return Math.max(Integer.parseInt(rawValue), 1);
        } catch (NumberFormatException ignored) {
            return Config.RuntimeProperty.Transport.DEFAULT_BYPASS_RANK_LOG_TOP_N;
        }
    }

    private static String formatBytes(long bytes) {
        if (bytes >= 1024L * 1024L) {
            return String.format(java.util.Locale.ROOT, "%.2fMiB", bytes / 1024.0D / 1024.0D);
        }
        if (bytes >= 1024L) {
            return String.format(java.util.Locale.ROOT, "%.2fKiB", bytes / 1024.0D);
        }
        return bytes + "B";
    }

    private record BypassKey(
            String reason,
            String protocolName,
            String packetFlow,
            String packetClassName,
            String payloadChannel,
            int rawPacketId
    ) {
    }

    private static final class BypassCounter {

        private long windowCount;
        private long windowBytes;
        private long totalCount;
        private long totalBytes;
        private String lastChannelId = "<no-channel>";

        private void record(int packetBytes, String channelId) {
            int safePacketBytes = Math.max(packetBytes, 0);
            this.windowCount++;
            this.windowBytes += safePacketBytes;
            this.totalCount++;
            this.totalBytes += safePacketBytes;
            this.lastChannelId = channelId;
        }

        private void resetWindow() {
            this.windowCount = 0L;
            this.windowBytes = 0L;
        }

        private long averageBytes() {
            return this.totalCount <= 0L ? 0L : this.totalBytes / this.totalCount;
        }

        private long windowCount() {
            return this.windowCount;
        }

        private long windowBytes() {
            return this.windowBytes;
        }

        private long totalCount() {
            return this.totalCount;
        }

        private long totalBytes() {
            return this.totalBytes;
        }

        private String lastChannelId() {
            return this.lastChannelId;
        }
    }
}
