package com.PinkCats.bandwidthoptimizer.experient;

import com.PinkCats.bandwidthoptimizer.chunk.protocol.ChunkHotspotFrameOp;

import java.io.BufferedReader;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

// 1. server/send.jsonl <-> client/receive.jsonl
// 2. client/send.jsonl <-> server/receive.jsonl
public final class ChannelJsonlCompareMain {

    private static final int MAX_MISMATCHES_TO_PRINT = 5;
    private static final Path DEFAULT_SERVER_SEND = Path.of("run", "server", "send.jsonl");
    private static final Path DEFAULT_CLIENT_RECEIVE = Path.of("run", "client", "receive.jsonl");
    private static final Path DEFAULT_CLIENT_SEND = Path.of("run", "client", "send.jsonl");
    private static final Path DEFAULT_SERVER_RECEIVE = Path.of("run", "server", "receive.jsonl");
    private static final Path DEFAULT_SERVER_CHUNK_HOTSPOT_STATS = Path.of("run", "server", "chunk-hotspot-stats.properties");
    private static final Path DEFAULT_CLIENT_CHUNK_HOTSPOT_STATS = Path.of("run", "client", "chunk-hotspot-stats.properties");

    private ChannelJsonlCompareMain() {}

    public static void main(String[] args) {
        try {
            int exitCode = runComparison(args);
            if (exitCode != 0) {
                System.exit(exitCode);
            }
        } catch (Exception exception) {
            System.err.println("ChannelJsonlCompare failed: " + exception.getMessage());
            System.exit(1);
        }
    }

    private static int runComparison(String[] args) throws IOException {
        List<ComparisonTarget> targets = createTargets(args);
        List<ComparisonReport> reports = new ArrayList<>();
        boolean allMatched = true;

        for (ComparisonTarget target : targets) {
            ComparisonReport report = comparePair(target);
            reports.add(report);
            if (!report.matched()) {
                allMatched = false;
            }
        }

        ChunkHotspotVerificationReport chunkHotspotVerificationReport = verifyChunkHotspotReports();
        boolean overallMatched = allMatched && chunkHotspotVerificationReport.matchedOrSkipped();
        printReports(reports, chunkHotspotVerificationReport, overallMatched);
        return overallMatched ? 0 : 1;
    }

    private static List<ComparisonTarget> createTargets(String[] args) {
        if (args.length == 0) {
            return List.of(
                    new ComparisonTarget("server/send -> client/receive", DEFAULT_SERVER_SEND, DEFAULT_CLIENT_RECEIVE),
                    new ComparisonTarget("client/send -> server/receive", DEFAULT_CLIENT_SEND, DEFAULT_SERVER_RECEIVE)
            );
        }

        if (args.length == 2) {
            return List.of(new ComparisonTarget("custom", Path.of(args[0]), Path.of(args[1])));
        }

        throw new IllegalArgumentException("用法: 无参数，或传入 2 个路径参数");
    }

    // compare jsonl
    private static ComparisonReport comparePair(ComparisonTarget target) throws IOException {
        ensureFileExists(target.leftPath());
        ensureFileExists(target.rightPath());

        List<MismatchDetail> mismatches = new ArrayList<>();
        long comparedLines = 0L;
        boolean stoppedEarly = false;

        try (BufferedReader leftReader = Files.newBufferedReader(target.leftPath());
             BufferedReader rightReader = Files.newBufferedReader(target.rightPath())) {

            while (true) {
                String leftLine = leftReader.readLine();
                String rightLine = rightReader.readLine();

                if (leftLine == null && rightLine == null) {
                    break;
                }

                comparedLines++;

                if (leftLine == null || rightLine == null) {
                    mismatches.add(new MismatchDetail(
                            comparedLines,
                            describeLengthMismatch(leftLine, rightLine)
                    ));
                    break;
                }

                ComparableFrame leftFrame = parseComparableFrame(leftLine, target.leftPath(), comparedLines);
                ComparableFrame rightFrame = parseComparableFrame(rightLine, target.rightPath(), comparedLines);
                String difference = describeFieldDifference(leftFrame, rightFrame);
                if (difference != null) {
                    mismatches.add(new MismatchDetail(comparedLines, difference));
                    if (mismatches.size() >= MAX_MISMATCHES_TO_PRINT) {
                        stoppedEarly = true;
                        break;
                    }
                }
            }
        }

        return new ComparisonReport(target, comparedLines, mismatches, stoppedEarly);
    }

