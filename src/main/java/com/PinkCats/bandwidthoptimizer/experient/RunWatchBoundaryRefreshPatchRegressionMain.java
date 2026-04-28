package com.PinkCats.bandwidthoptimizer.experient;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import java.io.BufferedReader;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

public final class RunWatchBoundaryRefreshPatchRegressionMain {

    private static final int MAX_MISMATCHES_TO_PRINT = 5;
    private static final Path DEFAULT_SERVER_SEND = Path.of("run", "server", "send.jsonl");
    private static final Path DEFAULT_CLIENT_RECEIVE = Path.of("run", "client", "receive.jsonl");
    private static final Path DEFAULT_CLIENT_SEND = Path.of("run", "client", "send.jsonl");
    private static final Path DEFAULT_SERVER_RECEIVE = Path.of("run", "server", "receive.jsonl");
    private static final Path DEFAULT_SERVER_LOG = Path.of("run", "runall-server-task.log");
    private static final Path DEFAULT_CLIENT_LOG = Path.of("run", "runall-client-task.log");
    private static final Path DEFAULT_MARKER = Path.of("run", "server", "bo-watch-boundary-refresh-patch.marker");
    private static final String SERVER_PATCH_REASON = "refresh_patch_after_watch_boundary";
    private static final String CLIENT_ACK_REASON = "runtime_patch_reused_after_watch_boundary";
    private static final List<String> FORBIDDEN_REASON_MARKERS = List.of(
            "runtime_patch_missing_full_base",
            "runtime_patch_apply_failed",
            "runtime_nack_received",
            "runtime_watch_boundary_refresh_patch_fallback_unavailable"
    );

    private RunWatchBoundaryRefreshPatchRegressionMain() {}

    public static void main(String[] args) {
        try {
            RegressionMarker marker = readRegressionMarker(DEFAULT_MARKER);
            List<ComparisonReport> compareReports = List.of(
                    comparePair("server/send -> client/receive", DEFAULT_SERVER_SEND, DEFAULT_CLIENT_RECEIVE),
                    comparePair("client/send -> server/receive", DEFAULT_CLIENT_SEND, DEFAULT_SERVER_RECEIVE)
            );
            List<String> failures = new ArrayList<>(collectComparisonFailures(compareReports));
            failures.addAll(collectLogFailures(marker, DEFAULT_SERVER_LOG, DEFAULT_CLIENT_LOG));
            printReport(marker, compareReports, failures);
            if (!failures.isEmpty()) {
                System.exit(1);
            }
        } catch (Exception exception) {
            System.err.println("Watch-boundary regression failed: " + exception.getMessage());
            System.exit(1);
        }
    }

    private static RegressionMarker readRegressionMarker(Path markerPath) throws IOException {
        ensureFileExists(markerPath);
        Map<String, String> values = new LinkedHashMap<>();
        for (String line : Files.readAllLines(markerPath, StandardCharsets.UTF_8)) {
            String trimmedLine = line == null ? "" : line.trim();
            if (trimmedLine.isEmpty() || trimmedLine.startsWith("#")) {
                continue;
            }
            int separatorIndex = trimmedLine.indexOf('=');
            if (separatorIndex <= 0) {
                throw new IllegalArgumentException("marker 格式非法: " + markerPath.toAbsolutePath() + " -> " + trimmedLine);
            }
            values.put(
                    trimmedLine.substring(0, separatorIndex).trim(),
                    trimmedLine.substring(separatorIndex + 1).trim()
            );
        }
        return new RegressionMarker(
                readRequiredString(values, "player_name"),
                readRequiredInt(values, "target_chunk_x"),
                readRequiredInt(values, "target_chunk_z"),
                readRequiredInt(values, "target_block_x"),
                readRequiredInt(values, "target_block_y"),
                readRequiredInt(values, "target_block_z"),
                readRequiredLong(values, "initial_full_version"),
                readRequiredLong(values, "final_full_version"),
                readOptionalString(values, "mutated_from"),
                readOptionalString(values, "mutated_to"),
                readOptionalString(values, "initial_full_hash"),
                readOptionalString(values, "final_full_hash")
        );
    }

