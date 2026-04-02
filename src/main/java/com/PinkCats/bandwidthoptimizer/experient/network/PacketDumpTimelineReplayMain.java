package com.PinkCats.bandwidthoptimizer.experient.network;

import com.PinkCats.bandwidthoptimizer.Config;
import com.PinkCats.bandwidthoptimizer.network.payload.PayloadInspectionSupport;
import com.PinkCats.bandwidthoptimizer.network.algorithm.BatchAlgorithm;
import com.PinkCats.bandwidthoptimizer.network.algorithm.BatchAlgorithmRegistry;
import com.PinkCats.bandwidthoptimizer.network.algorithm.template.TemplateDictionaryBatchAlgorithm;
import com.PinkCats.bandwidthoptimizer.network.batch.PayloadBatching;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import io.netty.buffer.Unpooled;
import net.minecraft.network.FriendlyByteBuf;

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
import java.util.Set;

public final class PacketDumpTimelineReplayMain {

    private static final Path DEFAULT_INPUT_PATH = Path.of("dump", "packettest-20260404-094728-6eabf199-server_to_client.jsonl");
    private static final Path OUTPUT_DIR = Path.of("dump", "replay_output");
    private static final Set<String> TEST_ALGORITHM_IDS = Set.of(
            "template_dictionary",
            "template_dictionary_streaming_zstd",
            "block_entity_template",
            "block_entity_template_streaming_zstd"
    );
    private static final PayloadBatching.Session ENCODE_SESSION = new PayloadBatching.Session();
    private static final PayloadBatching.Session DECODE_SESSION = new PayloadBatching.Session();

    private PacketDumpTimelineReplayMain() {
    }

    public static void main(String[] args) {
        long totalStartNanos = System.nanoTime();
        configureReplayOverrides(args);
        ENCODE_SESSION.resetAll();
        DECODE_SESSION.resetAll();

        long readStartNanos = System.nanoTime();
        List<PacketDumpReplayMain.DumpInput> dumpInputs = readDumpInputs(args);
        long readDumpNanos = System.nanoTime() - readStartNanos;
        if (dumpInputs.isEmpty()) {
            System.out.println("Usage:");
            System.out.println("  ./gradlew.bat runPacketDumpTimelineReplay");
            System.out.println("  ./gradlew.bat runPacketDumpTimelineReplay --args=\"@C:\\path\\to\\dump.jsonl\"");
            System.out.println("  ./gradlew.bat runPacketDumpTimelineReplay --args=\"--algorithm=template_dictionary\"");
            System.out.println("Allowed algorithms: " + String.join(", ", TEST_ALGORITHM_IDS));
            return;
        }

        long originalStartNanos = System.nanoTime();
        List<RawPacketRecord> originalRecords = buildOriginalRecords(dumpInputs);
        long buildOriginalNanos = System.nanoTime() - originalStartNanos;
        if (originalRecords.isEmpty()) {
            System.out.println("No packet records with payloads were found.");
            return;
        }

        Path inputPath = resolveInputPath(args);
        TimedValue<List<RawBatchInfo>> batchedResult = buildBatches(originalRecords);
        List<RawBatchInfo> batches = batchedResult.value();
        TimedValue<List<ReplayRecord>> replayResult = replayBatches(batches);
        List<ReplayRecord> replayRecords = replayResult.value();
        ComparisonSummary comparisonSummary = compare(originalRecords, replayRecords);
        String algorithmId = batches.isEmpty() ? "<none>" : batches.get(0).algorithmId();
        long totalOriginalBytes = originalRecords.stream().mapToLong(RawPacketRecord::wireBytes).sum();
        long totalChannelBytes = batches.stream().mapToLong(RawBatchInfo::estimatedWireBytes).sum();
        long totalReferenceEntries = batches.stream()
                .flatMap(batch -> batch.entryInfos().stream())
                .filter(BatchAlgorithm.EntryInfo::reference)
                .count();
        long totalLiteralEntries = originalRecords.size() - totalReferenceEntries;
        long totalReferenceSourceBytes = batches.stream()
                .flatMap(batch -> batch.entryInfos().stream())
                .filter(BatchAlgorithm.EntryInfo::reference)
                .mapToLong(BatchAlgorithm.EntryInfo::referencedWireBytes)
                .sum();
        long totalReferenceEncodedBytes = batches.stream()
                .flatMap(batch -> batch.entryInfos().stream())
                .filter(BatchAlgorithm.EntryInfo::reference)
                .mapToLong(BatchAlgorithm.EntryInfo::encodedBytes)
                .sum();
        long totalDedupSavedBytes = totalReferenceSourceBytes - totalReferenceEncodedBytes;
        long totalAddedMappings = batches.stream().mapToLong(RawBatchInfo::addedMappings).sum();
        long totalRemovedMappings = batches.stream().mapToLong(RawBatchInfo::removedMappings).sum();
        long singleUseMappedEntries = countSingleUseMappedEntries(batches);
        ParseSummary originalParseSummary = summarizeParses(originalRecords);
        ParseSummary replayParseSummary = summarizeParses(
                replayRecords.stream().map(ReplayRecord::packet).toList()
        );

        long writeStartNanos = System.nanoTime();
        writeOutputs(
                inputPath,
                toOriginalLines(originalRecords),
                toBatchLines(batches),
                buildBatchOverviewLines(originalRecords, batches),
                toPlainBatchJsonLines(batches),
                toOptimizedPayloadDecodedLines(batches),
                toSingleUseMappedEntryLines(batches),
                toReplayLines(replayRecords),
                toIncompleteParseLines(originalRecords),
                toTopPacketClassLines(originalRecords),
                buildTopPacketClassSummaryLines(originalRecords, totalOriginalBytes)
        );
        long writeOutputNanos = System.nanoTime() - writeStartNanos;
        long totalElapsedNanos = System.nanoTime() - totalStartNanos;

        System.out.println("=== Packet Dump Timeline Replay ===");
        System.out.println("Original packet count: " + originalRecords.size());
        System.out.println("Batch count: " + batches.size());
        System.out.println("Batch algorithm: " + algorithmId);
        System.out.println("Replayed packet count: " + replayRecords.size());
        System.out.println("Equal packets: " + comparisonSummary.equalPackets() + "/" + originalRecords.size());
        System.out.println("All equal: " + comparisonSummary.allEqual());
        System.out.println("Original total bytes: " + totalOriginalBytes);
        double batchedPercent = totalOriginalBytes == 0L ? 0.0D : ((double) totalChannelBytes / (double) totalOriginalBytes) * 100.0D;
        double savedPercent = 100.0D - batchedPercent;
        System.out.println("Batched total bytes: " + totalChannelBytes + "  " + formatMillis(batchedPercent) + " % remaining");
        System.out.println("Saved percent: " + formatMillis(savedPercent) + " %");
        System.out.println("Byte delta: " + (totalChannelBytes - totalOriginalBytes));
        System.out.println("Dedup literal entries: " + totalLiteralEntries);
        System.out.println("Dedup reference entries: " + totalReferenceEntries);
        System.out.println("Single-use mapped entries: " + singleUseMappedEntries);
        System.out.println("Dedup referenced source bytes: " + totalReferenceSourceBytes);
        System.out.println("Dedup reference encoded bytes: " + totalReferenceEncodedBytes);
        System.out.println("Dedup saved bytes inside batches: " + totalDedupSavedBytes);
        System.out.println("Dictionary added mappings: " + totalAddedMappings);
        System.out.println("Dictionary removed mappings: " + totalRemovedMappings);
        System.out.println("Original parse matched: " + originalParseSummary.matchedCount() + "/" + originalParseSummary.totalCount());
        System.out.println("Original parse complete: " + originalParseSummary.completeCount() + "/" + originalParseSummary.totalCount());
        System.out.println("Original parse incomplete: " + originalParseSummary.incompleteCount());
        System.out.println("Replayed parse matched: " + replayParseSummary.matchedCount() + "/" + replayParseSummary.totalCount());
        System.out.println("Replayed parse complete: " + replayParseSummary.completeCount() + "/" + replayParseSummary.totalCount());
        System.out.println("Replayed parse incomplete: " + replayParseSummary.incompleteCount());
        System.out.println("Read dump time: " + formatDuration(readDumpNanos));
        System.out.println("Build original records time: " + formatDuration(buildOriginalNanos));
        System.out.println("Batch encode time: " + formatDuration(batchedResult.elapsedNanos()));
        System.out.println("Replay decode time: " + formatDuration(replayResult.elapsedNanos()));
        System.out.println("Write output time: " + formatDuration(writeOutputNanos));
        System.out.println("Main total time: " + formatDuration(totalElapsedNanos));
        for (String line : buildTopPacketClassLines(originalRecords, totalOriginalBytes, 10)) {
            System.out.println(line);
        }
        for (String line : buildPacketClassDetailLines(originalRecords, totalOriginalBytes, 3, 64, 5, 5)) {
            System.out.println(line);
        }
        for (String line : buildComparisonLines(comparisonSummary)) {
            System.out.println(line);
        }
        for (String line : buildSummaryLines(originalRecords, batches, comparisonSummary, originalParseSummary, replayParseSummary)) {
            System.out.println(line);
        }
        System.out.println("Output directory: " + OUTPUT_DIR.toAbsolutePath());
        System.out.println("=== Timeline Replay Completed ===");
    }