    private static void ensureFileExists(Path path) {
        if (!Files.exists(path)) {
            throw new IllegalArgumentException("Can't find file: " + path.toAbsolutePath());
        }
    }

    private static ComparableFrame parseComparableFrame(String jsonLine, Path sourcePath, long lineNumber) {
        try {
            return new ComparableFrame(
                    extractStringField(jsonLine, "protocol"),
                    extractStringField(jsonLine, "packet_class"),
                    extractIntField(jsonLine, "packet_id"),
                    extractIntField(jsonLine, "byte_length"),
                    extractStringField(jsonLine, "payload_hex"),
                    extractStringField(jsonLine, "semantic_fingerprint")
            );
        } catch (RuntimeException exception) {
            throw new IllegalArgumentException(
                    "解析失败: " + sourcePath.toAbsolutePath() + " 第 " + lineNumber + " 行: " + exception.getMessage(),
                    exception
            );
        }
    }


    private static String extractStringField(String jsonLine, String fieldName) {
        int valueStart = findValueStart(jsonLine, fieldName);
        if (valueStart >= jsonLine.length() || jsonLine.charAt(valueStart) != '"') {
            throw new IllegalArgumentException("字段不是字符串: " + fieldName);
        }

        StringBuilder builder = new StringBuilder();
        boolean escaping = false;
        for (int index = valueStart + 1; index < jsonLine.length(); index++) {
            char current = jsonLine.charAt(index);
            if (escaping) {
                builder.append(unescapeJsonChar(current));
                escaping = false;
                continue;
            }
            if (current == '\\') {
                escaping = true;
                continue;
            }
            if (current == '"') {
                return builder.toString();
            }
            builder.append(current);
        }

        throw new IllegalArgumentException("字符串字段未闭合: " + fieldName);
    }

    private static int extractIntField(String jsonLine, String fieldName) {
        int valueStart = findValueStart(jsonLine, fieldName);
        int valueEnd = valueStart;
        while (valueEnd < jsonLine.length()) {
            char current = jsonLine.charAt(valueEnd);
            if ((current >= '0' && current <= '9') || current == '-') {
                valueEnd++;
                continue;
            }
            break;
        }

        if (valueStart == valueEnd) {
            throw new IllegalArgumentException("字段不是整数: " + fieldName);
        }

        return Integer.parseInt(jsonLine.substring(valueStart, valueEnd));
    }

    private static int findValueStart(String jsonLine, String fieldName) {
        String token = "\"" + fieldName + "\":";
        int keyIndex = jsonLine.indexOf(token);
        if (keyIndex < 0) {
            throw new IllegalArgumentException("缺少字段: " + fieldName);
        }
        return keyIndex + token.length();
    }



