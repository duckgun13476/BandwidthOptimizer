package com.PinkCats.bandwidthoptimizer.recipe;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;

public final class RecipeSyncDeltaCodec {

    private static final int MAGIC = 0x424F5231;
    private static final int VERSION = 1;
    private static final int COPY = 1;
    private static final int LITERAL = 2;
    private static final int MATCH_BLOCK = 32;
    private static final int MAX_HASH_CANDIDATES = 64;
    private static final int MAX_COMMANDS = 262_144;
    private static final long HASH_BASE = 0x9E3779B185EBCA87L;
    private static final long HASH_HIGH_FACTOR = hashHighFactor();
    private static final int STRUCTURAL_ANCHOR_BYTES = 8;
    private static final int STRUCTURAL_ANCHOR_SAMPLES = 8;

    private RecipeSyncDeltaCodec() {}

    public static byte[] encode(byte[] base, byte[] target) {
        if (base == null || target == null || base.length == 0 || target.length == 0) {
            return null;
        }
        if (base.length < MATCH_BLOCK || target.length < MATCH_BLOCK) {
            return encodeLiteral(target);
        }
        Map<Long, List<Integer>> baseBlocks = indexBlocks(base);
        List<Command> commands = new ArrayList<>();
        ByteArrayOutputStream literal = new ByteArrayOutputStream();
        int targetOffset = 0;
        long targetHash = hashWindow(target, 0);
        while (targetOffset < target.length) {
            Match match = findBestMatch(base, target, targetOffset, targetHash, baseBlocks);
            if (match == null) {
                literal.write(target[targetOffset]);
                targetOffset++;
                if (targetOffset <= target.length - MATCH_BLOCK) {
                    targetHash = targetOffset == 0
                            ? hashWindow(target, 0)
                            : slideHash(targetHash, target[targetOffset - 1],
                            target[targetOffset + MATCH_BLOCK - 1]);
                }
            } else {
                flushLiteral(commands, literal);
                appendCopy(commands, match.offset(), match.length());
                targetOffset += match.length();
                if (targetOffset <= target.length - MATCH_BLOCK) {
                    targetHash = hashWindow(target, targetOffset);
                }
            }
            if (commands.size() > MAX_COMMANDS) {
                return null;
            }
        }
        flushLiteral(commands, literal);
        if (commands.isEmpty() || commands.size() > MAX_COMMANDS) {
            return null;
        }

        return encodeCommands(target.length, commands);
    }

    public static byte[] encodeIdentity(int targetLength) {
        if (targetLength <= 0) {
            return null;
        }
        return encodeCommands(targetLength, List.of(new Command(COPY, 0, targetLength, null)));
    }