    private static List<RawPacketRecord> buildOriginalRecords(List<PacketDumpReplayMain.DumpInput> dumpInputs) {
        List<PacketDumpReplayMain.DumpInput> sorted = new ArrayList<>(dumpInputs);
        sorted.sort(Comparator.comparingLong(PacketDumpReplayMain.DumpInput::capturedAtMs));
        long firstTimestamp = sorted.stream()
                .filter(input -> "OUT".equals(input.direction()))
                .mapToLong(PacketDumpReplayMain.DumpInput::capturedAtMs)
                .filter(value -> value >= 0L)
                .findFirst()
                .orElse(-1L);

        List<RawPacketRecord> records = new ArrayList<>();
        for (PacketDumpReplayMain.DumpInput input : sorted) {
            if (!"OUT".equals(input.direction())) {
                continue;
            }
            if (input.payloadBytes().length == 0) {
                continue;
            }
            long offsetMs = input.capturedAtMs() >= 0L && firstTimestamp >= 0L ? input.capturedAtMs() - firstTimestamp : -1L;
            records.add(new RawPacketRecord(
                    records.size(),
                    input.capturedAtMs(),
                    offsetMs,
                    input.direction(),
                    input.packetName(),
                    input.packetClass(),
                    input.wireBytes(),
                    input.payloadBytes()
            ));
        }
        return records;
    }

    private static TimedValue<List<RawBatchInfo>> buildBatches(List<RawPacketRecord> records) {
        long startNanos = System.nanoTime();
        List<RawBatchInfo> batches = new ArrayList<>();
        List<RawPacketRecord> current = new ArrayList<>();
        long batchStartTimestamp = Long.MIN_VALUE;

        for (RawPacketRecord record : records) {
            if (current.isEmpty()) {
                current.add(record);
                batchStartTimestamp = record.capturedAtMs();
                continue;
            }
            if (!PayloadBatching.withinWindow(batchStartTimestamp, record.capturedAtMs())) {
                batches.add(createBatch(batches.size(), current));
                current = new ArrayList<>();
                current.add(record);
                batchStartTimestamp = record.capturedAtMs();
                continue;
            }
            current.add(record);
        }

        if (!current.isEmpty()) {
            batches.add(createBatch(batches.size(), current));
        }
        return new TimedValue<>(batches, System.nanoTime() - startNanos);
    }

    private static RawBatchInfo createBatch(int batchIndex, List<RawPacketRecord> records) {
        PayloadBatching.EncodedPayloadBatch encodedBatch = PayloadBatching.encodeEntries(
                records.stream()
                        .map(record -> new BatchAlgorithm.BatchInput(record.packetClass(), record.payloadBytes()))
                        .toList(),
                ENCODE_SESSION
        );
        double replayIntervalMs = records.isEmpty() ? 0.0D : (double) PayloadBatching.WINDOW_MILLIS / (double) records.size();
        return new RawBatchInfo(
                batchIndex,
                List.copyOf(records),
                encodedBatch.bytes(),
                encodedBatch.entryInfos(),
                encodedBatch.bytes().length,
                encodedBatch.algorithmId(),
                encodedBatch.addedMappings(),
                encodedBatch.removedMappings(),
                replayIntervalMs
        );
    }

    private static TimedValue<List<ReplayRecord>> replayBatches(List<RawBatchInfo> batches) {
        long startNanos = System.nanoTime();
        List<ReplayRecord> replayRecords = new ArrayList<>();
        for (RawBatchInfo batch : batches) {
            List<byte[]> decodedPayloads = PayloadBatching.decodePayloads(
                    batch.algorithmId(),
                    batch.channelBytes(),
                    DECODE_SESSION
            );
            for (int i = 0; i < decodedPayloads.size(); i++) {
                RawPacketRecord sourceRecord = batch.records().get(i);
                RawPacketRecord record = new RawPacketRecord(
                        sourceRecord.index(),
                        -1L,
                        -1L,
                        sourceRecord.direction(),
                        sourceRecord.packetName(),
                        sourceRecord.packetClass(),
                        sourceRecord.wireBytes(),
                        decodedPayloads.get(i)
                );
                replayRecords.add(new ReplayRecord(
                        replayRecords.size(),
                        batch.batchIndex(),
                        i,
                        i * batch.replayIntervalMs(),
                        record
                ));
            }
        }
        return new TimedValue<>(replayRecords, System.nanoTime() - startNanos);
    }