    private static String describeFieldDifference(ComparableFrame leftFrame, ComparableFrame rightFrame) {
        List<String> differences = new ArrayList<>();

        if (!Objects.equals(leftFrame.protocol(), rightFrame.protocol())) {
            differences.add("protocol: " + leftFrame.protocol() + " != " + rightFrame.protocol());
        }
        if (!Objects.equals(leftFrame.packetClass(), rightFrame.packetClass())) {
            differences.add("packet_class: " + leftFrame.packetClass() + " != " + rightFrame.packetClass());
        }
        if (leftFrame.packetId() != rightFrame.packetId()) {
            differences.add("packet_id: " + leftFrame.packetId() + " != " + rightFrame.packetId());
        }
        if (leftFrame.byteLength() != rightFrame.byteLength()) {
            differences.add("byte_length: " + leftFrame.byteLength() + " != " + rightFrame.byteLength());
        }
        if (!Objects.equals(leftFrame.payloadHex(), rightFrame.payloadHex())) {
            differences.add(
                    "payload_hex: "
                            + abbreviate(leftFrame.payloadHex()) + " != "
                            + abbreviate(rightFrame.payloadHex())
            );
        }
        if (!Objects.equals(leftFrame.semanticFingerprint(), rightFrame.semanticFingerprint())) {
            differences.add(
                    "semantic_fingerprint: "
                            + leftFrame.semanticFingerprint() + " != "
                            + rightFrame.semanticFingerprint()
            );
        }

        if (differences.isEmpty()) {
            return null;
        }
        return String.join(" | ", differences);
    }


    private static String describeLengthMismatch(String leftLine, String rightLine) {
        if (leftLine == null) {
            return "左侧文件已结束，但右侧还有更多行";
        }
        return "右侧文件已结束，但左侧还有更多行";
    }

    private static String abbreviate(String value) {
        if (value == null) {
            return "null";
        }
        if (value.length() <= 96) {
            return value;
        }
        return value.substring(0, 96) + "...(len=" + value.length() + ")";
    }

    // 这个函数把 jsonl 等价性结果和 chunk 特化层统计对齐结果一起输出，形成统一的主验证出口。
    private static void printReports(
            List<ComparisonReport> reports,
            ChunkHotspotVerificationReport chunkHotspotVerificationReport,
            boolean allMatched
    ) {
        System.out.println("=== ChannelJsonlCompare ===");
        for (ComparisonReport report : reports) {
            if (report.matched()) {
                System.out.println(report.target().label() + ": 匹配, 共 " + report.comparedLines() + " 行");
                continue;
            }

            System.out.println(report.target().label() + ": 不匹配, 已比较 " + report.comparedLines() + " 行");
            int printedCount = 0;
            for (MismatchDetail mismatch : report.mismatches()) {
                printedCount++;
                System.out.println("  " + printedCount + ". 第 " + mismatch.lineNumber() + " 行: " + mismatch.message());
            }
            if (report.stoppedEarly()) {
                System.out.println("  已达到前 " + MAX_MISMATCHES_TO_PRINT + " 个差异上限，后续不再继续输出。");
            }
        }

        printChunkHotspotVerificationReport(chunkHotspotVerificationReport);
        System.out.println(allMatched ? "Result: Match" : "Result: Mismatch");
    }

    private static ChunkHotspotVerificationReport verifyChunkHotspotReports() throws IOException {
        ChunkHotspotStatsFile serverStats = readChunkHotspotStatsFile(DEFAULT_SERVER_CHUNK_HOTSPOT_STATS);
        ChunkHotspotStatsFile clientStats = readChunkHotspotStatsFile(DEFAULT_CLIENT_CHUNK_HOTSPOT_STATS);
        if (serverStats == null || clientStats == null) {
            return ChunkHotspotVerificationReport.skipped(serverStats, clientStats);
        }

        List<String> mismatches = new ArrayList<>();
        compareChunkDirection("server[outbound] -> client[inbound]", serverStats, "outbound", clientStats, "inbound", mismatches);
        compareChunkDirection("client[outbound] -> server[inbound]", clientStats, "outbound", serverStats, "inbound", mismatches);
        return new ChunkHotspotVerificationReport(serverStats, clientStats, mismatches);
    }