    public static byte[] encodeStructured(byte[] base, RecipeSyncStructuralView target) {
        if (base == null || base.length == 0 || target == null
                || target.trustedPrefix().length == 0 || target.trustedRecords().isEmpty()) {
            return null;
        }
        List<byte[]> records = target.trustedRecords();
        Map<Long, Integer> frequencies = new HashMap<>();
        List<int[]> samples = new ArrayList<>(records.size());
        for (byte[] record : records) {
            int[] offsets = sampleAnchorOffsets(record.length);
            samples.add(offsets);
            for (int offset : offsets) {
                frequencies.merge(readLong(record, offset), 1, Integer::sum);
            }
        }
        Map<Long, List<StructuralCandidate>> candidates = new HashMap<>();
        for (int index = 0; index < records.size(); index++) {
            byte[] record = records.get(index);
            int[] offsets = samples.get(index);
            if (offsets.length == 0) {
                continue;
            }
            int selectedOffset = offsets[0];
            int selectedFrequency = Integer.MAX_VALUE;
            for (int offset : offsets) {
                int frequency = frequencies.getOrDefault(readLong(record, offset), Integer.MAX_VALUE);
                if (frequency < selectedFrequency) {
                    selectedOffset = offset;
                    selectedFrequency = frequency;
                }
            }
            candidates.computeIfAbsent(readLong(record, selectedOffset), ignored -> new ArrayList<>())
                    .add(new StructuralCandidate(index, selectedOffset));
        }
        int[] sourceOffsets = new int[records.size()];
        Arrays.fill(sourceOffsets, -1);
        for (int offset = 0; offset <= base.length - STRUCTURAL_ANCHOR_BYTES; offset++) {
            List<StructuralCandidate> matchedCandidates = candidates.get(readLong(base, offset));
            if (matchedCandidates == null) {
                continue;
            }
            for (StructuralCandidate candidate : matchedCandidates) {
                if (sourceOffsets[candidate.recordIndex()] >= 0) {
                    continue;
                }
                byte[] record = records.get(candidate.recordIndex());
                int sourceOffset = offset - candidate.anchorOffset();
                if (sourceOffset >= 0 && sourceOffset <= base.length - record.length
                        && Arrays.equals(base, sourceOffset, sourceOffset + record.length,
                        record, 0, record.length)) {
                    sourceOffsets[candidate.recordIndex()] = sourceOffset;
                }
            }
        }

        List<Command> commands = new ArrayList<>();
        ByteArrayOutputStream literal = new ByteArrayOutputStream();
        literal.writeBytes(target.trustedPrefix());
        int targetLength = target.trustedPrefix().length;
        for (int index = 0; index < records.size(); index++) {
            byte[] record = records.get(index);
            targetLength += record.length;
            int sourceOffset = sourceOffsets[index];
            if (sourceOffset < 0) {
                literal.writeBytes(record);
            } else {
                flushLiteral(commands, literal);
                appendCopy(commands, sourceOffset, record.length);
            }
            if (commands.size() > MAX_COMMANDS || targetLength < 0) {
                return null;
            }
        }
        flushLiteral(commands, literal);
        return commands.isEmpty() || commands.size() > MAX_COMMANDS
                ? null
                : encodeCommands(targetLength, commands);
    }

    static SingleRecordSample sampleSingleRecordReplacement(
            byte[] targetBytes, RecipeSyncStructuralView target, int maximumTargetBytes) {
        if (targetBytes == null || target == null || target.trustedRecords().isEmpty()) {
            return SingleRecordSample.unavailable();
        }
        List<byte[]> records = target.trustedRecords();
        int selectedIndex = 0;
        int[] lengths = records.stream().mapToInt(record -> record.length).sorted().toArray();
        int medianLength = lengths[lengths.length / 2];
        for (int index = 0; index < records.size(); index++) {
            if (records.get(index).length == medianLength) {
                selectedIndex = index;
                break;
            }
        }
        int selectedOffset = target.trustedPrefix().length;
        for (int index = 0; index < selectedIndex; index++) {
            selectedOffset += records.get(index).length;
        }
        byte[] syntheticBase = targetBytes.clone();
        byte[] selectedRecord = records.get(selectedIndex);
        for (int index = 0; index < selectedRecord.length; index++) {
            syntheticBase[selectedOffset + index] ^= (byte) 0xA5;
        }
        byte[] delta = encodeStructured(syntheticBase, target);
        if (delta == null) {
            return SingleRecordSample.unavailable();
        }
        try {
            byte[] restored = decode(syntheticBase, delta, maximumTargetBytes);
            if (!Arrays.equals(restored, targetBytes)) {
                return SingleRecordSample.unavailable();
            }
        } catch (IOException ignored) {
            return SingleRecordSample.unavailable();
        }
        return new SingleRecordSample(true, selectedRecord.length, delta.length);
    }

    private static byte[] encodeCommands(int targetLength, List<Command> commands) {
        ByteArrayOutputStream output = new ByteArrayOutputStream(Math.max(targetLength / 2, 32));
        writeInt(output, MAGIC);
        writeVarInt(output, VERSION);
        writeVarInt(output, targetLength);
        writeVarInt(output, commands.size());
        for (Command command : commands) {
            output.write(command.kind());
            if (command.kind() == COPY) {
                writeVarInt(output, command.offset());
                writeVarInt(output, command.length());
            } else {
                writeVarInt(output, command.length());
                output.writeBytes(command.bytes());
            }
        }
        return output.toByteArray();
    }