    private static ComparisonSummary compare(List<RawPacketRecord> originalRecords, List<ReplayRecord> replayRecords) {
        int compareCount = Math.min(originalRecords.size(), replayRecords.size());
        int equalPackets = 0;
        List<String> lines = new ArrayList<>();
        for (int i = 0; i < compareCount; i++) {
            RawPacketRecord original = originalRecords.get(i);
            RawPacketRecord replayed = replayRecords.get(i).packet();
            boolean packetEqual = original.direction().equals(replayed.direction())
                    && original.packetName().equals(replayed.packetName())
                    && original.packetClass().equals(replayed.packetClass())
                    && java.util.Arrays.equals(original.payloadBytes(), replayed.payloadBytes());
            if (packetEqual) {
                equalPackets++;
            } else {
                lines.add(buildMismatchLine(i, original, replayed));
            }
        }
        if (originalRecords.size() != replayRecords.size()) {
            lines.add("count_mismatch original=" + originalRecords.size() + " replayed=" + replayRecords.size());
        }
        return new ComparisonSummary(equalPackets, lines, equalPackets == originalRecords.size() && originalRecords.size() == replayRecords.size());
    }

    private static String buildMismatchLine(int index, RawPacketRecord original, RawPacketRecord replayed) {
        return "index=" + index
                + " originalPacket=" + original.packetName()
                + " replayPacket=" + replayed.packetName()
                + " originalDirection=" + original.direction()
                + " replayDirection=" + replayed.direction()
                + " originalBytes=" + original.payloadBytes().length
                + " replayBytes=" + replayed.payloadBytes().length;
    }

    private static List<String> toOriginalLines(List<RawPacketRecord> records) {
        List<String> lines = new ArrayList<>(records.size());
        for (RawPacketRecord record : records) {
            JsonObject object = new JsonObject();
            object.addProperty("index", record.index());
            object.addProperty("captured_at_ms", record.capturedAtMs());
            object.addProperty("offset_ms", record.offsetMs());
            object.addProperty("direction", record.direction());
            object.addProperty("packet", record.packetName());
            object.addProperty("packet_class", record.packetClass());
            object.addProperty("payload_base64", Base64.getEncoder().encodeToString(record.payloadBytes()));
            object.add("payload_analysis", PayloadInspectionSupport.inspectPayload(record.packetClass(), record.payloadBytes()));
            lines.add(object.toString());
        }
        return lines;
    }

    private static List<String> toBatchLines(List<RawBatchInfo> batches) {
        List<String> lines = new ArrayList<>();
        for (RawBatchInfo batch : batches) {
            long referenceCount = batch.entryInfos().stream().filter(BatchAlgorithm.EntryInfo::reference).count();
            lines.add("batch=" + batch.batchIndex()
                    + " packets=" + batch.records().size()
                    + " algorithm=" + batch.algorithmId()
                    + " addedMappings=" + batch.addedMappings()
                    + " removedMappings=" + batch.removedMappings()
                    + " references=" + referenceCount
                    + " replayIntervalMs=" + formatMillis(batch.replayIntervalMs())
                    + " channelBytes=" + batch.estimatedWireBytes());
            lines.add("  channelPayloadBase64=" + Base64.getEncoder().encodeToString(batch.channelBytes()));
            lines.add("  entries:");
            for (int i = 0; i < batch.records().size(); i++) {
                RawPacketRecord record = batch.records().get(i);
                BatchAlgorithm.EntryInfo entryInfo = batch.entryInfos().get(i);
                lines.add("    - order=" + i
                        + " index=" + record.index()
                        + " offsetMs=" + record.offsetMs()
                        + " capturedAtMs=" + record.capturedAtMs()
                        + " direction=" + record.direction()
                        + " packet=" + record.packetName()
                        + " bytes=" + record.payloadBytes().length
                        + (entryInfo.reference() ? " ref=" + entryInfo.referenceIndex() : " literal"));
            }
            lines.add("");
        }
        return lines;
    }

    private static List<String> buildBatchOverviewLines(List<RawPacketRecord> originalRecords, List<RawBatchInfo> batches) {
        List<String> lines = new ArrayList<>();
        lines.add("Packet count: " + originalRecords.size());
        lines.add("Channel batch count: " + batches.size());
        lines.add("Window ms: " + PayloadBatching.WINDOW_MILLIS);
        for (RawBatchInfo batch : batches) {
            RawPacketRecord first = batch.records().get(0);
            RawPacketRecord last = batch.records().get(batch.records().size() - 1);
            long referenceCount = batch.entryInfos().stream().filter(BatchAlgorithm.EntryInfo::reference).count();
            lines.add("batch=" + batch.batchIndex()
                    + " packets=" + batch.records().size()
                    + " algorithm=" + batch.algorithmId()
                    + " addedMappings=" + batch.addedMappings()
                    + " removedMappings=" + batch.removedMappings()
                    + " references=" + referenceCount
                    + " index=" + first.index() + "-" + last.index()
                    + " offsetMs=" + first.offsetMs() + "-" + last.offsetMs()
                    + " capturedAtMs=" + first.capturedAtMs() + "-" + last.capturedAtMs()
                    + " replayIntervalMs=" + formatMillis(batch.replayIntervalMs())
                    + " channelBytes=" + batch.estimatedWireBytes());
        }
        return lines;
    }

    private static List<String> toPlainBatchJsonLines(List<RawBatchInfo> batches) {
        List<String> lines = new ArrayList<>(batches.size());
        TemplateDiagnosticState templateDiagnosticState = new TemplateDiagnosticState();
        for (RawBatchInfo batch : batches) {
            JsonObject object = new JsonObject();
            object.addProperty("batch_index", batch.batchIndex());
            object.addProperty("algorithm", batch.algorithmId());
            object.addProperty("packet_count", batch.records().size());
            object.addProperty("encoded_bytes", batch.estimatedWireBytes());
            object.addProperty("added_mappings", batch.addedMappings());
            object.addProperty("removed_mappings", batch.removedMappings());
            object.addProperty("replay_interval_ms", formatMillis(batch.replayIntervalMs()));
            object.add("entries", buildEntryJsonArray(batch));

            if ("template_dictionary".equals(batch.algorithmId())) {
                populateTemplateDictionaryDetails(object, batch.channelBytes(), templateDiagnosticState);
            } else {
                object.addProperty("encoded_payload_base64", Base64.getEncoder().encodeToString(batch.channelBytes()));
            }
            lines.add(object.toString());
        }
        return lines;
    }