    private static ChunkHotspotStatsFile readChunkHotspotStatsFile(Path path) throws IOException {
        if (!Files.exists(path)) {
            return null;
        }

        Map<String, String> values = new HashMap<>();
        for (String line : Files.readAllLines(path, StandardCharsets.UTF_8)) {
            String trimmedLine = line == null ? "" : line.trim();
            if (trimmedLine.isEmpty() || trimmedLine.startsWith("#")) {
                continue;
            }

            int separatorIndex = trimmedLine.indexOf('=');
            if (separatorIndex <= 0) {
                throw new IllegalArgumentException("chunk stats 文件格式非法: " + path.toAbsolutePath() + " -> " + trimmedLine);
            }
            values.put(
                    trimmedLine.substring(0, separatorIndex).trim(),
                    trimmedLine.substring(separatorIndex + 1).trim()
            );
        }
        return new ChunkHotspotStatsFile(path, Map.copyOf(values));
    }

    private static void compareChunkDirection(
            String label,
            ChunkHotspotStatsFile leftReport,
            String leftPrefix,
            ChunkHotspotStatsFile rightReport,
            String rightPrefix,
            List<String> mismatches
    ) {
        compareChunkStatField(label, leftReport, leftPrefix + "_total_frames", rightReport, rightPrefix + "_total_frames", mismatches);
        compareChunkStatField(label, leftReport, leftPrefix + "_total_logical_packet_bytes", rightReport, rightPrefix + "_total_logical_packet_bytes", mismatches);
        compareChunkStatField(label, leftReport, leftPrefix + "_total_wire_frame_bytes", rightReport, rightPrefix + "_total_wire_frame_bytes", mismatches);

        for (ChunkHotspotFrameOp operation : ChunkHotspotFrameOp.values()) {
            String operationName = operation.logName();
            compareChunkStatField(label, leftReport, leftPrefix + "_" + operationName + "_frames", rightReport, rightPrefix + "_" + operationName + "_frames", mismatches);
            compareChunkStatField(label, leftReport, leftPrefix + "_" + operationName + "_logical_packet_bytes", rightReport, rightPrefix + "_" + operationName + "_logical_packet_bytes", mismatches);
            compareChunkStatField(label, leftReport, leftPrefix + "_" + operationName + "_wire_frame_bytes", rightReport, rightPrefix + "_" + operationName + "_wire_frame_bytes", mismatches);
        }
    }

    private static void compareChunkStatField(
            String label,
            ChunkHotspotStatsFile leftReport,
            String leftKey,
            ChunkHotspotStatsFile rightReport,
            String rightKey,
            List<String> mismatches
    ) {
        long leftValue = readChunkStatLong(leftReport, leftKey);
        long rightValue = readChunkStatLong(rightReport, rightKey);
        if (leftValue != rightValue) {
            mismatches.add(label + ": " + leftKey + "=" + leftValue + " != " + rightKey + "=" + rightValue);
        }
    }

    private static long readChunkStatLong(ChunkHotspotStatsFile report, String key) {
        if (report == null) {
            throw new IllegalArgumentException("chunk stats 报告缺失，无法读取字段: " + key);
        }

        String rawValue = report.values().get(key);
        if (rawValue == null) {
            throw new IllegalArgumentException("chunk stats 缺少字段: " + report.path().toAbsolutePath() + " -> " + key);
        }

        try {
            return Long.parseLong(rawValue);
        } catch (NumberFormatException exception) {
            throw new IllegalArgumentException("chunk stats 字段不是整数: " + report.path().toAbsolutePath() + " -> " + key + "=" + rawValue, exception);
        }
    }

