package com.PinkCats.bandwidthoptimizer.Old.network;

import com.PinkCats.bandwidthoptimizer.Old.network.algorithm.play.PlayPacketReplaySupport;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import net.minecraft.network.protocol.Packet;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

public final class NonWhitelistedPlayPacketReportMain {

    private static final Path DEFAULT_TRAFFIC_INPUT = Path.of("dump", "packettest-20260404-094728-6eabf199-server_to_client.jsonl");
    private static final int DEFAULT_LIMIT = 30;
    private static final Set<String> WHITELISTED_PACKET_CLASSES = buildWhitelistedPacketClasses();

    private NonWhitelistedPlayPacketReportMain() {
    }

    public static void main(String[] args) {
        Arguments arguments = Arguments.parse(args);
        List<TrafficEntry> trafficEntries = readTrafficEntries(arguments.trafficInputPath());
        if (trafficEntries.isEmpty()) {
            printUsage();
            return;
        }

        Map<String, ReasonStats> reasonsByClass = arguments.unhandledInputPath() == null
                ? Map.of()
                : readUnhandledReasons(arguments.unhandledInputPath());

        long totalPackets = trafficEntries.size();
        long totalBytes = trafficEntries.stream().mapToLong(TrafficEntry::wireBytes).sum();

        Map<String, ClassStats> nonWhitelisted = new HashMap<>();
        long nonWhitelistedPackets = 0L;
        long nonWhitelistedBytes = 0L;
        for (TrafficEntry entry : trafficEntries) {
            if (WHITELISTED_PACKET_CLASSES.contains(entry.packetClass())) {
                continue;
            }
            nonWhitelistedPackets++;
            nonWhitelistedBytes += entry.wireBytes();
            nonWhitelisted.computeIfAbsent(entry.packetClass(), ignored -> new ClassStats()).add(entry.wireBytes());
        }

        List<Map.Entry<String, ClassStats>> ranked = new ArrayList<>(nonWhitelisted.entrySet());
        ranked.sort(Comparator
                .comparingLong((Map.Entry<String, ClassStats> entry) -> entry.getValue().bytes())
                .reversed()
                .thenComparing(Map.Entry::getKey));

        System.out.println("=== Non-Whitelisted Clientbound PLAY Packet Report ===");
        System.out.println("Traffic input: " + arguments.trafficInputPath().toAbsolutePath());
        if (arguments.unhandledInputPath() != null) {
            System.out.println("Unhandled input: " + arguments.unhandledInputPath().toAbsolutePath());
        }
        System.out.println("Whitelisted classes: " + WHITELISTED_PACKET_CLASSES.size());
        System.out.println("Total packets=" + totalPackets + ", totalBytes=" + totalBytes);
        System.out.println("Non-whitelisted packets=" + nonWhitelistedPackets
                + " (" + formatPercent(nonWhitelistedPackets, totalPackets) + ")"
                + ", bytes=" + nonWhitelistedBytes
                + " (" + formatPercent(nonWhitelistedBytes, totalBytes) + ")");
        System.out.println();

        if (ranked.isEmpty()) {
            System.out.println("No non-whitelisted packet classes found in the traffic dump.");
            return;
        }

        int limit = Math.min(arguments.limit(), ranked.size());
        System.out.println("Top " + limit + " classes by bytes:");
        for (int i = 0; i < limit; i++) {
            Map.Entry<String, ClassStats> entry = ranked.get(i);
            String packetClass = entry.getKey();
            ClassStats stats = entry.getValue();
            ReasonStats reasonStats = reasonsByClass.get(packetClass);
            System.out.println(
                    "#"
                            + (i + 1)
                            + " class="
                            + packetClass
                            + ", packets="
                            + stats.packets()
                            + " ("
                            + formatPercent(stats.packets(), totalPackets)
                            + ")"
                            + ", bytes="
                            + stats.bytes()
                            + " ("
                            + formatPercent(stats.bytes(), totalBytes)
                            + ")"
                            + ", avgBytes="
                            + formatDecimal(stats.averageBytes())
                            + formatReasonSuffix(reasonStats)
            );
        }

        if (!reasonsByClass.isEmpty()) {
            long totalUnhandled = reasonsByClass.values().stream().mapToLong(ReasonStats::total).sum();
            long listenerPresent = reasonsByClass.values().stream().mapToLong(ReasonStats::listenerPresent).sum();
            long notInWhitelist = reasonsByClass.values().stream().mapToLong(ReasonStats::notInWhitelist).sum();
            long bypassChannel = reasonsByClass.values().stream().mapToLong(ReasonStats::bypassChannel).sum();
            long estimatedBypassedBytes = reasonsByClass.values().stream().mapToLong(ReasonStats::estimatedBytes).sum();
            System.out.println();
            System.out.println("Unhandled reason totals: total=" + totalUnhandled
                    + ", listener_present=" + listenerPresent
                    + ", not_in_whitelist=" + notInWhitelist
                    + ", bypass_channel=" + bypassChannel
                    + ", estimated_bypassed_bytes=" + estimatedBypassedBytes);
            printBypassedRanking(reasonsByClass, estimatedBypassedBytes, arguments.limit());
        }
    }