    private static List<String> toOptimizedPayloadDecodedLines(List<RawBatchInfo> batches) {
        List<String> lines = new ArrayList<>(batches.size());
        PayloadBatching.Session diagnosticDecodeSession = new PayloadBatching.Session();
        for (RawBatchInfo batch : batches) {
            JsonObject object = new JsonObject();
            object.addProperty("batch_index", batch.batchIndex());
            object.addProperty("algorithm", batch.algorithmId());
            object.addProperty("packet_count", batch.records().size());
            object.addProperty("encoded_payload_base64", Base64.getEncoder().encodeToString(batch.channelBytes()));

            JsonArray decodedEntries = new JsonArray();
            List<byte[]> decodedPayloads = PayloadBatching.decodePayloads(
                    batch.algorithmId(),
                    batch.channelBytes(),
                    diagnosticDecodeSession
            );
            for (int i = 0; i < decodedPayloads.size(); i++) {
                RawPacketRecord record = batch.records().get(i);
                byte[] decodedPayload = decodedPayloads.get(i);
                JsonObject decodedEntry = new JsonObject();
                decodedEntry.addProperty("order", i);
                decodedEntry.addProperty("packet", record.packetName());
                decodedEntry.addProperty("packet_class", record.packetClass());
                decodedEntry.add("payload_analysis", PayloadInspectionSupport.inspectPayload(record.packetClass(), decodedPayload));
                decodedEntries.add(decodedEntry);
            }
            object.add("decoded_entries", decodedEntries);
            lines.add(object.toString());
        }
        return lines;
    }

    private static JsonArray buildEntryJsonArray(RawBatchInfo batch) {
        JsonArray entries = new JsonArray();
        for (int i = 0; i < batch.records().size(); i++) {
            RawPacketRecord record = batch.records().get(i);
            BatchAlgorithm.EntryInfo entryInfo = batch.entryInfos().get(i);
            JsonObject entry = new JsonObject();
            entry.addProperty("order", i);
            entry.addProperty("index", record.index());
            entry.addProperty("offset_ms", record.offsetMs());
            entry.addProperty("captured_at_ms", record.capturedAtMs());
            entry.addProperty("packet", record.packetName());
            entry.addProperty("packet_class", record.packetClass());
            entry.addProperty("entry_kind", entryInfo.reference() ? "mapped" : "literal");
            entry.add("payload_analysis", PayloadInspectionSupport.inspectPayload(record.packetClass(), record.payloadBytes()));
            if (entryInfo.reference()) {
                entry.addProperty("mapping_id", entryInfo.referenceIndex());
            } else {
                entry.addProperty("payload_base64", Base64.getEncoder().encodeToString(record.payloadBytes()));
                entry.addProperty("payload_preview", PayloadInspectionSupport.toPrintable(record.payloadBytes()));
            }
            entries.add(entry);
        }
        return entries;
    }

    private static void populateTemplateDictionaryDetails(JsonObject object, byte[] channelBytes, TemplateDiagnosticState state) {
        FriendlyByteBuf buffer = new FriendlyByteBuf(Unpooled.wrappedBuffer(channelBytes));
        try {
            JsonArray additions = new JsonArray();
            int addedCount = buffer.readVarInt();
            for (int i = 0; i < addedCount; i++) {
                JsonObject added = new JsonObject();
                int kind = buffer.readVarInt();
                int mappingId = buffer.readVarInt();
                added.addProperty("mapping_id", mappingId);
                if (kind == 0) {
                    int payloadLength = buffer.readVarInt();
                    byte[] payload = new byte[payloadLength];
                    buffer.readBytes(payload);
                    added.addProperty("mapping_kind", "exact");
                    added.addProperty("payload_base64", Base64.getEncoder().encodeToString(payload));
                    added.addProperty("payload_preview", PayloadInspectionSupport.toPrintable(payload));
                    added.add("payload_analysis", PayloadInspectionSupport.inspectPayload("<template_exact>", payload));
                } else if (kind == 1) {
                    int totalLength = buffer.readVarInt();
                    int segmentCount = buffer.readVarInt();
                    JsonArray segments = new JsonArray();
                    List<Integer> variableLengths = new ArrayList<>();
                    for (int j = 0; j < segmentCount; j++) {
                        JsonObject segment = new JsonObject();
                        boolean variable = buffer.readBoolean();
                        int length = buffer.readVarInt();
                        segment.addProperty("variable", variable);
                        segment.addProperty("length", length);
                        if (variable) {
                            variableLengths.add(length);
                        } else {
                            byte[] literalBytes = new byte[length];
                            buffer.readBytes(literalBytes);
                            segment.addProperty("literal_hex", PayloadInspectionSupport.hex(literalBytes));
                            segment.addProperty("literal_preview", PayloadInspectionSupport.toPrintable(literalBytes));
                        }
                        segments.add(segment);
                    }
                    added.addProperty("mapping_kind", "template");
                    added.addProperty("total_length", totalLength);
                    added.add("segments", segments);
                    state.templatesById().put(mappingId, new TemplateWireDefinition(List.copyOf(variableLengths)));
                } else {
                    throw new IllegalArgumentException("Unknown template addition kind: " + kind);
                }
                additions.add(added);
            }
            object.add("dictionary_additions", additions);

            JsonArray removals = new JsonArray();
            int removedCount = buffer.readVarInt();
            for (int i = 0; i < removedCount; i++) {
                JsonObject removed = new JsonObject();
                int kind = buffer.readVarInt();
                int mappingId = buffer.readVarInt();
                removed.addProperty("mapping_kind", kind == 0 ? "exact" : "template");
                removed.addProperty("mapping_id", mappingId);
                if (kind == 1) {
                    state.templatesById().remove(mappingId);
                }
                removals.add(removed);
            }
            object.add("dictionary_removals", removals);

            JsonArray wireEntries = new JsonArray();
            int entryCount = buffer.readVarInt();
            for (int i = 0; i < entryCount; i++) {
                JsonObject wireEntry = new JsonObject();
                int entryType = buffer.readVarInt();
                wireEntry.addProperty("order", i);
                if (entryType == 0) {
                    int payloadLength = buffer.readVarInt();
                    byte[] payload = new byte[payloadLength];
                    buffer.readBytes(payload);
                    wireEntry.addProperty("entry_kind", "literal");
                    wireEntry.addProperty("payload_base64", Base64.getEncoder().encodeToString(payload));
                    wireEntry.addProperty("payload_preview", PayloadInspectionSupport.toPrintable(payload));
                    wireEntry.add("payload_analysis", PayloadInspectionSupport.inspectPayload("<template_wire_literal>", payload));
                } else if (entryType == 1) {
                    wireEntry.addProperty("entry_kind", "exact_reference");
                    wireEntry.addProperty("mapping_id", buffer.readVarInt());
                } else if (entryType == 2 || entryType == 3) {
                    int mappingId = buffer.readVarInt();
                    wireEntry.addProperty("entry_kind", entryType == 2 ? "template_reference" : "static_template_reference");
                    wireEntry.addProperty("mapping_id", mappingId);
                    JsonArray variableSegments = new JsonArray();
                    TemplateWireDefinition definition = entryType == 2
                            ? state.templatesById().get(mappingId)
                            : new TemplateWireDefinition(TemplateDictionaryBatchAlgorithm.staticTemplateVariableLengths(mappingId));
                    if (definition != null) {
                        for (int length : definition.variableLengths()) {
                            byte[] variableBytes = new byte[length];
                            buffer.readBytes(variableBytes);
                            JsonObject variable = new JsonObject();
                            variable.addProperty("length", length);
                            variable.addProperty("hex", PayloadInspectionSupport.hex(variableBytes));
                            variable.addProperty("preview", PayloadInspectionSupport.toPrintable(variableBytes));
                            variableSegments.add(variable);
                        }
                    } else {
                        wireEntry.addProperty("template_definition_missing", true);
                    }
                    wireEntry.add("variable_segments", variableSegments);
                } else {
                    throw new IllegalArgumentException("Unknown template wire entry type: " + entryType);
                }
                wireEntries.add(wireEntry);
            }
            object.add("wire_entries", wireEntries);
        } finally {
            buffer.release();
        }
    }

