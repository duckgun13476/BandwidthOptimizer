package com.PinkCats.bandwidthoptimizer.Old.network;

import com.PinkCats.bandwidthoptimizer.Old.network.algorithm.BatchAlgorithm;
import com.PinkCats.bandwidthoptimizer.Old.network.algorithm.BatchAlgorithmRegistry;
import com.PinkCats.bandwidthoptimizer.Old.network.algorithm.play.ClientboundPlayPacketBatchPacket;
import com.PinkCats.bandwidthoptimizer.Old.network.algorithm.play.PlayPacketReplaySupport;
import com.PinkCats.bandwidthoptimizer.Old.network.batch.PayloadBatching;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import io.netty.buffer.Unpooled;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.network.protocol.Packet;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Base64;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

public final class ReplayMappedHeaderExperimentMain {

    private static final Path DEFAULT_INPUT = Path.of("dump", "packettest-20260404-094728-6eabf199-server_to_client.jsonl");
    private static final List<String> DEFAULT_ALGORITHMS = List.of(
            "passthrough",
            "template_dictionary",
            "block_entity_template",
            "reference_dedup_zstd",
            "sha256_dictionary_zstd",
            "template_dictionary_zstd",
            "reference_dedup_streaming_zstd",
            "sha256_dictionary_streaming_zstd",
            "template_dictionary_streaming_zstd",
            "block_entity_template_streaming_zstd"
    );
    private static final Set<String> WHITELISTED_PACKET_CLASSES = buildWhitelistedPacketClasses();
    private static final Set<String> CUSTOM_PAYLOAD_BYPASS_CHANNELS = Set.of(
            "bandwidthoptimizer:main",
            "yes_steve_model:2_6_0"
    );
    private static final byte TIGHT_MAPPED_HEADER_MARKER = (byte) 0xC0;

    private ReplayMappedHeaderExperimentMain() {
    }

    public static void main(String[] args) {
        Arguments arguments = Arguments.parse(args);
        List<ReplayRecord> replayRecords = readReplayRecords(arguments.inputPath());
        if (replayRecords.isEmpty()) {
            printUsage();
            return;
        }

        ChannelIndexTable channelIndexTable = ChannelIndexTable.from(replayRecords);
        List<List<ReplayRecord>> batches = buildBatches(replayRecords);

        long replayDomainWireBytes = replayRecords.stream().mapToLong(ReplayRecord::wireBytes).sum();
        long totalCustomPayloads = replayRecords.stream().filter(ReplayRecord::customPayload).count();
        long totalCustomPayloadWireBytes = replayRecords.stream()
                .filter(ReplayRecord::customPayload)
                .mapToLong(ReplayRecord::wireBytes)
                .sum();
        HeaderStats headerStats = summarizeHeaderStats(replayRecords, channelIndexTable);

        System.out.println("=== Replay Mapped Header Experiment ===");
        System.out.println("Input: " + arguments.inputPath().toAbsolutePath());
        System.out.println("Replay-domain packets: " + replayRecords.size());
        System.out.println("Replay-domain batches: " + batches.size());
        System.out.println("Replay-domain wire bytes: " + replayDomainWireBytes);
        System.out.println("Custom payload packets in replay domain: " + totalCustomPayloads);
        System.out.println("Custom payload wire bytes in replay domain: " + totalCustomPayloadWireBytes);
        System.out.println("Unique custom payload channels: " + channelIndexTable.channelCount());
        System.out.println("Unique namespaces: " + channelIndexTable.namespaceCount() + ", unique paths: " + channelIndexTable.pathCount());
        System.out.println("Raw custom header bytes: baseline=" + headerStats.baselineHeaderBytes()
                + ", mapped=" + headerStats.mappedHeaderBytes()
                + ", delta=" + (headerStats.baselineHeaderBytes() - headerStats.mappedHeaderBytes())
                + " (" + formatPercent(headerStats.baselineHeaderBytes() - headerStats.mappedHeaderBytes(), headerStats.baselineHeaderBytes()) + " saved)");
        System.out.println("One-time explicit channel-table bytes: " + channelIndexTable.explicitSyncBytes());
        System.out.println();
        System.out.println("algorithm | encoded baseline -> mapped | full batch baseline -> mapped | net gain");

        for (String algorithmId : arguments.algorithmIds()) {
            ExperimentResult baseline = runExperiment(batches, channelIndexTable, algorithmId, false);
            ExperimentResult mapped = runExperiment(batches, channelIndexTable, algorithmId, true);
            printAlgorithmResult(algorithmId, baseline, mapped, channelIndexTable.explicitSyncBytes());
        }

        System.out.println("=== Experiment Completed ===");
    }

