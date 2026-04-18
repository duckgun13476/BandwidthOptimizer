package com.PinkCats.bandwidthoptimizer.Old.network;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import io.netty.buffer.Unpooled;
import net.minecraft.core.BlockPos;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.FriendlyByteBuf;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;

public final class SimilarPacketInspectorMain {

    private static final Path DEFAULT_INPUT_PATH = Path.of("dump", "similar", "1.jsonl");

    private SimilarPacketInspectorMain() {
    }

    public static void main(String[] args) {
        Path inputPath = resolveInputPath(args);
        List<SimilarDumpEntry> entries = readEntries(inputPath);
        if (entries.isEmpty()) {
            System.out.println("Usage:");
            System.out.println("  ./gradlew.bat runSimilarPacketInspector");
            System.out.println("  ./gradlew.bat runSimilarPacketInspector --args=\"@C:\\path\\to\\similar.jsonl\"");
            return;
        }

        System.out.println("=== Similar Packet Inspector ===");
        System.out.println("Input: " + inputPath.toAbsolutePath());
        System.out.println("Entry count: " + entries.size());
        System.out.println("Packet class: " + entries.get(0).packetClass());
        System.out.println("Note: the dump `payload_*` bytes are already raw packet payload bytes after Netty/MC decode.");
        System.out.println("Note: Forge play packets here are not using an extra packet-level encryption layer in this tool.");
        System.out.println();

        SimilarDumpEntry baseline = entries.get(0);
        ParsedBlockEntityPayload baselineParsed = tryParseBlockEntityPayload(baseline.payloadBytes());
        printEntry("baseline", baseline, baselineParsed);

        for (int i = 1; i < entries.size(); i++) {
            SimilarDumpEntry current = entries.get(i);
            ParsedBlockEntityPayload parsed = tryParseBlockEntityPayload(current.payloadBytes());
            printEntry("entry[" + i + "]", current, parsed);
            printDiff(baseline, baselineParsed, current, parsed);
        }

        System.out.println("=== Similar Packet Inspector Completed ===");
    }

    private static void printEntry(String label, SimilarDumpEntry entry, ParsedBlockEntityPayload parsed) {
        System.out.println("[" + label + "]");
        System.out.println("mappingId=" + entry.mappingId()
                + " batch=" + entry.batchIndex()
                + " order=" + entry.order()
                + " capturedAtMs=" + entry.capturedAtMs());
        System.out.println("payloadPrefixHex=" + hexPrefix(entry.payloadBytes(), 24));
        System.out.println("payloadPrintable=" + toPrintable(entry.payloadBytes()));
        if (parsed.parsed()) {
            System.out.println("blockPos=" + parsed.blockPos());
            System.out.println("blockEntityTypeId=" + parsed.blockEntityTypeId());
            System.out.println("nbt=" + parsed.nbtString());
            System.out.println("remainingBytes=" + parsed.remainingBytes());
        } else {
            System.out.println("parseError=" + parsed.parseError());
        }
        System.out.println();
    }

    private static void printDiff(
            SimilarDumpEntry baseline,
            ParsedBlockEntityPayload baselineParsed,
            SimilarDumpEntry current,
            ParsedBlockEntityPayload currentParsed
    ) {
        byte[] left = baseline.payloadBytes();
        byte[] right = current.payloadBytes();
        List<Integer> diffIndexes = diffIndexes(left, right, 12);
        System.out.println("[diff baseline -> mappingId=" + current.mappingId() + "]");
        System.out.println("commonPrefixBytes=" + commonPrefixLength(left, right));
        System.out.println("commonSuffixBytes=" + commonSuffixLength(left, right));
        System.out.println("diffCount=" + countDiffs(left, right));
        System.out.println("firstDiffs=" + diffIndexes);
        for (int index : diffIndexes) {
            System.out.println("  byte[" + index + "] baseline=" + unsignedHex(left, index) + " current=" + unsignedHex(right, index));
        }
        if (baselineParsed.parsed() && currentParsed.parsed()) {
            System.out.println("blockPosEqual=" + baselineParsed.blockPos().equals(currentParsed.blockPos()));
            System.out.println("typeEqual=" + baselineParsed.blockEntityTypeId().equals(currentParsed.blockEntityTypeId()));
            System.out.println("nbtEqual=" + baselineParsed.nbtString().equals(currentParsed.nbtString()));
        }
        System.out.println();
    }