    private static List<String> toReplayLines(List<ReplayRecord> replayRecords) {
        List<String> lines = new ArrayList<>(replayRecords.size());
        for (ReplayRecord replayRecord : replayRecords) {
            JsonObject object = new JsonObject();
            object.addProperty("index", replayRecord.index());
            object.addProperty("batch_index", replayRecord.batchIndex());
            object.addProperty("order_in_batch", replayRecord.orderInBatch());
            object.addProperty("replay_at_ms", replayRecord.replayAtMs());
            object.addProperty("direction", replayRecord.packet().direction());
            object.addProperty("packet", replayRecord.packet().packetName());
            object.addProperty("packet_class", replayRecord.packet().packetClass());
            object.addProperty("payload_base64", Base64.getEncoder().encodeToString(replayRecord.packet().payloadBytes()));
            object.add("payload_analysis", PayloadInspectionSupport.inspectPayload(
                    replayRecord.packet().packetClass(),
                    replayRecord.packet().payloadBytes()
            ));
            lines.add(object.toString());
        }
        return lines;
    }

    private static List<String> toIncompleteParseLines(List<RawPacketRecord> records) {
        List<String> lines = new ArrayList<>();
        for (RawPacketRecord record : records) {
            JsonObject analysis = PayloadInspectionSupport.inspectPayload(record.packetClass(), record.payloadBytes());
            if (analysis.has("complete_parse") && analysis.get("complete_parse").getAsBoolean()) {
                continue;
            }

            JsonObject object = new JsonObject();
            object.addProperty("index", record.index());
            object.addProperty("captured_at_ms", record.capturedAtMs());
            object.addProperty("offset_ms", record.offsetMs());
            object.addProperty("direction", record.direction());
            object.addProperty("packet", record.packetName());
            object.addProperty("packet_class", record.packetClass());
            object.addProperty("payload_base64", Base64.getEncoder().encodeToString(record.payloadBytes()));
            object.add("payload_analysis", analysis);
            lines.add(object.toString());
        }
        return lines;
    }

    private static long countSingleUseMappedEntries(List<RawBatchInfo> batches) {
        return buildReferenceUsageCounts(batches).values().stream()
                .filter(count -> count == 1)
                .count();
    }

    private static List<String> toSingleUseMappedEntryLines(List<RawBatchInfo> batches) {
        List<String> lines = new ArrayList<>();
        Map<Integer, Integer> usageCountByMappingId = buildReferenceUsageCounts(batches);
        for (RawBatchInfo batch : batches) {
            for (int i = 0; i < batch.records().size(); i++) {
                BatchAlgorithm.EntryInfo entryInfo = batch.entryInfos().get(i);
                if (!entryInfo.reference()) {
                    continue;
                }
                int mappingId = entryInfo.referenceIndex();
                if (usageCountByMappingId.getOrDefault(mappingId, 0) != 1) {
                    continue;
                }

                RawPacketRecord record = batch.records().get(i);
                JsonObject object = new JsonObject();
                object.addProperty("batch_index", batch.batchIndex());
                object.addProperty("order", i);
                object.addProperty("index", record.index());
                object.addProperty("offset_ms", record.offsetMs());
                object.addProperty("captured_at_ms", record.capturedAtMs());
                object.addProperty("algorithm", batch.algorithmId());
                object.addProperty("packet", record.packetName());
                object.addProperty("packet_class", record.packetClass());
                object.addProperty("entry_kind", "mapped");
                object.addProperty("mapping_id", mappingId);
                object.addProperty("mapping_use_count", 1);
                object.addProperty("payload_base64", Base64.getEncoder().encodeToString(record.payloadBytes()));
                object.addProperty("payload_preview", PayloadInspectionSupport.toPrintable(record.payloadBytes()));
                object.add("payload_analysis", PayloadInspectionSupport.inspectPayload(record.packetClass(), record.payloadBytes()));
                lines.add(object.toString());
            }
        }
        return lines;
    }

    private static Map<Integer, Integer> buildReferenceUsageCounts(List<RawBatchInfo> batches) {
        Map<Integer, Integer> usageCountByMappingId = new HashMap<>();
        for (RawBatchInfo batch : batches) {
            for (BatchAlgorithm.EntryInfo entryInfo : batch.entryInfos()) {
                if (!entryInfo.reference()) {
                    continue;
                }
                usageCountByMappingId.merge(entryInfo.referenceIndex(), 1, Integer::sum);
            }
        }
        return usageCountByMappingId;
    }

    private static List<String> buildComparisonLines(ComparisonSummary comparisonSummary) {
        List<String> lines = new ArrayList<>();
        lines.add("all_equal=" + comparisonSummary.allEqual());
        lines.add("equal_packets=" + comparisonSummary.equalPackets());
        lines.addAll(comparisonSummary.mismatchLines());
        return lines;
    }

