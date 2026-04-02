package com.PinkCats.bandwidthoptimizer.experient.network;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Base64;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

public final class PacketClassSimilarityMain {

    private static final Path DEFAULT_INPUT_PATH = Path.of("dump", "packettest-20260404-094728-6eabf199-server_to_client.jsonl");

    private PacketClassSimilarityMain() {
    }

    public static void main(String[] args) {
        Path inputPath = resolveInputPath(args);
        List<Entry> entries = readEntries(inputPath);
        if (entries.isEmpty()) {
            System.out.println("Usage:");
            System.out.println("  ./gradlew.bat runPacketClassSimilarity");
            System.out.println("  ./gradlew.bat runPacketClassSimilarity --args=\"@C:\\path\\to\\dump.jsonl\"");
            return;
        }

        Map<String, List<Entry>> byClass = new HashMap<>();
        for (Entry entry : entries) {
            byClass.computeIfAbsent(entry.packetClass(), ignored -> new ArrayList<>()).add(entry);
        }

        String topClass = byClass.entrySet().stream()
                .max(Comparator.comparingLong(entry -> entry.getValue().stream().mapToLong(Entry::wireBytes).sum()))
                .map(Map.Entry::getKey)
                .orElse(null);
        if (topClass == null) {
            return;
        }

        Profile baseline = profile(byClass.get(topClass));
        List<Result> results = new ArrayList<>();
        for (Map.Entry<String, List<Entry>> entry : byClass.entrySet()) {
            if (entry.getKey().equals(topClass)) {
                continue;
            }
            Profile candidate = profile(entry.getValue());
            results.add(new Result(entry.getKey(), scoreSimilarity(baseline, candidate), candidate));
        }
        results.sort(Comparator.comparingDouble(Result::distance));

        System.out.println("=== Packet Class Similarity ===");
        System.out.println("Baseline class: " + topClass);
        System.out.println("Baseline avgBytes=" + format(baseline.averageBytes())
                + " dominantPrefixRatio=" + format(baseline.dominantPrefixRatio())
                + " dominantLengthRatio=" + format(baseline.dominantLengthRatio())
                + " avgStability16=" + format(baseline.averageStability16()));
        System.out.println("Nearest classes:");
        int limit = Math.min(5, results.size());
        for (int i = 0; i < limit; i++) {
            Result result = results.get(i);
            Profile profile = result.profile();
            System.out.println(
                    "  #"
                            + (i + 1)
                            + " class="
                            + result.packetClass()
                            + " distance="
                            + format(result.distance())
                            + " avgBytes="
                            + format(profile.averageBytes())
                            + " dominantPrefixRatio="
                            + format(profile.dominantPrefixRatio())
                            + " dominantLengthRatio="
                            + format(profile.dominantLengthRatio())
                            + " avgStability16="
                            + format(profile.averageStability16())
            );
        }
        System.out.println("=== Similarity Completed ===");
    }

    private static Profile profile(List<Entry> entries) {
        double averageBytes = entries.stream().mapToInt(entry -> entry.payload().length).average().orElse(0.0D);

        Map<String, Integer> prefixCounts = new HashMap<>();
        Map<Integer, Integer> lengthCounts = new HashMap<>();
        double stabilitySum = 0.0D;
        int stabilityCount = 0;

        for (Entry entry : entries) {
            byte[] payload = entry.payload();
            String prefix = payload.length >= 2
                    ? String.format(Locale.ROOT, "%02X%02X", payload[0] & 0xFF, payload[1] & 0xFF)
                    : payload.length == 1
                    ? String.format(Locale.ROOT, "%02X__", payload[0] & 0xFF)
                    : "__";
            prefixCounts.merge(prefix, 1, Integer::sum);
            lengthCounts.merge(payload.length, 1, Integer::sum);
        }

        int dominantPrefix = prefixCounts.values().stream().mapToInt(Integer::intValue).max().orElse(0);
        int dominantLength = lengthCounts.values().stream().mapToInt(Integer::intValue).max().orElse(0);

        int window = Math.min(16, entries.stream().mapToInt(entry -> entry.payload().length).max().orElse(0));
        for (int offset = 0; offset < window; offset++) {
            Map<Integer, Integer> byteCounts = new HashMap<>();
            int samples = 0;
            for (Entry entry : entries) {
                if (offset >= entry.payload().length) {
                    continue;
                }
                byteCounts.merge(entry.payload()[offset] & 0xFF, 1, Integer::sum);
                samples++;
            }
            if (samples == 0) {
                continue;
            }
            int dominant = byteCounts.values().stream().mapToInt(Integer::intValue).max().orElse(0);
            stabilitySum += dominant * 100.0D / samples;
            stabilityCount++;
        }

        return new Profile(
                averageBytes,
                entries.isEmpty() ? 0.0D : dominantPrefix * 100.0D / entries.size(),
                entries.isEmpty() ? 0.0D : dominantLength * 100.0D / entries.size(),
                stabilityCount == 0 ? 0.0D : stabilitySum / stabilityCount
        );
    }

    private static double scoreSimilarity(Profile baseline, Profile candidate) {
        return Math.abs(baseline.averageBytes() - candidate.averageBytes())
                + Math.abs(baseline.dominantPrefixRatio() - candidate.dominantPrefixRatio()) * 4.0D
                + Math.abs(baseline.dominantLengthRatio() - candidate.dominantLengthRatio()) * 3.0D
                + Math.abs(baseline.averageStability16() - candidate.averageStability16()) * 2.0D;
    }

    private static List<Entry> readEntries(Path inputPath) {
        if (inputPath == null || !Files.exists(inputPath)) {
            return List.of();
        }
        try {
            List<Entry> entries = new ArrayList<>();
            for (String rawLine : Files.readAllLines(inputPath, StandardCharsets.UTF_8)) {
                String line = rawLine.trim();
                if (line.isEmpty()) {
                    continue;
                }
                JsonObject object = JsonParser.parseString(line).getAsJsonObject();
                if (!object.has("type") || !"packet".equals(object.get("type").getAsString())) {
                    continue;
                }
                byte[] payload = object.has("serialized_payload_base64")
                        ? Base64.getDecoder().decode(object.get("serialized_payload_base64").getAsString())
                        : Base64.getDecoder().decode(object.get("payload_base64").getAsString());
                entries.add(new Entry(
                        object.get("packet_class").getAsString(),
                        object.has("wire_bytes") ? object.get("wire_bytes").getAsInt() : payload.length,
                        payload
                ));
            }
            return entries;
        } catch (IOException exception) {
            throw new IllegalArgumentException("Failed to read dump input file: " + inputPath, exception);
        }
    }

    private static Path resolveInputPath(String[] args) {
        if (args.length == 0) {
            return DEFAULT_INPUT_PATH;
        }
        String joined = String.join(" ", args).trim();
        if (joined.isEmpty()) {
            return DEFAULT_INPUT_PATH;
        }
        if (joined.startsWith("@")) {
            return Path.of(joined.substring(1).trim());
        }
        return Path.of(joined);
    }

    private static String format(double value) {
        return String.format(Locale.ROOT, "%.3f", value);
    }

    private record Entry(String packetClass, int wireBytes, byte[] payload) {
    }

    private record Profile(
            double averageBytes,
            double dominantPrefixRatio,
            double dominantLengthRatio,
            double averageStability16
    ) {
    }

    private record Result(String packetClass, double distance, Profile profile) {
    }
}