    private static String formatReasonSuffix(ReasonStats reasonStats) {
        if (reasonStats == null || reasonStats.total() == 0L) {
            return "";
        }
        return ", unhandled="
                + reasonStats.total()
                + " [listener_present="
                + reasonStats.listenerPresent()
                + ", not_in_whitelist="
                + reasonStats.notInWhitelist()
                + ", bypass_channel="
                + reasonStats.bypassChannel()
                + ", estimated_bytes="
                + reasonStats.estimatedBytes()
                + "]";
    }

    private static void printBypassedRanking(Map<String, ReasonStats> reasonsByClass, long totalEstimatedBypassedBytes, int limit) {
        List<Map.Entry<String, ReasonStats>> ranked = reasonsByClass.entrySet().stream()
                .filter(entry -> entry.getValue().estimatedBytes() > 0L)
                .sorted(Comparator
                        .comparingLong((Map.Entry<String, ReasonStats> entry) -> entry.getValue().estimatedBytes())
                        .reversed()
                        .thenComparing(Map.Entry::getKey))
                .toList();
        if (ranked.isEmpty()) {
            return;
        }
        System.out.println();
        System.out.println("Top bypassed classes by estimated bytes:");
        for (int index = 0; index < Math.min(limit, ranked.size()); index++) {
            Map.Entry<String, ReasonStats> entry = ranked.get(index);
            System.out.println("#"
                    + (index + 1)
                    + " class="
                    + entry.getKey()
                    + ", estimated_bytes="
                    + entry.getValue().estimatedBytes()
                    + " ("
                    + formatPercent(entry.getValue().estimatedBytes(), totalEstimatedBypassedBytes)
                    + ")"
                    + ", payloads="
                    + entry.getValue().payloadSummary());
        }
    }

    private static Set<String> buildWhitelistedPacketClasses() {
        Map<String, Boolean> classNames = new LinkedHashMap<>();
        for (Class<? extends Packet<?>> packetClass : PlayPacketReplaySupport.WHITELIST) {
            classNames.put(packetClass.getName(), Boolean.TRUE);
        }
        return classNames.keySet();
    }

    private static List<TrafficEntry> readTrafficEntries(Path inputPath) {
        if (inputPath == null || !Files.exists(inputPath)) {
            return List.of();
        }
        try {
            List<TrafficEntry> entries = new ArrayList<>();
            for (String rawLine : Files.readAllLines(inputPath, StandardCharsets.UTF_8)) {
                String line = rawLine.trim();
                if (line.isEmpty() || !line.startsWith("{")) {
                    continue;
                }
                JsonObject object = JsonParser.parseString(line).getAsJsonObject();
                if (!object.has("type") || !"packet".equals(object.get("type").getAsString())) {
                    continue;
                }
                if (!object.has("packet_class")) {
                    continue;
                }
                String packetClass = object.get("packet_class").getAsString();
                long wireBytes = resolveWireBytes(object);
                entries.add(new TrafficEntry(packetClass, wireBytes));
            }
            return entries;
        } catch (IOException exception) {
            throw new IllegalArgumentException("Failed to read traffic input file: " + inputPath, exception);
        }
    }

    private static long resolveWireBytes(JsonObject object) {
        if (object.has("bytes")) {
            return object.get("bytes").getAsLong();
        }
        if (object.has("wire_bytes")) {
            return object.get("wire_bytes").getAsLong();
        }
        if (object.has("serialized_payload_hex")) {
            return object.get("serialized_payload_hex").getAsString().length() / 2L;
        }
        if (object.has("payload_hex")) {
            return object.get("payload_hex").getAsString().length() / 2L;
        }
        return 0L;
    }

    private static Map<String, ReasonStats> readUnhandledReasons(Path inputPath) {
        if (!Files.exists(inputPath)) {
            throw new IllegalArgumentException("Unhandled input file does not exist: " + inputPath);
        }
        try {
            Map<String, ReasonStats> statsByClass = new HashMap<>();
            for (String rawLine : Files.readAllLines(inputPath, StandardCharsets.UTF_8)) {
                String line = rawLine.trim();
                if (line.isEmpty() || !line.startsWith("{")) {
                    continue;
                }
                JsonObject object = JsonParser.parseString(line).getAsJsonObject();
                if (!object.has("packet_class")) {
                    continue;
                }
                String packetClass = object.get("packet_class").getAsString();
                String reason = object.has("reason") ? object.get("reason").getAsString() : "";
                long estimatedBytes = object.has("estimated_bytes") ? object.get("estimated_bytes").getAsLong() : 0L;
                String payloadId = object.has("payload_id") ? object.get("payload_id").getAsString() : "";
                statsByClass.computeIfAbsent(packetClass, ignored -> new ReasonStats()).add(reason, estimatedBytes, payloadId);
            }
            return statsByClass;
        } catch (IOException exception) {
            throw new IllegalArgumentException("Failed to read unhandled input file: " + inputPath, exception);
        }
    }