    private static List<String> buildTopPacketClassLines(
            List<RawPacketRecord> originalRecords,
            long totalOriginalBytes,
            int limit
    ) {
        Map<String, PacketClassStats> statsByClass = new HashMap<>();
        for (RawPacketRecord record : originalRecords) {
            statsByClass.compute(
                    record.packetClass(),
                    (key, existing) -> existing == null
                            ? new PacketClassStats(1, record.wireBytes())
                            : new PacketClassStats(existing.count() + 1, existing.wireBytes() + record.wireBytes())
            );
        }

        List<Map.Entry<String, PacketClassStats>> sorted = new ArrayList<>(statsByClass.entrySet());
        sorted.sort((left, right) -> Long.compare(right.getValue().wireBytes(), left.getValue().wireBytes()));

        List<String> lines = new ArrayList<>();
        lines.add("Top packet classes by original wire bytes:");
        int max = Math.min(limit, sorted.size());
        for (int i = 0; i < max; i++) {
            Map.Entry<String, PacketClassStats> entry = sorted.get(i);
            PacketClassStats stats = entry.getValue();
            double percent = totalOriginalBytes == 0L ? 0.0D : (stats.wireBytes() * 100.0D) / totalOriginalBytes;
            lines.add(
                    "  #"
                            + (i + 1)
                            + " class="
                            + entry.getKey()
                            + " bytes="
                            + stats.wireBytes()
                            + " packets="
                            + stats.count()
                            + " percent="
                            + formatMillis(percent)
                            + " %"
            );
        }
        return lines;
    }

    private static List<String> toTopPacketClassLines(List<RawPacketRecord> originalRecords) {
        String topPacketClass = findTopPacketClass(originalRecords);
        if (topPacketClass == null) {
            return List.of();
        }

        List<String> lines = new ArrayList<>();
        for (RawPacketRecord record : originalRecords) {
            if (!topPacketClass.equals(record.packetClass())) {
                continue;
            }
            JsonObject object = new JsonObject();
            object.addProperty("index", record.index());
            object.addProperty("captured_at_ms", record.capturedAtMs());
            object.addProperty("offset_ms", record.offsetMs());
            object.addProperty("direction", record.direction());
            object.addProperty("packet", record.packetName());
            object.addProperty("packet_class", record.packetClass());
            object.addProperty("wire_bytes", record.wireBytes());
            object.addProperty("payload_length", record.payloadBytes().length);
            object.addProperty("payload_base64", Base64.getEncoder().encodeToString(record.payloadBytes()));
            object.add("payload_analysis", PayloadInspectionSupport.inspectPayload(record.packetClass(), record.payloadBytes()));
            lines.add(object.toString());
        }
        return lines;
    }

    private static List<String> buildTopPacketClassSummaryLines(List<RawPacketRecord> originalRecords, long totalOriginalBytes) {
        String topPacketClass = findTopPacketClass(originalRecords);
        if (topPacketClass == null) {
            return List.of();
        }

        List<String> lines = new ArrayList<>();
        lines.add("Top class export target:");
        lines.add("  class=" + topPacketClass);
        for (String line : buildPacketClassDetailLines(
                originalRecords.stream().filter(record -> topPacketClass.equals(record.packetClass())).toList(),
                totalOriginalBytes,
                1,
                64,
                10,
                10
        )) {
            lines.add(line);
        }
        return lines;
    }

    private static String findTopPacketClass(List<RawPacketRecord> originalRecords) {
        Map<String, Long> bytesByClass = new HashMap<>();
        for (RawPacketRecord record : originalRecords) {
            bytesByClass.merge(record.packetClass(), (long) record.wireBytes(), Long::sum);
        }
        return bytesByClass.entrySet().stream()
                .max(Map.Entry.comparingByValue())
                .map(Map.Entry::getKey)
                .orElse(null);
    }

    private static List<String> buildPacketClassDetailLines(
            List<RawPacketRecord> originalRecords,
            long totalOriginalBytes,
            int classLimit,
            int byteWindow,
            int lengthLimit,
            int prefixLimit
    ) {
        Map<String, List<RawPacketRecord>> recordsByClass = new HashMap<>();
        for (RawPacketRecord record : originalRecords) {
            recordsByClass.computeIfAbsent(record.packetClass(), ignored -> new ArrayList<>()).add(record);
        }

        List<Map.Entry<String, List<RawPacketRecord>>> sorted = new ArrayList<>(recordsByClass.entrySet());
        sorted.sort((left, right) -> Long.compare(
                right.getValue().stream().mapToLong(RawPacketRecord::wireBytes).sum(),
                left.getValue().stream().mapToLong(RawPacketRecord::wireBytes).sum()
        ));

        List<String> lines = new ArrayList<>();
        lines.add("Top packet class details:");
        int maxClasses = Math.min(classLimit, sorted.size());
        for (int i = 0; i < maxClasses; i++) {
            Map.Entry<String, List<RawPacketRecord>> entry = sorted.get(i);
            List<RawPacketRecord> classRecords = entry.getValue();
            long totalBytes = classRecords.stream().mapToLong(RawPacketRecord::wireBytes).sum();
            int packetCount = classRecords.size();
            int minBytes = classRecords.stream().mapToInt(RawPacketRecord::wireBytes).min().orElse(0);
            int maxBytes = classRecords.stream().mapToInt(RawPacketRecord::wireBytes).max().orElse(0);
            double averageBytes = packetCount == 0 ? 0.0D : totalBytes / (double) packetCount;
            double percent = totalOriginalBytes == 0L ? 0.0D : totalBytes * 100.0D / totalOriginalBytes;
            lines.add("  class=" + entry.getKey());
            lines.add("    packets=" + packetCount
                    + " totalBytes=" + totalBytes
                    + " percent=" + formatMillis(percent) + " %"
                    + " avgBytes=" + formatMillis(averageBytes)
                    + " minBytes=" + minBytes
                    + " maxBytes=" + maxBytes);
            lines.add("    top_lengths=" + summarizeTopLengths(classRecords, lengthLimit));
            lines.add("    top_prefix2=" + summarizeTopPrefix2(classRecords, prefixLimit));
            lines.add("    byte_stability=" + summarizeByteStability(classRecords, byteWindow));
        }
        return lines;
    }

    private static String summarizeTopLengths(List<RawPacketRecord> records, int limit) {
        Map<Integer, Integer> countByLength = new HashMap<>();
        for (RawPacketRecord record : records) {
            countByLength.merge(record.payloadBytes().length, 1, Integer::sum);
        }
        List<Map.Entry<Integer, Integer>> sorted = new ArrayList<>(countByLength.entrySet());
        sorted.sort((left, right) -> Integer.compare(right.getValue(), left.getValue()));
        return joinTopEntries(sorted, limit, entry -> entry.getKey() + "x" + entry.getValue());
    }