    private static void printAlgorithmResult(String algorithmId, ExperimentResult baseline, ExperimentResult mapped, long explicitSyncBytes) {
        long encodedDelta = baseline.totalEncodedBytes() - mapped.totalEncodedBytes();
        long fullDelta = baseline.totalFullBatchBytes() - mapped.totalFullBatchBytes();
        long fullDeltaWithSync = fullDelta - explicitSyncBytes;
        System.out.println(algorithmId
                + " | encoded "
                + baseline.totalEncodedBytes()
                + " -> "
                + mapped.totalEncodedBytes()
                + " ("
                + signed(encodedDelta)
                + ", "
                + formatPercent(encodedDelta, baseline.totalEncodedBytes())
                + ") | full "
                + baseline.totalFullBatchBytes()
                + " -> "
                + mapped.totalFullBatchBytes()
                + " ("
                + signed(fullDelta)
                + ", "
                + formatPercent(fullDelta, baseline.totalFullBatchBytes())
                + ") | full-minus-sync="
                + signed(fullDeltaWithSync)
                + " ("
                + formatPercent(fullDeltaWithSync, baseline.totalFullBatchBytes())
                + ")");
    }

    private static ExperimentResult runExperiment(
            List<List<ReplayRecord>> batches,
            ChannelIndexTable channelIndexTable,
            String algorithmId,
            boolean useMappedHeader
    ) {
        BatchAlgorithm algorithm = BatchAlgorithmRegistry.byId(algorithmId);
        BatchAlgorithm.Session encodeSession = algorithm.createSession();
        BatchAlgorithm.Session decodeSession = algorithm.createSession();
        Map<String, Integer> syntheticProtocolIdByClass = new LinkedHashMap<>();
        long totalEncodedBytes = 0L;
        long totalFullBatchBytes = 0L;
        long totalEntryBytes = 0L;
        long totalCustomEntryBytes = 0L;
        long totalMappedEntryBytes = 0L;

        for (int batchIndex = 0; batchIndex < batches.size(); batchIndex++) {
            List<ReplayRecord> batch = batches.get(batchIndex);
            Map<String, Integer> localPacketIdByClass = new LinkedHashMap<>();
            List<byte[]> entryBytes = new ArrayList<>(batch.size());
            List<BatchAlgorithm.BatchInput> inputs = new ArrayList<>(batch.size());

            for (ReplayRecord record : batch) {
                int localId = localPacketIdByClass.computeIfAbsent(record.packetClass(), ignored -> localPacketIdByClass.size());
                byte[] payloadBody = useMappedHeader ? encodeMappedPayloadBody(record, channelIndexTable) : record.payloadBody();
                byte[] encodedEntry = encodeReplayEntry(localId, payloadBody);
                entryBytes.add(encodedEntry);
                inputs.add(new BatchAlgorithm.BatchInput(record.packetClass(), encodedEntry));
                totalEntryBytes += encodedEntry.length;
                if (record.customPayload()) {
                    totalCustomEntryBytes += encodeReplayEntry(localId, record.payloadBody()).length;
                    totalMappedEntryBytes += encodeReplayEntry(localId, encodeMappedPayloadBody(record, channelIndexTable)).length;
                }
            }

            BatchAlgorithm.EncodedBatch encodedBatch = encodeSession.encodeEntries(inputs);
            List<byte[]> decodedEntries = decodeSession.decode(encodedBatch.bytes());
            if (decodedEntries.size() != entryBytes.size()) {
                throw new IllegalStateException("Decoded entry count mismatch for algorithm " + algorithmId
                        + ": expected " + entryBytes.size() + " but got " + decodedEntries.size());
            }
            for (int i = 0; i < decodedEntries.size(); i++) {
                byte[] expected = entryBytes.get(i);
                byte[] decoded = decodedEntries.get(i);
                if (!java.util.Arrays.equals(expected, decoded)) {
                    throw new IllegalStateException("Decoded entry mismatch for algorithm " + algorithmId
                            + ", batch=" + batchIndex
                            + ", entry=" + i);
                }
            }

            int[] packetIdTable = buildSyntheticPacketIdTable(localPacketIdByClass, syntheticProtocolIdByClass);
            ClientboundPlayPacketBatchPacket fullPacket = new ClientboundPlayPacketBatchPacket(
                    1L,
                    batchIndex + 1L,
                    packetIdTable,
                    algorithmId,
                    batchIndex == 0,
                    encodedBatch.bytes(),
                    batch.size()
            );
            totalEncodedBytes += encodedBatch.bytes().length;
            totalFullBatchBytes += fullPacket.encodedSize();
        }

        return new ExperimentResult(
                algorithmId,
                useMappedHeader,
                totalEncodedBytes,
                totalFullBatchBytes,
                totalEntryBytes,
                totalCustomEntryBytes,
                totalMappedEntryBytes
        );
    }