    private static ParsedBlockEntityPayload tryParseBlockEntityPayload(byte[] payloadBytes) {
        FriendlyByteBuf buffer = new FriendlyByteBuf(Unpooled.wrappedBuffer(payloadBytes));
        try {
            BlockPos blockPos = buffer.readBlockPos();
            int typeId = buffer.readVarInt();
            CompoundTag nbt = buffer.readNbt();
            int remainingBytes = buffer.readableBytes();
            return new ParsedBlockEntityPayload(
                    true,
                    blockPos,
                    Integer.toString(typeId),
                    nbt == null ? "null" : nbt.toString(),
                    remainingBytes,
                    null
            );
        } catch (Exception exception) {
            return new ParsedBlockEntityPayload(false, null, null, null, -1, exception.getClass().getSimpleName() + ": " + exception.getMessage());
        } finally {
            buffer.release();
        }
    }

    private static int commonPrefixLength(byte[] left, byte[] right) {
        int max = Math.min(left.length, right.length);
        int index = 0;
        while (index < max && left[index] == right[index]) {
            index++;
        }
        return index;
    }

    private static int commonSuffixLength(byte[] left, byte[] right) {
        int leftIndex = left.length - 1;
        int rightIndex = right.length - 1;
        int count = 0;
        while (leftIndex >= 0 && rightIndex >= 0 && left[leftIndex] == right[rightIndex]) {
            leftIndex--;
            rightIndex--;
            count++;
        }
        return count;
    }

    private static int countDiffs(byte[] left, byte[] right) {
        int max = Math.max(left.length, right.length);
        int count = 0;
        for (int i = 0; i < max; i++) {
            byte leftByte = i < left.length ? left[i] : 0;
            byte rightByte = i < right.length ? right[i] : 0;
            if (i >= left.length || i >= right.length || leftByte != rightByte) {
                count++;
            }
        }
        return count;
    }

    private static List<Integer> diffIndexes(byte[] left, byte[] right, int limit) {
        List<Integer> indexes = new ArrayList<>();
        int max = Math.max(left.length, right.length);
        for (int i = 0; i < max && indexes.size() < limit; i++) {
            byte leftByte = i < left.length ? left[i] : 0;
            byte rightByte = i < right.length ? right[i] : 0;
            if (i >= left.length || i >= right.length || leftByte != rightByte) {
                indexes.add(i);
            }
        }
        return indexes;
    }

    private static String unsignedHex(byte[] bytes, int index) {
        if (index < 0 || index >= bytes.length) {
            return "--";
        }
        return String.format("%02x", bytes[index] & 0xFF);
    }

    private static String hexPrefix(byte[] bytes, int limit) {
        int length = Math.min(bytes.length, limit);
        StringBuilder builder = new StringBuilder(length * 2);
        for (int i = 0; i < length; i++) {
            builder.append(String.format("%02x", bytes[i] & 0xFF));
        }
        return builder.toString();
    }

    private static String toPrintable(byte[] payloadBytes) {
        StringBuilder builder = new StringBuilder(payloadBytes.length);
        for (byte value : payloadBytes) {
            int unsigned = value & 0xFF;
            if (unsigned >= 32 && unsigned <= 126) {
                builder.append((char) unsigned);
            } else {
                builder.append('.');
            }
        }
        return builder.toString();
    }

    private static List<SimilarDumpEntry> readEntries(Path inputPath) {
        if (inputPath == null || !Files.exists(inputPath)) {
            return List.of();
        }

        try {
            List<SimilarDumpEntry> entries = new ArrayList<>();
            for (String line : Files.readAllLines(inputPath, StandardCharsets.UTF_8)) {
                String trimmed = line.trim();
                if (trimmed.isEmpty()) {
                    continue;
                }
                JsonObject object = JsonParser.parseString(trimmed).getAsJsonObject();
                entries.add(SimilarDumpEntry.fromJson(object));
            }
            return entries;
        } catch (IOException exception) {
            throw new IllegalArgumentException("Failed to read input file: " + inputPath, exception);
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

    private record SimilarDumpEntry(
            int batchIndex,
            int order,
            long index,
            long capturedAtMs,
            int mappingId,
            String packetClass,
            byte[] payloadBytes
    ) {
        private static SimilarDumpEntry fromJson(JsonObject object) {
            return new SimilarDumpEntry(
                    object.get("batch_index").getAsInt(),
                    object.get("order").getAsInt(),
                    object.get("index").getAsLong(),
                    object.get("captured_at_ms").getAsLong(),
                    object.get("mapping_id").getAsInt(),
                    object.get("packet_class").getAsString(),
                    Base64.getDecoder().decode(object.get("payload_base64").getAsString())
            );
        }
    }

    private record ParsedBlockEntityPayload(
            boolean parsed,
            BlockPos blockPos,
            String blockEntityTypeId,
            String nbtString,
            int remainingBytes,
            String parseError
    ) {
    }
}