    private static String summarizeTopPrefix2(List<RawPacketRecord> records, int limit) {
        Map<String, Integer> countByPrefix = new HashMap<>();
        for (RawPacketRecord record : records) {
            byte[] payload = record.payloadBytes();
            String prefix = payload.length >= 2
                    ? String.format(Locale.ROOT, "%02X%02X", payload[0] & 0xFF, payload[1] & 0xFF)
                    : payload.length == 1
                    ? String.format(Locale.ROOT, "%02X__", payload[0] & 0xFF)
                    : "__";
            countByPrefix.merge(prefix, 1, Integer::sum);
        }
        List<Map.Entry<String, Integer>> sorted = new ArrayList<>(countByPrefix.entrySet());
        sorted.sort((left, right) -> Integer.compare(right.getValue(), left.getValue()));
        return joinTopEntries(sorted, limit, entry -> entry.getKey() + "x" + entry.getValue());
    }

    private static String summarizeByteStability(List<RawPacketRecord> records, int byteWindow) {
        if (records.isEmpty()) {
            return "none";
        }
        int window = Math.min(byteWindow, records.stream().mapToInt(record -> record.payloadBytes().length).max().orElse(0));
        if (window <= 0) {
            return "none";
        }

        List<String> summaries = new ArrayList<>();
        for (int offset = 0; offset < window; offset++) {
            Map<Integer, Integer> countByByte = new HashMap<>();
            int samples = 0;
            for (RawPacketRecord record : records) {
                byte[] payload = record.payloadBytes();
                if (offset >= payload.length) {
                    continue;
                }
                countByByte.merge(payload[offset] & 0xFF, 1, Integer::sum);
                samples++;
            }
            if (samples == 0) {
                continue;
            }
            int maxCount = countByByte.values().stream().mapToInt(Integer::intValue).max().orElse(0);
            double stablePercent = maxCount * 100.0D / samples;
            summaries.add("@" + offset + "=" + formatMillis(stablePercent) + "%");
        }
        return String.join(", ", summaries);
    }

    private static <T> String joinTopEntries(
            List<Map.Entry<T, Integer>> entries,
            int limit,
            java.util.function.Function<Map.Entry<T, Integer>, String> formatter
    ) {
        if (entries.isEmpty()) {
            return "none";
        }
        List<String> parts = new ArrayList<>();
        int max = Math.min(limit, entries.size());
        for (int i = 0; i < max; i++) {
            parts.add(formatter.apply(entries.get(i)));
        }
        return String.join(", ", parts);
    }

    private static List<String> buildSummaryLines(
            List<RawPacketRecord> originalRecords,
            List<RawBatchInfo> batches,
            ComparisonSummary comparisonSummary,
            ParseSummary originalParseSummary,
            ParseSummary replayParseSummary
    ) {
        JsonObject object = new JsonObject();
        object.addProperty("packet_count", originalRecords.size());
        object.addProperty("channel_batch_count", batches.size());
        object.addProperty("channel_window_ms", PayloadBatching.WINDOW_MILLIS);
        object.addProperty("equal_packets", comparisonSummary.equalPackets());
        object.addProperty("all_equal", comparisonSummary.allEqual());
        object.addProperty("total_original_wire_bytes", originalRecords.stream().mapToLong(RawPacketRecord::wireBytes).sum());
        object.addProperty("total_channel_wire_bytes", batches.stream().mapToLong(RawBatchInfo::estimatedWireBytes).sum());
        object.addProperty("original_parse_matched", originalParseSummary.matchedCount());
        object.addProperty("original_parse_complete", originalParseSummary.completeCount());
        object.addProperty("original_parse_incomplete", originalParseSummary.incompleteCount());
        object.addProperty("replayed_parse_matched", replayParseSummary.matchedCount());
        object.addProperty("replayed_parse_complete", replayParseSummary.completeCount());
        object.addProperty("replayed_parse_incomplete", replayParseSummary.incompleteCount());
        return List.of(object.toString());
    }

    private static ParseSummary summarizeParses(List<RawPacketRecord> records) {
        int matchedCount = 0;
        int completeCount = 0;
        for (RawPacketRecord record : records) {
            JsonObject analysis = PayloadInspectionSupport.inspectPayload(record.packetClass(), record.payloadBytes());
            if (analysis.has("matched_parser") && analysis.get("matched_parser").getAsBoolean()) {
                matchedCount++;
            }
            if (analysis.has("complete_parse") && analysis.get("complete_parse").getAsBoolean()) {
                completeCount++;
            }
        }
        return new ParseSummary(records.size(), matchedCount, completeCount);
    }

    private static void writeOutputs(
            Path inputPath,
            List<String> originalLines,
            List<String> batchLines,
            List<String> batchOverviewLines,
            List<String> plainBatchJsonLines,
            List<String> optimizedPayloadDecodedLines,
            List<String> singleUseMappedEntryLines,
            List<String> replayLines,
            List<String> incompleteParseLines,
            List<String> topPacketClassLines,
            List<String> topPacketClassSummaryLines
    ) {
        try {
            Files.createDirectories(OUTPUT_DIR);
            String baseName = inputPath == null ? "dump" : stripExtension(inputPath.getFileName().toString());
            Files.write(OUTPUT_DIR.resolve(baseName + "-original.jsonl"), originalLines, StandardCharsets.UTF_8);
            Files.write(OUTPUT_DIR.resolve(baseName + "-channel-batches.txt"), batchLines, StandardCharsets.UTF_8);
            Files.write(OUTPUT_DIR.resolve(baseName + "-channel-batches-overview.txt"), batchOverviewLines, StandardCharsets.UTF_8);
            Files.write(OUTPUT_DIR.resolve(baseName + "-channel-batches-plain.jsonl"), plainBatchJsonLines, StandardCharsets.UTF_8);
            Files.write(OUTPUT_DIR.resolve(baseName + "-channel-batches-decoded.jsonl"), optimizedPayloadDecodedLines, StandardCharsets.UTF_8);
            Files.write(OUTPUT_DIR.resolve(baseName + "-single-use-mapped.jsonl"), singleUseMappedEntryLines, StandardCharsets.UTF_8);
            Files.write(OUTPUT_DIR.resolve(baseName + "-replayed.jsonl"), replayLines, StandardCharsets.UTF_8);
            Files.write(OUTPUT_DIR.resolve(baseName + "-incomplete-parse.jsonl"), incompleteParseLines, StandardCharsets.UTF_8);
            Files.write(OUTPUT_DIR.resolve(baseName + "-top-packet-class.jsonl"), topPacketClassLines, StandardCharsets.UTF_8);
            Files.write(OUTPUT_DIR.resolve(baseName + "-top-packet-class-summary.txt"), topPacketClassSummaryLines, StandardCharsets.UTF_8);
        } catch (IOException exception) {
            throw new IllegalStateException("Failed to write replay output files", exception);
        }
    }