    private static int[] buildSyntheticPacketIdTable(
            Map<String, Integer> localPacketIdByClass,
            Map<String, Integer> syntheticProtocolIdByClass
    ) {
        int[] packetIdTable = new int[localPacketIdByClass.size()];
        for (Map.Entry<String, Integer> entry : localPacketIdByClass.entrySet()) {
            int syntheticProtocolId = syntheticProtocolIdByClass.computeIfAbsent(entry.getKey(), ignored -> syntheticProtocolIdByClass.size() + 1);
            packetIdTable[entry.getValue()] = syntheticProtocolId;
        }
        return packetIdTable;
    }

    private static byte[] encodeReplayEntry(int localId, byte[] payloadBody) {
        FriendlyByteBuf buffer = new FriendlyByteBuf(Unpooled.buffer());
        try {
            buffer.writeVarInt(localId);
            buffer.writeBytes(payloadBody);
            byte[] bytes = new byte[buffer.readableBytes()];
            buffer.getBytes(0, bytes);
            return bytes;
        } finally {
            buffer.release();
        }
    }

    private static byte[] encodeMappedPayloadBody(ReplayRecord record, ChannelIndexTable channelIndexTable) {
        if (!record.customPayload()) {
            return record.payloadBody();
        }
        ChannelIds channelIds = channelIndexTable.lookup(record.channelId());
        if (channelIds == null || !channelIds.tight()) {
            return record.payloadBody();
        }

        FriendlyByteBuf input = new FriendlyByteBuf(Unpooled.wrappedBuffer(record.payloadBody()));
        FriendlyByteBuf output = new FriendlyByteBuf(Unpooled.buffer(record.payloadBody().length));
        try {
            String decodedChannel = input.readUtf();
            if (!record.channelId().equals(decodedChannel)) {
                throw new IllegalStateException("Custom payload channel mismatch: packet=" + record.channelId() + ", decoded=" + decodedChannel);
            }
            output.writeByte(TIGHT_MAPPED_HEADER_MARKER);
            output.writeByte(channelIds.namespaceId());
            output.writeByte(channelIds.pathId());
            output.writeBytes(input);
            byte[] bytes = new byte[output.readableBytes()];
            output.getBytes(0, bytes);
            return bytes;
        } finally {
            input.release();
            output.release();
        }
    }

    private static HeaderStats summarizeHeaderStats(List<ReplayRecord> records, ChannelIndexTable channelIndexTable) {
        long baselineHeaderBytes = 0L;
        long mappedHeaderBytes = 0L;
        for (ReplayRecord record : records) {
            if (!record.customPayload()) {
                continue;
            }
            int baselineBytes = resourceLocationWireBytes(record.channelId());
            baselineHeaderBytes += baselineBytes;
            ChannelIds channelIds = channelIndexTable.lookup(record.channelId());
            mappedHeaderBytes += channelIds != null && channelIds.tight() ? 3L : baselineBytes;
        }
        return new HeaderStats(baselineHeaderBytes, mappedHeaderBytes);
    }

    private static int resourceLocationWireBytes(String channelId) {
        FriendlyByteBuf buffer = new FriendlyByteBuf(Unpooled.buffer());
        try {
            buffer.writeUtf(channelId);
            return buffer.readableBytes();
        } finally {
            buffer.release();
        }
    }