    private static ComparisonReport comparePair(String label, Path leftPath, Path rightPath) throws IOException {
        ensureFileExists(leftPath);
        ensureFileExists(rightPath);

        List<MismatchDetail> mismatches = new ArrayList<>();
        long comparedLines = 0L;
        boolean stoppedEarly = false;

        try (BufferedReader leftReader = Files.newBufferedReader(leftPath, StandardCharsets.UTF_8);
             BufferedReader rightReader = Files.newBufferedReader(rightPath, StandardCharsets.UTF_8)) {

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
                            leftLine == null ? "左侧文件已结束，但右侧还有更多行" : "右侧文件已结束，但左侧还有更多行"
                    ));
                    break;
                }

                ComparableFrame leftFrame = parseComparableFrame(leftLine, leftPath, comparedLines);
                ComparableFrame rightFrame = parseComparableFrame(rightLine, rightPath, comparedLines);
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

        return new ComparisonReport(label, comparedLines, mismatches, stoppedEarly);
    }

    private static ComparableFrame parseComparableFrame(String jsonLine, Path sourcePath, long lineNumber) {
        try {
            JsonObject jsonObject = JsonParser.parseString(jsonLine).getAsJsonObject();
            return new ComparableFrame(
                    readJsonString(jsonObject, "protocol"),
                    readJsonString(jsonObject, "packet_class"),
                    readJsonInt(jsonObject, "packet_id"),
                    readJsonInt(jsonObject, "byte_length"),
                    readJsonString(jsonObject, "payload_hex"),
                    readJsonString(jsonObject, "semantic_fingerprint")
            );
        } catch (RuntimeException exception) {
            throw new IllegalArgumentException(
                    "解析 JSONL 失败: " + sourcePath.toAbsolutePath() + " 第 " + lineNumber + " 行: " + exception.getMessage(),
                    exception
            );
        }
    }


    private static List<String> collectLogFailures(
            RegressionMarker marker,
            Path serverLogPath,
            Path clientLogPath
    ) throws IOException {
        ensureFileExists(serverLogPath);
        ensureFileExists(clientLogPath);

        List<String> failures = new ArrayList<>();
        String chunkMarker = marker.chunkMarker();
        List<String> serverLines = Files.readAllLines(serverLogPath, StandardCharsets.UTF_8);
        List<String> clientLines = Files.readAllLines(clientLogPath, StandardCharsets.UTF_8);
        int serverPatchHits = countLinesContaining(
                serverLines,
                "decision=publish_patch",
                "reason=" + SERVER_PATCH_REASON,
                "chunk=" + chunkMarker
        );
        int clientAckHits = countLinesContaining(
                clientLines,
                "reason=" + CLIENT_ACK_REASON,
                "chunk=" + chunkMarker
        );

        if (serverPatchHits <= 0) {
            failures.add("missing_server_patch_decision chunk=" + chunkMarker + ", reason=" + SERVER_PATCH_REASON);
        }
        if (clientAckHits <= 0) {
            failures.add("missing_client_ack chunk=" + chunkMarker + ", reason=" + CLIENT_ACK_REASON);
        }

        for (String forbiddenMarker : FORBIDDEN_REASON_MARKERS) {
            int hitCount = countLinesContaining(serverLines, "chunk=" + chunkMarker, forbiddenMarker)
                    + countLinesContaining(clientLines, "chunk=" + chunkMarker, forbiddenMarker);
            if (hitCount > 0) {
                failures.add("forbidden_reason_hit chunk=" + chunkMarker + ", reason=" + forbiddenMarker + ", count=" + hitCount);
            }
        }

        if (marker.finalFullVersion() <= marker.initialFullVersion()) {
            failures.add(
                    "marker_full_version_not_advanced initial="
                            + marker.initialFullVersion()
                            + ", final="
                            + marker.finalFullVersion()
            );
        }
        if (!marker.initialFullHash().isBlank()
                && !marker.finalFullHash().isBlank()
                && marker.initialFullHash().equals(marker.finalFullHash())) {
            failures.add("marker_full_hash_unchanged chunk=" + chunkMarker);
        }
        return failures;
    }


    private static List<String> collectComparisonFailures(List<ComparisonReport> compareReports) {
        List<String> failures = new ArrayList<>();
        for (ComparisonReport compareReport : compareReports) {
            if (compareReport.matched()) {
                continue;
            }
            failures.add("packet_stream_mismatch=" + compareReport.label());
        }
        return failures;
    }


    private static void printReport(
            RegressionMarker marker,
            List<ComparisonReport> compareReports,
            List<String> failures
    ) {
        System.out.println("=== WatchBoundaryRefreshPatchRegression ===");
        System.out.println("player=" + marker.playerName());
        System.out.println("target_chunk=" + marker.chunkMarker());
        System.out.println(
                "target_block=("
                        + marker.targetBlockX()
                        + ", "
                        + marker.targetBlockY()
                        + ", "
                        + marker.targetBlockZ()
                        + ")"
        );
        System.out.println(
                "mutation=" + marker.mutatedFrom() + " -> " + marker.mutatedTo()
                        + ", fullVersion=" + marker.initialFullVersion() + " -> " + marker.finalFullVersion()
        );

        for (ComparisonReport compareReport : compareReports) {
            if (compareReport.matched()) {
                System.out.println(compareReport.label() + ": 匹配, 共 " + compareReport.comparedLines() + " 行");
                continue;
            }

            System.out.println(compareReport.label() + ": 不匹配, 已比较 " + compareReport.comparedLines() + " 行");
            int printedCount = 0;
            for (MismatchDetail mismatch : compareReport.mismatches()) {
                printedCount++;
                System.out.println("  " + printedCount + ". 第 " + mismatch.lineNumber() + " 行: " + mismatch.message());
            }
            if (compareReport.stoppedEarly()) {
                System.out.println("  已达到前 " + MAX_MISMATCHES_TO_PRINT + " 个差异上限，后续不再继续输出。");
            }
        }

        if (failures.isEmpty()) {
            System.out.println("Result: Pass");
            return;
        }

        System.out.println("Result: Fail");
        for (String failure : failures) {
            System.out.println(failure);
        }
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
            differences.add("payload_hex: " + abbreviate(leftFrame.payloadHex()) + " != " + abbreviate(rightFrame.payloadHex()));
        }
        if (!Objects.equals(leftFrame.semanticFingerprint(), rightFrame.semanticFingerprint())) {
            differences.add(
                    "semantic_fingerprint: "
                            + leftFrame.semanticFingerprint()
                            + " != "
                            + rightFrame.semanticFingerprint()
            );
        }
        return differences.isEmpty() ? null : String.join(" | ", differences);
    }


    private static int countLinesContaining(List<String> lines, String... requiredMarkers) {
        int count = 0;
        for (String line : lines) {
            boolean allMatched = true;
            for (String requiredMarker : requiredMarkers) {
                if (requiredMarker == null || requiredMarker.isBlank()) {
                    continue;
                }
                if (line == null || !line.contains(requiredMarker)) {
                    allMatched = false;
                    break;
                }
            }
            if (allMatched) {
                count++;
            }
        }
        return count;
    }


    private static String readRequiredString(Map<String, String> values, String key) {
        String value = values.get(key);
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException("marker 缺少字段: " + key);
        }
        return value;
    }


    private static String readOptionalString(Map<String, String> values, String key) {
        String value = values.get(key);
        return value == null ? "" : value;
    }


    private static int readRequiredInt(Map<String, String> values, String key) {
        return Integer.parseInt(readRequiredString(values, key));
    }


    private static long readRequiredLong(Map<String, String> values, String key) {
        return Long.parseLong(readRequiredString(values, key));
    }


    private static String readJsonString(JsonObject jsonObject, String fieldName) {
        if (jsonObject == null || !jsonObject.has(fieldName) || jsonObject.get(fieldName).isJsonNull()) {
            throw new IllegalArgumentException("缺少字符串字段: " + fieldName);
        }
        return jsonObject.get(fieldName).getAsString();
    }


    private static int readJsonInt(JsonObject jsonObject, String fieldName) {
        if (jsonObject == null || !jsonObject.has(fieldName) || jsonObject.get(fieldName).isJsonNull()) {
            throw new IllegalArgumentException("缺少整数字段: " + fieldName);
        }
        return jsonObject.get(fieldName).getAsInt();
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


    private static void ensureFileExists(Path path) {
        if (!Files.exists(path)) {
            throw new IllegalArgumentException("找不到文件: " + path.toAbsolutePath());
        }
    }

    private record RegressionMarker(
            String playerName,
            int targetChunkX,
            int targetChunkZ,
            int targetBlockX,
            int targetBlockY,
            int targetBlockZ,
            long initialFullVersion,
            long finalFullVersion,
            String mutatedFrom,
            String mutatedTo,
            String initialFullHash,
            String finalFullHash
    ) {

        private String chunkMarker() {
            return "(" + this.targetChunkX + ", " + this.targetChunkZ + ")";
        }
    }

    private record ComparableFrame(
            String protocol,
            String packetClass,
            int packetId,
            int byteLength,
            String payloadHex,
            String semanticFingerprint
    ) {}

    private record MismatchDetail(long lineNumber, String message) {}

    private record ComparisonReport(
            String label,
            long comparedLines,
            List<MismatchDetail> mismatches,
            boolean stoppedEarly
    ) {

        private boolean matched() {
            return this.mismatches == null || this.mismatches.isEmpty();
        }
    }
}