    private static List<PacketDumpReplayMain.DumpInput> readDumpInputs(String[] args) {
        Path inputPath = resolveInputPath(args);
        if (inputPath == null || !Files.exists(inputPath)) {
            return List.of();
        }

        try {
            String content = Files.readString(inputPath, StandardCharsets.UTF_8);
            return parseDumpInputs(content);
        } catch (IOException exception) {
            throw new IllegalArgumentException("Failed to read dump input file: " + inputPath, exception);
        }
    }

    private static Path resolveInputPath(String[] args) {
        if (args.length == 0) {
            return DEFAULT_INPUT_PATH;
        }
        String joined = joinNonOptionArgs(args).trim();
        if (joined.isEmpty()) {
            return DEFAULT_INPUT_PATH;
        }
        if (joined.startsWith("@")) {
            return Path.of(joined.substring(1).trim());
        }
        return Path.of(joined);
    }

    private static void configureReplayOverrides(String[] args) {
        for (String arg : args) {
            if (arg.startsWith("--algorithm=")) {
                applyAlgorithmOverride(arg.substring("--algorithm=".length()).trim());
                continue;
            }
            if (arg.startsWith("--streaming-zstd-level=")) {
                Config.batchStreamingZstdLevel = Integer.parseInt(arg.substring("--streaming-zstd-level=".length()).trim());
            }
        }
    }

    private static String joinNonOptionArgs(String[] args) {
        List<String> parts = new ArrayList<>();
        for (String arg : args) {
            if (arg.startsWith("--algorithm=")
                    || arg.startsWith("--streaming-zstd-level=")) {
                continue;
            }
            parts.add(arg);
        }
        return String.join(" ", parts);
    }

    private static void applyAlgorithmOverride(String algorithmId) {
        if (!TEST_ALGORITHM_IDS.contains(algorithmId)) {
            throw new IllegalArgumentException(
                    "Unsupported test algorithm id: " + algorithmId + ". Allowed: " + String.join(", ", TEST_ALGORITHM_IDS)
            );
        }
        BatchAlgorithmRegistry.setOverride(algorithmId);
    }

    private static List<PacketDumpReplayMain.DumpInput> parseDumpInputs(String content) {
        String trimmed = content.trim();
        if (trimmed.isEmpty()) {
            return List.of();
        }

        try {
            JsonElement root = JsonParser.parseString(trimmed);
            if (root.isJsonObject()) {
                return parseJsonObject(root.getAsJsonObject());
            }
            if (root.isJsonArray()) {
                return parseJsonArray(root.getAsJsonArray());
            }
        } catch (Exception ignored) {
            // Fall back to JSONL parsing.
        }

        return parseJsonLines(trimmed);
    }

    private static List<PacketDumpReplayMain.DumpInput> parseJsonObject(JsonObject object) {
        if (isPacketObject(object)) {
            return List.of(PacketDumpReplayMain.DumpInput.fromJsonLine(object.toString()));
        }
        return List.of();
    }

    private static List<PacketDumpReplayMain.DumpInput> parseJsonArray(JsonArray array) {
        List<PacketDumpReplayMain.DumpInput> result = new ArrayList<>();
        for (JsonElement element : array) {
            if (element != null && element.isJsonObject() && isPacketObject(element.getAsJsonObject())) {
                result.add(PacketDumpReplayMain.DumpInput.fromJsonLine(element.getAsJsonObject().toString()));
            }
        }
        return result;
    }

    private static List<PacketDumpReplayMain.DumpInput> parseJsonLines(String content) {
        List<PacketDumpReplayMain.DumpInput> result = new ArrayList<>();
        String[] lines = content.split("\\R");
        for (String rawLine : lines) {
            String line = rawLine.trim();
            if (line.isEmpty()) {
                continue;
            }
            JsonObject object;
            try {
                object = JsonParser.parseString(line).getAsJsonObject();
            } catch (Exception ignored) {
                continue;
            }
            if (!isPacketObject(object)) {
                continue;
            }
            result.add(PacketDumpReplayMain.DumpInput.fromJsonLine(object.toString()));
        }
        return result;
    }

    private static boolean isPacketObject(JsonObject object) {
        return object.has("type")
                && "packet".equals(readOptionalString(object, "type", ""))
                && (object.has("serialized_payload_base64") || object.has("payload_base64"));
    }

    private static String readOptionalString(JsonObject object, String key, String fallback) {
        return object.has(key) && !object.get(key).isJsonNull() ? object.get(key).getAsString() : fallback;
    }

    private static String stripExtension(String fileName) {
        int index = fileName.lastIndexOf('.');
        return index >= 0 ? fileName.substring(0, index) : fileName;
    }

    private static String formatMillis(double value) {
        return String.format(Locale.ROOT, "%.3f", value);
    }

    private static String formatDuration(long nanos) {
        return String.format(Locale.ROOT, "%.3f ms", nanos / 1_000_000.0D);
    }

    private static String hex(byte[] bytes) {
        return PayloadInspectionSupport.hex(bytes);
    }

    private record RawPacketRecord(
            int index,
            long capturedAtMs,
            long offsetMs,
            String direction,
            String packetName,
            String packetClass,
            int wireBytes,
            byte[] payloadBytes
    ) {
    }

    private record RawBatchInfo(
            int batchIndex,
            List<RawPacketRecord> records,
            byte[] channelBytes,
            List<BatchAlgorithm.EntryInfo> entryInfos,
            int estimatedWireBytes,
            String algorithmId,
            int addedMappings,
            int removedMappings,
            double replayIntervalMs
    ) {
    }

    private record ReplayRecord(
            int index,
            int batchIndex,
            int orderInBatch,
            double replayAtMs,
            RawPacketRecord packet
    ) {
    }

    private record ComparisonSummary(
            int equalPackets,
            List<String> mismatchLines,
            boolean allEqual
    ) {
    }

    private record ParseSummary(
            int totalCount,
            int matchedCount,
            int completeCount
    ) {
        private int incompleteCount() {
            return totalCount - completeCount;
        }
    }

    private record PacketClassStats(
            int count,
            long wireBytes
    ) {
    }

    private record TemplateWireDefinition(
            List<Integer> variableLengths
    ) {
    }

    private record TimedValue<T>(
            T value,
            long elapsedNanos
    ) {
    }

    private record TemplateDiagnosticState(
            Map<Integer, TemplateWireDefinition> templatesById
    ) {
        private TemplateDiagnosticState() {
            this(new HashMap<>());
        }
    }
}