    public static byte[] decode(byte[] base, byte[] delta, int maximumTargetBytes) throws IOException {
        if (base == null || delta == null || maximumTargetBytes <= 0) {
            throw new IOException("Invalid recipe delta input");
        }
        ByteBuffer input = ByteBuffer.wrap(delta);
        if (input.remaining() < Integer.BYTES || input.getInt() != MAGIC) {
            throw new IOException("Invalid recipe delta magic");
        }
        if (readVarInt(input) != VERSION) {
            throw new IOException("Unsupported recipe delta version");
        }
        int targetLength = readVarInt(input);
        int commandCount = readVarInt(input);
        if (targetLength <= 0 || targetLength > maximumTargetBytes || commandCount <= 0 || commandCount > MAX_COMMANDS) {
            throw new IOException("Invalid recipe delta bounds");
        }
        byte[] target = new byte[targetLength];
        int outputOffset = 0;
        for (int commandIndex = 0; commandIndex < commandCount; commandIndex++) {
            if (!input.hasRemaining()) {
                throw new IOException("Truncated recipe delta command");
            }
            int kind = Byte.toUnsignedInt(input.get());
            if (kind == COPY) {
                int sourceOffset = readVarInt(input);
                int length = readVarInt(input);
                if (!validRange(sourceOffset, length, base.length) || !validRange(outputOffset, length, target.length)) {
                    throw new IOException("Invalid recipe delta copy range");
                }
                System.arraycopy(base, sourceOffset, target, outputOffset, length);
                outputOffset += length;
            } else if (kind == LITERAL) {
                int length = readVarInt(input);
                if (length < 0 || length > input.remaining() || !validRange(outputOffset, length, target.length)) {
                    throw new IOException("Invalid recipe delta literal range");
                }
                input.get(target, outputOffset, length);
                outputOffset += length;
            } else {
                throw new IOException("Unknown recipe delta command");
            }
        }
        if (outputOffset != target.length || input.hasRemaining()) {
            throw new IOException("Recipe delta did not reconstruct the exact target length");
        }
        return target;
    }

