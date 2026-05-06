package com.PinkCats.bandwidthoptimizer.test;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

public final class RunAllChunkBranchCoverageMain {

    private static final Path DEFAULT_SERVER_LOG = Path.of("run", "runall-server-task.log");
    private static final Path DEFAULT_SERVER_SEND_JSONL = Path.of("run", "server", "send.jsonl");
    private static final String LIGHT_UPDATE = "net.minecraft.network.protocol.game.ClientboundLightUpdatePacket";
    private static final String SECTION_BLOCKS_UPDATE = "net.minecraft.network.protocol.game.ClientboundSectionBlocksUpdatePacket";
    private static final String BLOCK_ENTITY_UPDATE = "net.minecraft.network.protocol.game.ClientboundBlockEntityDataPacket";
    private static final String BLOCK_UPDATE = "net.minecraft.network.protocol.game.ClientboundBlockUpdatePacket";
    private static final List<String> REQUIRED_REASON_MARKERS = List.of("reuse_cached_full_snapshot_after_watch_boundary");
    private static final List<String> OPTIONAL_REASON_MARKERS = List.of(
            "refresh_patch_after_watch_boundary",
            "reuse_inflight_full_snapshot_before_ack",
            "reuse_acknowledged_full_snapshot",
            "light_patch_after_published_full_before_ack",
            "section_patch_after_published_full_before_ack",
            "block_entity_patch_after_published_full_before_ack",
            "block_patch_after_published_full_before_ack"
    );

    private RunAllChunkBranchCoverageMain() {
    }

    public static void main(String[] args) throws IOException {
        Map<String, Integer> reasonCounts = countReasonMarkers(DEFAULT_SERVER_LOG);
        Map<String, PacketStats> packetStats = readPacketStats(DEFAULT_SERVER_SEND_JSONL);
        List<String> failures = collectRequiredFailures(reasonCounts, packetStats);

        System.out.println("=== ChunkBranchCoverage ===");
        for (String marker : REQUIRED_REASON_MARKERS) {
            System.out.println(marker + "=" + reasonCounts.getOrDefault(marker, 0));
        }
        for (String marker : OPTIONAL_REASON_MARKERS) {
            System.out.println(marker + "=" + reasonCounts.getOrDefault(marker, 0));
        }
        printPacketStats(packetStats, LIGHT_UPDATE, "light_update");
        printPacketStats(packetStats, SECTION_BLOCKS_UPDATE, "section_blocks_update");
        printPacketStats(packetStats, BLOCK_ENTITY_UPDATE, "block_entity_update");
        printPacketStats(packetStats, BLOCK_UPDATE, "block_update");

        if (!failures.isEmpty()) {
            System.out.println("Result: Fail");
            for (String failure : failures) {
                System.out.println(failure);
            }
            System.exit(1);
        }

        System.out.println("Result: Pass");
    }

    private static Map<String, Integer> countReasonMarkers(Path logFile) throws IOException {
        String logText = Files.exists(logFile) ? Files.readString(logFile, StandardCharsets.UTF_8) : "";
        LinkedHashMap<String, Integer> counts = new LinkedHashMap<>();
        for (String marker : REQUIRED_REASON_MARKERS) {
            counts.put(marker, countOccurrences(logText, marker));
        }
        for (String marker : OPTIONAL_REASON_MARKERS) {
            counts.put(marker, countOccurrences(logText, marker));
        }
        return counts;
    }

    private static Map<String, PacketStats> readPacketStats(Path jsonlFile) throws IOException {
        LinkedHashMap<String, PacketStats> statsByPacketClass = new LinkedHashMap<>();
        statsByPacketClass.put(LIGHT_UPDATE, PacketStats.empty());
        statsByPacketClass.put(SECTION_BLOCKS_UPDATE, PacketStats.empty());
        statsByPacketClass.put(BLOCK_ENTITY_UPDATE, PacketStats.empty());
        statsByPacketClass.put(BLOCK_UPDATE, PacketStats.empty());
        if (!Files.exists(jsonlFile)) {
            return statsByPacketClass;
        }
        for (String line : Files.readAllLines(jsonlFile, StandardCharsets.UTF_8)) {
            if (line == null || line.isBlank()) {
                continue;
            }
            String packetClassName = extractStringField(line, "packet_class");
            if (!statsByPacketClass.containsKey(packetClassName)) {
                continue;
            }
            int byteLength = extractIntField(line, "byte_length");
            statsByPacketClass.put(packetClassName, statsByPacketClass.get(packetClassName).record(byteLength));
        }
        return statsByPacketClass;
    }

