package com.PinkCats.bandwidthoptimizer.channel;

import com.PinkCats.bandwidthoptimizer.Bandwidthoptimizer;
import com.PinkCats.bandwidthoptimizer.Config;
import com.PinkCats.bandwidthoptimizer.debug.DebugRuntimeConfig;
import com.PinkCats.bandwidthoptimizer.util.BandwidthOptimizerOutputPaths;

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

public final class ChannelTransportBypassRankCore {

    private static final Object LOCK = new Object();
    private static final Map<BypassKey, BypassCounter> COUNTERS = new LinkedHashMap<>();
    private static final DateTimeFormatter REPORT_DISPLAY_TIMESTAMP = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss", Locale.ROOT);
    private static final String LATEST_REPORT_FILE_NAME = "latest-bypass-report.md";
    private static final String REPORT_PROTOCOL = "PLAY";

    private static long windowBypassCount;
    private static long windowBypassBytes;
    private static long totalBypassCount;
    private static long totalBypassBytes;
    private static long nextPeriodicDumpAtMillis;
    private static long nextReportFailureLogAtMillis;

    private ChannelTransportBypassRankCore() {}

    public static void recordEncodedPacket(
            String reason,
            String protocolName,
            String packetFlowName,
            String packetClassName,
            String payloadChannel,
            int rawPacketId,
            int packetBytes,
            String channelId
    ) {
        long nowMillis = System.currentTimeMillis();
        BypassKey key = new BypassKey(
                textOrFallback(reason, "<unknown-reason>"),
                textOrFallback(protocolName, "<unknown-protocol>"),
                textOrFallback(packetFlowName, "<unknown-flow>"),
                textOrFallback(packetClassName, "<unknown-packet>"),
                textOrFallback(payloadChannel, ""),
                rawPacketId
        );
        synchronized (LOCK) {
            if (nextPeriodicDumpAtMillis <= 0L) {
                nextPeriodicDumpAtMillis = nowMillis + readIntervalMillis();
            }

            BypassCounter counter = COUNTERS.computeIfAbsent(key, ignored -> new BypassCounter());
            counter.record(packetBytes, textOrFallback(channelId, "<no-channel>"));
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

    public static int tryReadLeadingVarInt(byte[] packetBytes) {
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

    public static int lengthOf(byte[] packetBytes) {
        return packetBytes == null ? 0 : packetBytes.length;
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
                    reportFlowLabel(key.packetFlow()),
                    key.packetClassName(),
                    key.payloadChannel().isEmpty() ? "<none>" : key.payloadChannel(),
                    key.rawPacketId(),
                    counter.lastChannelId()
            );
        }
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
        List<Map.Entry<BypassKey, BypassCounter>> reportEntries = filterReportProtocol(entries);
        List<Map.Entry<BypassKey, BypassCounter>> analysisEntries = filterAnalysisEntries(reportEntries);
        List<Map.Entry<BypassKey, BypassCounter>> bypassOptimalEntries = filterBypassOptimalEntries(reportEntries);
        StringBuilder builder = new StringBuilder(4096);
        builder.append("# BandwidthOptimizer Transport Bypass Report").append(System.lineSeparator());
        builder.append(System.lineSeparator());
        builder.append("- Generated at: `").append(reportTimestamp).append('`').append(System.lineSeparator());
        builder.append("- Flush reason: `").append(markdownCell(reason)).append('`').append(System.lineSeparator());
        builder.append("- Protocol filter: `").append(REPORT_PROTOCOL).append('`').append(System.lineSeparator());
        long reportWindowCount = sumWindowCount(reportEntries);
        long reportWindowBytes = sumWindowBytes(reportEntries);
        long reportTotalCount = sumTotalCount(reportEntries);
        long reportTotalBytes = sumTotalBytes(reportEntries);
        builder.append("- Window: `").append(reportWindowCount).append(" packets`, `")
                .append(reportWindowBytes).append(" bytes / ").append(formatBytes(reportWindowBytes)).append('`').append(System.lineSeparator());
        builder.append("- Total: `").append(reportTotalCount).append(" packets`, `")
                .append(reportTotalBytes).append(" bytes / ").append(formatBytes(reportTotalBytes)).append('`').append(System.lineSeparator());
        builder.append("- Keys: `").append(reportEntries.size()).append("`, TopN: `").append(topN).append('`').append(System.lineSeparator());
        if (!bypassOptimalEntries.isEmpty()) {
            builder.append("- Bypass optimal/semi-bypass keys: `").append(bypassOptimalEntries.size()).append('`').append(System.lineSeparator());
        }
        builder.append("- Note: only aggregated counters are stored; payload bytes are not retained.").append(System.lineSeparator());
        builder.append(System.lineSeparator());
        appendReasonCodeLegend(builder, reportEntries);
        builder.append("## Bypass Rank").append(System.lineSeparator());
        builder.append(System.lineSeparator());
        builder.append("| Rank | Window Count | Window Bytes | Total Count | Total Bytes | Avg Bytes | Reason | Flow | Packet Class | Payload Channel | Raw Packet ID | Last Channel |").append(System.lineSeparator());
        builder.append("|---:|---:|---:|---:|---:|---:|---|---|---|---|---:|---|").append(System.lineSeparator());

        int emitted = 0;
        for (Map.Entry<BypassKey, BypassCounter> entry : analysisEntries) {
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
        appendIncompleteOptimizationSection(builder, analysisEntries);
        appendBypassOptimalSection(builder, bypassOptimalEntries);

        try {
            Files.createDirectories(reportDirectory);
            Files.writeString(latestReport, builder.toString(), StandardCharsets.UTF_8);
        } catch (IOException exception) {
            logReportFailureThrottled(exception);
        }
    }

    private static void appendReportEntry(StringBuilder builder, int rank, BypassKey key, BypassCounter counter) {
        appendCommonEntryColumns(builder.append("| ").append(rank), key, counter)
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
        builder.append("| Miss | Cat | Window Count | Window Bytes | Total Count | Total Bytes | Avg Bytes | Reason | Flow | Packet Class | Payload Channel | Raw Packet ID | Last Channel |").append(System.lineSeparator());
        builder.append("|---|---|---:|---:|---:|---:|---:|---|---|---|---|---:|---|").append(System.lineSeparator());

        for (Map.Entry<BypassKey, BypassCounter> entry : missedEntries) {
            appendIncompleteOptimizationEntry(builder, entry.getKey(), entry.getValue());
        }
    }

    private static void appendBypassOptimalSection(StringBuilder builder, List<Map.Entry<BypassKey, BypassCounter>> entries) {
        if (entries.isEmpty()) {
            return;
        }
        List<Map.Entry<BypassKey, BypassCounter>> sortedEntries = new ArrayList<>(entries);
        sortedEntries.removeIf(entry -> entry.getValue().totalCount() <= 0L && entry.getValue().windowCount() <= 0L);
        if (sortedEntries.isEmpty()) {
            return;
        }
        sortedEntries.sort(Comparator
                .<Map.Entry<BypassKey, BypassCounter>>comparingLong(entry -> entry.getValue().totalBytes()).reversed()
                .thenComparing(entry -> entry.getKey().packetClassName())
                .thenComparing(entry -> entry.getKey().reason()));

        builder.append(System.lineSeparator());
        builder.append("## Bypass Optimal / Semi Bypass Packet List").append(System.lineSeparator());
        builder.append(System.lineSeparator());
        builder.append("Rows below were already marked by the current code path as direct/bypass-optimal, or are tiny chunk-related semi-bypass packets.").append(System.lineSeparator());
        builder.append(System.lineSeparator());
        builder.append("| Kind | Window Count | Window Bytes | Total Count | Total Bytes | Avg Bytes | Reason | Flow | Packet Class | Payload Channel | Raw Packet ID | Last Channel |").append(System.lineSeparator());
        builder.append("|---|---:|---:|---:|---:|---:|---|---|---|---|---:|---|").append(System.lineSeparator());

        for (Map.Entry<BypassKey, BypassCounter> entry : sortedEntries) {
            appendBypassOptimalEntry(builder, entry.getKey(), entry.getValue());
        }
    }

    private static void appendBypassOptimalEntry(StringBuilder builder, BypassKey key, BypassCounter counter) {
        appendCommonEntryColumns(builder.append("| `").append(markdownCell(bypassOptimalKind(key, counter))).append('`'), key, counter)
                .append(" | `").append(markdownCell(counter.lastChannelId())).append("` |")
                .append(System.lineSeparator());
    }

    private static void appendIncompleteOptimizationEntry(StringBuilder builder, BypassKey key, BypassCounter counter) {
        builder.append("| `NBT`")
                .append(" | `").append(markdownCell(categoryCode(classifyIncompleteOptimizationReason(key)))).append('`');
        appendCommonEntryColumns(builder, key, counter)
                .append(" | `").append(markdownCell(counter.lastChannelId())).append("` |")
                .append(System.lineSeparator());
    }

    private static StringBuilder appendCommonEntryColumns(StringBuilder builder, BypassKey key, BypassCounter counter) {
        return builder
                .append(" | ").append(counter.windowCount())
                .append(" | `").append(counter.windowBytes()).append(" / ").append(formatBytes(counter.windowBytes())).append('`')
                .append(" | ").append(counter.totalCount())
                .append(" | `").append(counter.totalBytes()).append(" / ").append(formatBytes(counter.totalBytes())).append('`')
                .append(" | ").append(counter.averageBytes())
                .append(" | `").append(markdownCell(reasonCode(key.reason()))).append('`')
                .append(" | `").append(markdownCell(reportFlowLabel(key.packetFlow()))).append('`')
                .append(" | `").append(markdownCell(key.packetClassName())).append('`')
                .append(" | `").append(key.payloadChannel().isEmpty() ? "<none>" : markdownCell(key.payloadChannel())).append('`')
                .append(" | ").append(key.rawPacketId());
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

    private static List<Map.Entry<BypassKey, BypassCounter>> filterReportProtocol(List<Map.Entry<BypassKey, BypassCounter>> entries) {
        List<Map.Entry<BypassKey, BypassCounter>> filtered = new ArrayList<>();
        for (Map.Entry<BypassKey, BypassCounter> entry : entries) {
            if (REPORT_PROTOCOL.equals(entry.getKey().protocolName())) {
                filtered.add(entry);
            }
        }
        return filtered;
    }

    private static List<Map.Entry<BypassKey, BypassCounter>> filterAnalysisEntries(List<Map.Entry<BypassKey, BypassCounter>> entries) {
        List<Map.Entry<BypassKey, BypassCounter>> filtered = new ArrayList<>();
        for (Map.Entry<BypassKey, BypassCounter> entry : entries) {
            if (!isBypassOptimalEntry(entry.getKey(), entry.getValue())) {
                filtered.add(entry);
            }
        }
        return filtered;
    }

    private static List<Map.Entry<BypassKey, BypassCounter>> filterBypassOptimalEntries(List<Map.Entry<BypassKey, BypassCounter>> entries) {
        List<Map.Entry<BypassKey, BypassCounter>> filtered = new ArrayList<>();
        for (Map.Entry<BypassKey, BypassCounter> entry : entries) {
            if (isBypassOptimalEntry(entry.getKey(), entry.getValue())) {
                filtered.add(entry);
            }
        }
        return filtered;
    }

    private static boolean isBypassOptimalEntry(BypassKey key, BypassCounter counter) {
        String reason = key == null ? "" : textOrFallback(key.reason(), "");
        if (reason.startsWith("transparent_bypass")) {
            return true;
        }
        if (reason.contains("heavy_chunk_protocol_bypass")) {
            return true;
        }
        return isTinyChunkSemiBypassPacket(key, counter);
    }

    private static boolean isTinyChunkSemiBypassPacket(BypassKey key, BypassCounter counter) {
        if (key == null || counter == null || counter.averageBytes() <= 0 || counter.averageBytes() > 200) {
            return false;
        }
        String packetClassName = textOrFallback(key.packetClassName(), "");
        return packetClassName.endsWith("ClientboundLightUpdatePacket")
                || packetClassName.endsWith("ClientboundBlockUpdatePacket");
    }

    private static String bypassOptimalKind(BypassKey key, BypassCounter counter) {
        return isTinyChunkSemiBypassPacket(key, counter) ? "SEMI" : "OPT";
    }

    private static void appendReasonCodeLegend(StringBuilder builder, List<Map.Entry<BypassKey, BypassCounter>> entries) {
        Map<String, String> codes = new LinkedHashMap<>();
        for (Map.Entry<BypassKey, BypassCounter> entry : entries) {
            codes.putIfAbsent(reasonCode(entry.getKey().reason()), reasonLabel(entry.getKey().reason()));
        }
        if (codes.isEmpty()) {
            return;
        }
        builder.append("## Reason Codes").append(System.lineSeparator());
        builder.append(System.lineSeparator());
        for (Map.Entry<String, String> entry : codes.entrySet()) {
            builder.append("- `").append(markdownCell(entry.getKey())).append("`: ")
                    .append(markdownCell(entry.getValue()))
                    .append(System.lineSeparator());
        }
        builder.append(System.lineSeparator());
    }

    private static String reasonCode(String reason) {
        String value = textOrFallback(reason, "");
        if (value.startsWith("connection_strong_boundary:")) {
            return "SB";
        }
        if (value.startsWith("connection_interaction_boundary:")) {
            return "IB";
        }
        if (value.contains("bundle")) {
            return "BD";
        }
        if (value.contains("keep_alive")) {
            return "KA";
        }
        if (value.startsWith("protocol_boundary")) {
            return "PB";
        }
        if (value.startsWith("packet_send_listener")) {
            return "PL";
        }
        if (value.startsWith("transparent_bypass")) {
            return "TB";
        }
        if (value.contains("heavy_chunk_protocol_bypass")) {
            return "HB";
        }
        if (value.contains("serverbound_carrier_size")) {
            return "SZ";
        }
        if (value.contains("unprofitable")) {
            return "UP";
        }
        if (value.contains("disabled")) {
            return "DS";
        }
        if (value.contains("chunk")) {
            return "CH";
        }
        return "OT";
    }

    private static String reasonLabel(String reason) {
        String value = textOrFallback(reason, "");
        if (value.startsWith("connection_strong_boundary:")) {
            return "connection_strong_boundary";
        }
        if (value.startsWith("connection_interaction_boundary:")) {
            return "connection_interaction_boundary";
        }
        if (value.contains("bundle")) {
            return "bundle_boundary";
        }
        if (value.contains("keep_alive")) {
            return "keep_alive_boundary";
        }
        if (value.startsWith("protocol_boundary")) {
            return "protocol_boundary";
        }
        if (value.startsWith("packet_send_listener")) {
            return "packet_send_listener";
        }
        if (value.startsWith("transparent_bypass")) {
            return "transparent_bypass";
        }
        if (value.contains("heavy_chunk_protocol_bypass")) {
            return "heavy_chunk_protocol_bypass";
        }
        if (value.contains("serverbound_carrier_size")) {
            return "serverbound_carrier_size";
        }
        if (value.contains("unprofitable")) {
            return "unprofitable_transport";
        }
        if (value.contains("disabled")) {
            return "transport_disabled";
        }
        if (value.contains("chunk")) {
            return "chunk_transport";
        }
        return value.isBlank() ? "other" : value;
    }

    private static String categoryCode(String category) {
        return switch (category) {
            case "forced_strong_boundary" -> "SB";
            case "forced_interaction_boundary" -> "IB";
            case "forced_bundle_boundary" -> "BD";
            case "forced_keep_alive_boundary" -> "KA";
            case "forced_protocol_boundary" -> "PB";
            case "forced_packet_send_listener" -> "PL";
            case "transparent_direct_or_packet_blacklist" -> "TB";
            case "serverbound_carrier_size_fallback" -> "SZ";
            case "unprofitable_transport_fallback" -> "UP";
            case "transport_disabled_fallback" -> "DS";
            case "chunk_transport_fallback" -> "CH";
            default -> "OT";
        };
    }

    private static long sumWindowCount(List<Map.Entry<BypassKey, BypassCounter>> entries) {
        long total = 0L;
        for (Map.Entry<BypassKey, BypassCounter> entry : entries) {
            total += entry.getValue().windowCount();
        }
        return total;
    }

    private static long sumWindowBytes(List<Map.Entry<BypassKey, BypassCounter>> entries) {
        long total = 0L;
        for (Map.Entry<BypassKey, BypassCounter> entry : entries) {
            total += entry.getValue().windowBytes();
        }
        return total;
    }

    private static long sumTotalCount(List<Map.Entry<BypassKey, BypassCounter>> entries) {
        long total = 0L;
        for (Map.Entry<BypassKey, BypassCounter> entry : entries) {
            total += entry.getValue().totalCount();
        }
        return total;
    }

    private static long sumTotalBytes(List<Map.Entry<BypassKey, BypassCounter>> entries) {
        long total = 0L;
        for (Map.Entry<BypassKey, BypassCounter> entry : entries) {
            total += entry.getValue().totalBytes();
        }
        return total;
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

    private static String reportFlowLabel(String packetFlow) {
        String value = textOrFallback(packetFlow, "").trim();
        if ("SERVERBOUND".equals(value)) {
            return "\u2192SERVER";
        }
        if ("CLIENTBOUND".equals(value)) {
            return "\u2192CLIENT";
        }
        return "NONE";
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