    public static String sha256(byte[] bytes) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("SHA-256 is unavailable", exception);
        }
    }

    public static boolean isHash(String hash) {
        return hash != null && hash.length() == 64 && hash.chars().allMatch(character ->
                character >= '0' && character <= '9' || character >= 'a' && character <= 'f');
    }

    private static byte[] encodeLiteral(byte[] target) {
        List<Command> commands = List.of(new Command(LITERAL, 0, target.length, target.clone()));
        return encodeCommands(target.length, commands);
    }

    private static Map<Long, List<Integer>> indexBlocks(byte[] bytes) {
        Map<Long, List<Integer>> indexed = new HashMap<>();
        for (int offset = 0; offset <= bytes.length - MATCH_BLOCK; offset += MATCH_BLOCK) {
            long hash = hashWindow(bytes, offset);
            List<Integer> candidates = indexed.computeIfAbsent(hash, ignored -> new ArrayList<>());
            if (candidates.size() < MAX_HASH_CANDIDATES) {
                candidates.add(offset);
            }
        }
        return indexed;
    }

    private static Match findBestMatch(
            byte[] base,
            byte[] target,
            int targetOffset,
            long targetHash,
            Map<Long, List<Integer>> baseBlocks
    ) {
        if (targetOffset > target.length - MATCH_BLOCK) {
            return null;
        }
        List<Integer> candidates = baseBlocks.get(targetHash);
        if (candidates == null) {
            return null;
        }
        Match best = null;
        for (int baseOffset : candidates) {
            if (!Arrays.equals(base, baseOffset, baseOffset + MATCH_BLOCK,
                    target, targetOffset, targetOffset + MATCH_BLOCK)) {
                continue;
            }
            int length = MATCH_BLOCK;
            while (baseOffset + length < base.length
                    && targetOffset + length < target.length
                    && base[baseOffset + length] == target[targetOffset + length]) {
                length++;
            }
            if (best == null || length > best.length()) {
                best = new Match(baseOffset, length);
            }
        }
        return best;
    }

    private static void appendCopy(List<Command> commands, int offset, int length) {
        if (!commands.isEmpty()) {
            Command previous = commands.get(commands.size() - 1);
            if (previous.kind() == COPY && previous.offset() + previous.length() == offset) {
                commands.set(commands.size() - 1,
                        new Command(COPY, previous.offset(), previous.length() + length, null));
                return;
            }
        }
        commands.add(new Command(COPY, offset, length, null));
    }

    private static void flushLiteral(List<Command> commands, ByteArrayOutputStream literal) {
        if (literal.size() == 0) {
            return;
        }
        byte[] bytes = literal.toByteArray();
        commands.add(new Command(LITERAL, 0, bytes.length, bytes));
        literal.reset();
    }

    private static boolean validRange(int offset, int length, int totalLength) {
        return offset >= 0 && length >= 0 && offset <= totalLength - length;
    }

    private static long hashWindow(byte[] bytes, int offset) {
        long hash = 0L;
        for (int index = offset; index < offset + MATCH_BLOCK; index++) {
            hash = hash * HASH_BASE + Byte.toUnsignedInt(bytes[index]) + 1L;
        }
        return hash;
    }

    private static long slideHash(long hash, byte outgoing, byte incoming) {
        return (hash - (Byte.toUnsignedInt(outgoing) + 1L) * HASH_HIGH_FACTOR) * HASH_BASE
                + Byte.toUnsignedInt(incoming) + 1L;
    }

    private static long hashHighFactor() {
        long factor = 1L;
        for (int index = 1; index < MATCH_BLOCK; index++) {
            factor *= HASH_BASE;
        }
        return factor;
    }

    private static int[] sampleAnchorOffsets(int recordLength) {
        if (recordLength < STRUCTURAL_ANCHOR_BYTES) {
            return new int[0];
        }
        int maximumOffset = recordLength - STRUCTURAL_ANCHOR_BYTES;
        int samples = Math.min(STRUCTURAL_ANCHOR_SAMPLES, maximumOffset + 1);
        int[] offsets = new int[samples];
        for (int index = 0; index < samples; index++) {
            offsets[index] = samples == 1 ? 0 : maximumOffset * index / (samples - 1);
        }
        return Arrays.stream(offsets).distinct().toArray();
    }

    private static long readLong(byte[] bytes, int offset) {
        long value = 0L;
        for (int index = 0; index < STRUCTURAL_ANCHOR_BYTES; index++) {
            value = value << 8 | Byte.toUnsignedLong(bytes[offset + index]);
        }
        return value;
    }

    private static void writeInt(ByteArrayOutputStream output, int value) {
        output.write(value >>> 24);
        output.write(value >>> 16);
        output.write(value >>> 8);
        output.write(value);
    }

    private static void writeVarInt(ByteArrayOutputStream output, int value) {
        int remaining = value;
        while ((remaining & ~0x7F) != 0) {
            output.write((remaining & 0x7F) | 0x80);
            remaining >>>= 7;
        }
        output.write(remaining);
    }

    private static int readVarInt(ByteBuffer input) throws IOException {
        int value = 0;
        for (int index = 0; index < 5; index++) {
            if (!input.hasRemaining()) {
                throw new IOException("Truncated recipe delta varint");
            }
            int current = Byte.toUnsignedInt(input.get());
            value |= (current & 0x7F) << index * 7;
            if ((current & 0x80) == 0) {
                return value;
            }
        }
        throw new IOException("Recipe delta varint is too long");
    }

    private record Match(int offset, int length) {}
    private record Command(int kind, int offset, int length, byte[] bytes) {}
    private record StructuralCandidate(int recordIndex, int anchorOffset) {}

    record SingleRecordSample(boolean available, int recordBytes, int deltaBytes) {
        private static SingleRecordSample unavailable() {
            return new SingleRecordSample(false, 0, 0);
        }
    }
}