    private static List<List<ReplayRecord>> buildBatches(List<ReplayRecord> records) {
        List<List<ReplayRecord>> batches = new ArrayList<>();
        List<ReplayRecord> current = new ArrayList<>();
        long batchStartTimestamp = Long.MIN_VALUE;
        for (ReplayRecord record : records) {
            if (current.isEmpty()) {
                current.add(record);
                batchStartTimestamp = record.capturedAtMs();
                continue;
            }
            if (!PayloadBatching.withinWindow(batchStartTimestamp, record.capturedAtMs())) {
                batches.add(List.copyOf(current));
                current = new ArrayList<>();
                current.add(record);
                batchStartTimestamp = record.capturedAtMs();
                continue;
            }
            current.add(record);
        }
        if (!current.isEmpty()) {
            batches.add(List.copyOf(current));
        }
        return batches;
    }

    private static List<ReplayRecord> readReplayRecords(Path inputPath) {
        if (!Files.exists(inputPath)) {
            throw new IllegalArgumentException("Input does not exist: " + inputPath);
        }
        try {
            List<ReplayRecord> records = new ArrayList<>();
            for (String rawLine : Files.readAllLines(inputPath, StandardCharsets.UTF_8)) {
                String line = rawLine.trim();
                if (line.isEmpty() || !line.startsWith("{")) {
                    continue;
                }
                JsonObject object = JsonParser.parseString(line).getAsJsonObject();
                if (!object.has("type") || !"packet".equals(object.get("type").getAsString())) {
                    continue;
                }
                if (!"OUT".equals(readOptionalString(object, "direction", ""))) {
                    continue;
                }
                String packetClass = readOptionalString(object, "packet_class", "");
                if (!WHITELISTED_PACKET_CLASSES.contains(packetClass)) {
                    continue;
                }

                String packetName = readOptionalString(object, "packet", packetClass);
                String channelId = extractCustomPayloadChannel(packetName);
                if (channelId != null && CUSTOM_PAYLOAD_BYPASS_CHANNELS.contains(channelId)) {
                    continue;
                }

                byte[] payloadBody = decodePayloadBytes(object);
                if (payloadBody.length == 0) {
                    continue;
                }
                records.add(new ReplayRecord(
                        readOptionalLong(object, "captured_at_ms", -1L),
                        packetClass,
                        packetName,
                        readOptionalLong(object, "bytes", payloadBody.length),
                        payloadBody,
                        channelId
                ));
            }
            records.sort(Comparator.comparingLong(ReplayRecord::capturedAtMs));
            return records;
        } catch (IOException exception) {
            throw new IllegalArgumentException("Failed to read replay records: " + inputPath, exception);
        }
    }

    private static byte[] decodePayloadBytes(JsonObject object) {
        String payloadBase64 = readOptionalString(object, "serialized_payload_base64", "");
        if (payloadBase64.isEmpty()) {
            payloadBase64 = readOptionalString(object, "payload_base64", "");
        }
        return payloadBase64.isEmpty() ? new byte[0] : Base64.getDecoder().decode(payloadBase64);
    }

    private static String extractCustomPayloadChannel(String packetName) {
        int start = packetName.indexOf('[');
        int end = packetName.indexOf(']');
        if (start < 0 || end <= start + 1) {
            return null;
        }
        return packetName.substring(start + 1, end);
    }

    private static Set<String> buildWhitelistedPacketClasses() {
        Set<String> classes = new LinkedHashSet<>();
        for (Class<? extends Packet<?>> packetClass : PlayPacketReplaySupport.WHITELIST) {
            classes.add(packetClass.getName());
        }
        return Set.copyOf(classes);
    }

    private static String readOptionalString(JsonObject object, String key, String fallback) {
        return object.has(key) && !object.get(key).isJsonNull() ? object.get(key).getAsString() : fallback;
    }

    private static long readOptionalLong(JsonObject object, String key, long fallback) {
        return object.has(key) && !object.get(key).isJsonNull() ? object.get(key).getAsLong() : fallback;
    }

    private static String formatPercent(long numerator, long denominator) {
        if (denominator == 0L) {
            return "0.000%";
        }
        return String.format(Locale.ROOT, "%.3f%%", numerator * 100.0D / denominator);
    }

    private static String signed(long value) {
        return value > 0L ? "+" + value : Long.toString(value);
    }

    private static void printUsage() {
        System.out.println("Usage:");
        System.out.println("  ./gradlew.bat runReplayMappedHeaderExperiment");
        System.out.println("  ./gradlew.bat runReplayMappedHeaderExperiment --args=\"@C:\\path\\to\\server_to_client.jsonl\"");
        System.out.println("  ./gradlew.bat runReplayMappedHeaderExperiment --args=\"--algorithms template_dictionary_streaming_zstd,template_dictionary\"");
    }