    private static List<String> collectRequiredFailures(Map<String, Integer> reasonCounts, Map<String, PacketStats> packetStats) {
        List<String> failures = new ArrayList<>();
        for (String marker : REQUIRED_REASON_MARKERS) {
            if (reasonCounts.getOrDefault(marker, 0) <= 0) {
                failures.add("missing_reason_hit=" + marker);
            }
        }
        requirePacket(packetStats, LIGHT_UPDATE, "missing_packet_class=light_update", failures);
        requirePacket(packetStats, SECTION_BLOCKS_UPDATE, "missing_packet_class=section_blocks_update", failures);
        requirePacket(packetStats, BLOCK_ENTITY_UPDATE, "missing_packet_class=block_entity_update", failures);
        return failures;
    }

    private static void requirePacket(Map<String, PacketStats> packetStats, String packetClassName, String failureText, List<String> failures) {
        PacketStats snapshot = packetStats.getOrDefault(packetClassName, PacketStats.empty());
        if (snapshot.count() <= 0) {
            failures.add(failureText);
        }
    }

    private static void printPacketStats(Map<String, PacketStats> packetStats, String packetClassName, String label) {
        PacketStats stats = packetStats.getOrDefault(packetClassName, PacketStats.empty());
        System.out.println(label + "_count=" + stats.count() + ", minByteLength=" + stats.minByteLength() + ", maxByteLength=" + stats.maxByteLength());
    }

    private static String extractStringField(String jsonLine, String fieldName) {
        int valueStart = findValueStart(jsonLine, fieldName);
        if (valueStart >= jsonLine.length() || jsonLine.charAt(valueStart) != '"') {
            throw new IllegalArgumentException("Field is not a string: " + fieldName);
        }
        StringBuilder builder = new StringBuilder();
        boolean escaping = false;
        for (int index = valueStart + 1; index < jsonLine.length(); index++) {
            char current = jsonLine.charAt(index);
            if (escaping) {
                builder.append(current);
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
        throw new IllegalArgumentException("Unclosed string field: " + fieldName);
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
            throw new IllegalArgumentException("Field is not an integer: " + fieldName);
        }
        return Integer.parseInt(jsonLine.substring(valueStart, valueEnd));
    }

    private static int findValueStart(String jsonLine, String fieldName) {
        String token = "\"" + fieldName + "\":";
        int keyIndex = jsonLine.indexOf(token);
        if (keyIndex < 0) {
            throw new IllegalArgumentException("Missing field: " + fieldName);
        }
        return keyIndex + token.length();
    }

    private static int countOccurrences(String text, String marker) {
        if (text == null || text.isBlank() || marker == null || marker.isBlank()) {
            return 0;
        }
        int count = 0;
        int searchIndex = 0;
        while (searchIndex >= 0) {
            searchIndex = text.indexOf(marker, searchIndex);
            if (searchIndex < 0) {
                break;
            }
            count++;
            searchIndex += marker.length();
        }
        return count;
    }

    private record PacketStats(int count, int minByteLength, int maxByteLength) {

        private static PacketStats empty() {
            return new PacketStats(0, 0, 0);
        }

        private PacketStats record(int byteLength) {
            int normalizedByteLength = Math.max(byteLength, 0);
            if (count <= 0) {
                return new PacketStats(1, normalizedByteLength, normalizedByteLength);
            }
            return new PacketStats(count + 1, Math.min(minByteLength, normalizedByteLength), Math.max(maxByteLength, normalizedByteLength));
        }
    }
}