    private static void printChunkHotspotVerificationReport(ChunkHotspotVerificationReport report) {
        System.out.println("=== ChunkHotspotVerify ===");
        if (report == null || report.skipped()) {
            System.out.println("chunk hotspot stats: 跳过（未找到 server/client 统计文件）");
            return;
        }

        System.out.println("server stats: " + formatChunkHotspotSummary(report.serverStats()));
        System.out.println("client stats: " + formatChunkHotspotSummary(report.clientStats()));
        if (report.matched()) {
            System.out.println("chunk hotspot stats: 匹配");
            return;
        }

        System.out.println("chunk hotspot stats: 不匹配");
        int printedCount = 0;
        for (String mismatch : report.mismatches()) {
            printedCount++;
            System.out.println("  " + printedCount + ". " + mismatch);
            if (printedCount >= MAX_MISMATCHES_TO_PRINT) {
                break;
            }
        }
        if (report.mismatches().size() > MAX_MISMATCHES_TO_PRINT) {
            System.out.println("  已达到前 " + MAX_MISMATCHES_TO_PRINT + " 个差异上限，后续不再继续输出。");
        }
    }

    private static String formatChunkHotspotSummary(ChunkHotspotStatsFile statsFile) {
        if (statsFile == null) {
            return "<missing>";
        }

        return "path=" + statsFile.path().toAbsolutePath()
                + ", side=" + statsFile.values().getOrDefault("physical_side", "<unknown>")
                + ", outboundFrames=" + readChunkStatLong(statsFile, "outbound_total_frames")
                + ", outboundFull=" + readChunkStatLong(statsFile, "outbound_publish_full_frames")
                + ", outboundRef=" + readChunkStatLong(statsFile, "outbound_publish_ref_frames")
                + ", outboundPatch=" + readChunkStatLong(statsFile, "outbound_publish_patch_frames")
                + ", inboundFrames=" + readChunkStatLong(statsFile, "inbound_total_frames")
                + ", inboundFull=" + readChunkStatLong(statsFile, "inbound_publish_full_frames")
                + ", inboundRef=" + readChunkStatLong(statsFile, "inbound_publish_ref_frames")
                + ", inboundPatch=" + readChunkStatLong(statsFile, "inbound_publish_patch_frames")
                + ", outboundSavedVsLogicalBytes=" + readChunkStatLong(statsFile, "outbound_total_saved_vs_logical_bytes")
                + ", inboundSavedVsLogicalBytes=" + readChunkStatLong(statsFile, "inbound_total_saved_vs_logical_bytes");
    }

    private record ComparisonTarget(String label, Path leftPath, Path rightPath) { }

    private record ComparableFrame(
            String protocol,
            String packetClass,
            int packetId,
            int byteLength,
            String payloadHex,
            String semanticFingerprint
    ) {
    }


    private record ComparisonReport(
            ComparisonTarget target,
            long comparedLines,
            List<MismatchDetail> mismatches,
            boolean stoppedEarly
    ) {
        private boolean matched() {
            return mismatches.isEmpty();
        }
    }





    private record MismatchDetail(long lineNumber, String message) {}

    private record ChunkHotspotStatsFile(Path path, Map<String, String> values) {
    }

    private record ChunkHotspotVerificationReport(
            ChunkHotspotStatsFile serverStats,
            ChunkHotspotStatsFile clientStats,
            List<String> mismatches,
            boolean skipped
    ) {
        private ChunkHotspotVerificationReport(ChunkHotspotStatsFile serverStats, ChunkHotspotStatsFile clientStats, List<String> mismatches) {
            this(serverStats, clientStats, List.copyOf(mismatches), false);
        }

        private static ChunkHotspotVerificationReport skipped(ChunkHotspotStatsFile serverStats, ChunkHotspotStatsFile clientStats) {
            return new ChunkHotspotVerificationReport(serverStats, clientStats, List.of(), true);
        }

        private boolean matched() {
            return !this.skipped && this.mismatches.isEmpty();
        }

        private boolean matchedOrSkipped() {
            return this.skipped || this.mismatches.isEmpty();
        }
    }

    private static char unescapeJsonChar(char current) {
        return switch (current) {
            case '\\' -> '\\';
            case '"' -> '"';
            case 'r' -> '\r';
            case 'n' -> '\n';
            case 't' -> '\t';
            default -> current;
        };
    }
}