    private record ReplayRecord(
            long capturedAtMs,
            String packetClass,
            String packetName,
            long wireBytes,
            byte[] payloadBody,
            String channelId
    ) {
        private boolean customPayload() {
            return this.channelId != null;
        }
    }

    private record HeaderStats(long baselineHeaderBytes, long mappedHeaderBytes) {
    }

    private record ExperimentResult(
            String algorithmId,
            boolean mappedHeader,
            long totalEncodedBytes,
            long totalFullBatchBytes,
            long totalEntryBytes,
            long totalCustomEntryBytes,
            long totalMappedEntryBytes
    ) {
    }

    private record ChannelIds(String channelId, int namespaceId, int pathId) {
        private boolean tight() {
            return this.namespaceId >= 0 && this.namespaceId < 256 && this.pathId >= 0 && this.pathId < 256;
        }
    }

    private static final class ChannelIndexTable {
        private final Map<String, ChannelIds> byChannel;
        private final int namespaceCount;
        private final int pathCount;
        private final int explicitSyncBytes;

        private ChannelIndexTable(Map<String, ChannelIds> byChannel, int namespaceCount, int pathCount, int explicitSyncBytes) {
            this.byChannel = byChannel;
            this.namespaceCount = namespaceCount;
            this.pathCount = pathCount;
            this.explicitSyncBytes = explicitSyncBytes;
        }

        private static ChannelIndexTable from(List<ReplayRecord> records) {
            Map<String, Integer> namespaceIds = new LinkedHashMap<>();
            Map<String, Integer> pathIds = new LinkedHashMap<>();
            Map<String, ChannelIds> byChannel = new LinkedHashMap<>();
            int explicitSyncBytes = 0;

            for (ReplayRecord record : records) {
                if (!record.customPayload() || byChannel.containsKey(record.channelId())) {
                    continue;
                }
                ResourceLocation id = ResourceLocation.tryParse(record.channelId());
                if (id == null) {
                    continue;
                }
                int namespaceId = namespaceIds.computeIfAbsent(id.getNamespace(), ignored -> namespaceIds.size());
                int pathId = pathIds.computeIfAbsent(id.getPath(), ignored -> pathIds.size());
                byChannel.put(record.channelId(), new ChannelIds(record.channelId(), namespaceId, pathId));
                explicitSyncBytes += resourceLocationWireBytes(record.channelId());
            }

            return new ChannelIndexTable(Map.copyOf(byChannel), namespaceIds.size(), pathIds.size(), explicitSyncBytes);
        }

        private ChannelIds lookup(String channelId) {
            return this.byChannel.get(channelId);
        }

        private int channelCount() {
            return this.byChannel.size();
        }

        private int namespaceCount() {
            return this.namespaceCount;
        }

        private int pathCount() {
            return this.pathCount;
        }

        private int explicitSyncBytes() {
            return this.explicitSyncBytes;
        }
    }

    private record Arguments(Path inputPath, List<String> algorithmIds) {
        private static Arguments parse(String[] args) {
            Path inputPath = DEFAULT_INPUT;
            List<String> algorithmIds = DEFAULT_ALGORITHMS;
            for (int i = 0; i < args.length; i++) {
                String arg = args[i];
                if (arg == null || arg.isBlank()) {
                    continue;
                }
                if (arg.startsWith("@")) {
                    inputPath = Path.of(arg.substring(1).trim());
                    continue;
                }
                if ("--algorithms".equals(arg) && i + 1 < args.length) {
                    algorithmIds = parseAlgorithms(args[++i]);
                    continue;
                }
                if (arg.startsWith("--algorithms=")) {
                    algorithmIds = parseAlgorithms(arg.substring("--algorithms=".length()));
                }
            }
            return new Arguments(inputPath, algorithmIds);
        }

        private static List<String> parseAlgorithms(String csv) {
            List<String> ids = new ArrayList<>();
            for (String token : csv.split(",")) {
                String trimmed = token.trim();
                if (!trimmed.isEmpty()) {
                    ids.add(trimmed);
                }
            }
            return ids.isEmpty() ? DEFAULT_ALGORITHMS : List.copyOf(ids);
        }
    }
}