    private static String formatPercent(long numerator, long denominator) {
        if (denominator <= 0L) {
            return "0.000%";
        }
        return formatDecimal(numerator * 100.0D / denominator) + "%";
    }

    private static String formatDecimal(double value) {
        return String.format(Locale.ROOT, "%.3f", value);
    }

    private static void printUsage() {
        System.out.println("Usage:");
        System.out.println("  ./gradlew.bat runNonWhitelistedPlayPacketReport");
        System.out.println("  ./gradlew.bat runNonWhitelistedPlayPacketReport --args=\"@C:\\path\\to\\server_to_client.jsonl\"");
        System.out.println("  ./gradlew.bat runNonWhitelistedPlayPacketReport --args=\"@C:\\path\\to\\server_to_client.jsonl --unhandled @C:\\path\\to\\unhandled-clientbound-play.jsonl --limit 50\"");
        System.out.println();
        System.out.println("Notes:");
        System.out.println("  - Packet and byte shares come from the full server_to_client traffic dump.");
        System.out.println("  - Optional --unhandled input adds listener_present/not_in_whitelist reason totals by packet class.");
    }

    private record TrafficEntry(String packetClass, long wireBytes) {
    }

    private static final class ClassStats {
        private long packets;
        private long bytes;

        private void add(long wireBytes) {
            this.packets++;
            this.bytes += wireBytes;
        }

        private long packets() {
            return this.packets;
        }

        private long bytes() {
            return this.bytes;
        }

        private double averageBytes() {
            return this.packets == 0L ? 0.0D : (double) this.bytes / this.packets;
        }
    }

    private static final class ReasonStats {
        private long total;
        private long listenerPresent;
        private long notInWhitelist;
        private long bypassChannel;
        private long estimatedBytes;
        private final Map<String, Long> estimatedBytesByPayload = new LinkedHashMap<>();

        private void add(String reason, long estimatedBytes, String payloadId) {
            this.total++;
            this.estimatedBytes += Math.max(estimatedBytes, 0L);
            if ("listener_present".equals(reason)) {
                this.listenerPresent++;
            } else if ("not_in_whitelist".equals(reason)) {
                this.notInWhitelist++;
            } else if (reason.startsWith("custom_payload_bypass_channel:")) {
                this.bypassChannel++;
            }
            if (payloadId != null && !payloadId.isBlank()) {
                this.estimatedBytesByPayload.merge(payloadId, Math.max(estimatedBytes, 0L), Long::sum);
            }
        }

        private long total() {
            return this.total;
        }

        private long listenerPresent() {
            return this.listenerPresent;
        }

        private long notInWhitelist() {
            return this.notInWhitelist;
        }

        private long bypassChannel() {
            return this.bypassChannel;
        }

        private long estimatedBytes() {
            return this.estimatedBytes;
        }

        private String payloadSummary() {
            if (this.estimatedBytesByPayload.isEmpty()) {
                return "-";
            }
            return this.estimatedBytesByPayload.entrySet().stream()
                    .sorted(Map.Entry.<String, Long>comparingByValue().reversed())
                    .limit(3)
                    .map(entry -> entry.getKey() + "=" + entry.getValue())
                    .reduce((left, right) -> left + ", " + right)
                    .orElse("-");
        }
    }

    private record Arguments(Path trafficInputPath, Path unhandledInputPath, int limit) {
        private static Arguments parse(String[] args) {
            Path trafficPath = DEFAULT_TRAFFIC_INPUT;
            Path unhandledPath = null;
            int limit = DEFAULT_LIMIT;

            for (int index = 0; index < args.length; index++) {
                String arg = args[index].trim();
                if (arg.isEmpty()) {
                    continue;
                }
                if ("--unhandled".equals(arg) && index + 1 < args.length) {
                    unhandledPath = parsePath(args[++index]);
                    continue;
                }
                if ("--limit".equals(arg) && index + 1 < args.length) {
                    limit = Math.max(1, Integer.parseInt(args[++index].trim()));
                    continue;
                }
                trafficPath = parsePath(arg);
            }

            return new Arguments(trafficPath, unhandledPath, limit);
        }

        private static Path parsePath(String raw) {
            String value = raw.trim();
            if (value.startsWith("@")) {
                value = value.substring(1).trim();
            }
            return Path.of(value);
        }
    }
}
