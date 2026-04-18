package com.PinkCats.bandwidthoptimizer.Old.network;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import java.io.BufferedWriter;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

public final class TemplateHitMiningMain {

    private static final Gson GSON = new GsonBuilder().disableHtmlEscaping().create();
    private static final Path DEFAULT_INPUT = Path.of("dump", "replay_output", "packettest-20260404-094728-6eabf199-server_to_client-channel-batches-plain.jsonl");
    private static final Path DEFAULT_OUTPUT = Path.of("dump", "replay_output", "template-hit-summary.jsonl");

    private TemplateHitMiningMain() {
    }

    public static void main(String[] args) throws IOException {
        Path input = DEFAULT_INPUT;
        Path output = DEFAULT_OUTPUT;
        for (String arg : args) {
            if (arg.startsWith("--input=")) {
                input = Path.of(arg.substring("--input=".length()));
                continue;
            }
            if (arg.startsWith("--output=")) {
                output = Path.of(arg.substring("--output=".length()));
            }
        }

        List<JsonObject> rows = mine(input);
        Files.createDirectories(output.getParent());
        try (BufferedWriter writer = Files.newBufferedWriter(output, StandardCharsets.UTF_8)) {
            for (JsonObject row : rows) {
                writer.write(GSON.toJson(row));
                writer.newLine();
            }
        }

        System.out.println("Template summary written to: " + output);
        for (int i = 0; i < Math.min(8, rows.size()); i++) {
            JsonObject row = rows.get(i);
            System.out.println("#" + (i + 1)
                    + " hits=" + row.get("hits").getAsInt()
                    + " totalLength=" + row.get("total_length").getAsInt()
                    + " literalBytes=" + row.get("literal_bytes").getAsInt()
                    + " avgVariableBytes=" + row.get("avg_variable_bytes").getAsDouble());
        }
    }

    private static List<JsonObject> mine(Path input) throws IOException {
        Map<String, TemplateStat> stats = new HashMap<>();
        Files.readAllLines(input, StandardCharsets.UTF_8).forEach(line -> {
            if (line.isBlank()) {
                return;
            }
            JsonObject batch = JsonParser.parseString(line).getAsJsonObject();
            Map<Integer, String> additionById = new HashMap<>();
            JsonArray additions = batch.getAsJsonArray("dictionary_additions");
            if (additions != null) {
                for (JsonElement element : additions) {
                    JsonObject addition = element.getAsJsonObject();
                    if (!"template".equals(addition.get("mapping_kind").getAsString())) {
                        continue;
                    }
                    String signature = signatureOf(addition);
                    additionById.put(addition.get("mapping_id").getAsInt(), signature);
                    stats.computeIfAbsent(signature, ignored -> new TemplateStat(addition)).additions++;
                }
            }
            JsonArray wireEntries = batch.getAsJsonArray("wire_entries");
            if (wireEntries == null) {
                return;
            }
            for (JsonElement element : wireEntries) {
                JsonObject wireEntry = element.getAsJsonObject();
                if (!"template_reference".equals(wireEntry.get("entry_kind").getAsString())) {
                    continue;
                }
                String signature = additionById.get(wireEntry.get("mapping_id").getAsInt());
                if (signature == null) {
                    continue;
                }
                TemplateStat stat = stats.get(signature);
                stat.hits++;
                JsonArray variableSegments = wireEntry.getAsJsonArray("variable_segments");
                if (variableSegments == null) {
                    continue;
                }
                for (JsonElement segmentElement : variableSegments) {
                    stat.variableBytes += segmentElement.getAsJsonObject().get("length").getAsInt();
                }
            }
        });

        List<JsonObject> rows = new ArrayList<>();
        for (Map.Entry<String, TemplateStat> entry : stats.entrySet()) {
            TemplateStat stat = entry.getValue();
            JsonObject row = new JsonObject();
            row.addProperty("signature", entry.getKey());
            row.addProperty("total_length", stat.totalLength);
            row.addProperty("additions", stat.additions);
            row.addProperty("hits", stat.hits);
            row.addProperty("literal_bytes", stat.literalBytes);
            row.addProperty("avg_variable_bytes", stat.hits == 0 ? 0.0 : (double) stat.variableBytes / stat.hits);
            row.add("segments", stat.segments.deepCopy());
            rows.add(row);
        }
        rows.sort(Comparator
                .comparingInt((JsonObject row) -> row.get("hits").getAsInt()).reversed()
                .thenComparing(Comparator.comparingInt((JsonObject row) -> row.get("literal_bytes").getAsInt()).reversed())
                .thenComparingInt(row -> row.get("total_length").getAsInt()));
        return rows;
    }

    private static String signatureOf(JsonObject addition) {
        StringBuilder signature = new StringBuilder().append(addition.get("total_length").getAsInt());
        JsonArray segments = addition.getAsJsonArray("segments");
        for (JsonElement segmentElement : segments) {
            JsonObject segment = segmentElement.getAsJsonObject();
            signature.append('|');
            if (segment.get("variable").getAsBoolean()) {
                signature.append('V').append(segment.get("length").getAsInt());
            } else {
                signature.append('L')
                        .append(segment.get("length").getAsInt())
                        .append(':')
                        .append(segment.get("literal_hex").getAsString());
            }
        }
        return signature.toString();
    }

    private static final class TemplateStat {
        private final int totalLength;
        private final int literalBytes;
        private final JsonArray segments;
        private int additions;
        private int hits;
        private int variableBytes;

        private TemplateStat(JsonObject addition) {
            this.totalLength = addition.get("total_length").getAsInt();
            this.segments = addition.getAsJsonArray("segments").deepCopy();
            int literalBytes = 0;
            for (JsonElement segmentElement : this.segments) {
                JsonObject segment = segmentElement.getAsJsonObject();
                if (!segment.get("variable").getAsBoolean()) {
                    literalBytes += segment.get("length").getAsInt();
                }
            }
            this.literalBytes = literalBytes;
        }
    }
}
