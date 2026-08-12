package com.PinkCats.bandwidthoptimizer.chunk.persistent;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.HexFormat;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Properties;
import java.util.Set;
import java.util.UUID;
import java.util.function.BooleanSupplier;
import java.util.concurrent.locks.LockSupport;
import java.util.zip.CRC32;
import java.util.zip.Deflater;
import java.util.zip.DeflaterOutputStream;
import java.util.zip.GZIPInputStream;
import java.util.zip.GZIPOutputStream;
import java.util.zip.Inflater;
import java.util.zip.InflaterInputStream;

final class PersistentChunkCacheDiskStore {

    private static final int SEGMENT_MAGIC = 0x424F5332;
    private static final int SEGMENT_VERSION = 1;
    private static final int SEGMENT_HEADER_BYTES = 56;
    private static final int WAL_MAGIC = 0x424F5732;
    private static final int WAL_VERSION = 1;
    private static final int WAL_HEADER_BYTES = 16;
    private static final int MAX_BLOB_BYTES = 64 * 1024 * 1024;
    private static final int MAX_WAL_RECORD_BYTES = 1024 * 1024;
    private static final long MAX_SEGMENT_BYTES = 64L * 1024L * 1024L;
    private static final long FORCE_INTERVAL_NANOS = 30_000_000_000L;
    private static final String INDEX_FILE_NAME = "index.properties.gz";
    private static final String BACKUP_INDEX_FILE_NAME = "index.backup.properties.gz";
    private static final String WAL_FILE_NAME = "index.wal";
    private static final String SEGMENTS_DIRECTORY_NAME = "segments";
    private static final String TEMP_DIRECTORY_NAME = "tmp";
    private static final List<String> ENTRY_SUFFIXES = List.of(
            "hash",
            "protocolName",
            "packetClassName",
            "fullSnapshotVersion",
            "encodedBytes",
            "lastUsedAtMillis",
            "serverScopeHash",
            "hitCount",
            "savedBytes",
            "temperature"
    );
    private static final String REMOVE_BATCH_PREFIX = "\u0000remove-batch";
    private static final int REMOVE_BATCH_ENTRIES = 1024;

    private final Path root;
    private final Path segmentsRoot;
    private final Path tempRoot;
    private final int compressionLevel;
    private final long maxSegmentBytes;
    private final Map<String, BlobLocation> blobs = new HashMap<>();
    private int activeSegmentId;
    private long lastForceNanos;

    private PersistentChunkCacheDiskStore(Path root, int compressionLevel, long maxSegmentBytes) {
        this.root = root;
        this.segmentsRoot = root.resolve(SEGMENTS_DIRECTORY_NAME);
        this.tempRoot = root.resolve(TEMP_DIRECTORY_NAME);
        this.compressionLevel = Math.max(Deflater.NO_COMPRESSION, Math.min(Deflater.BEST_COMPRESSION, compressionLevel));
        this.maxSegmentBytes = Math.max(maxSegmentBytes, SEGMENT_HEADER_BYTES + 1L);
    }

    static PersistentChunkCacheDiskStore open(Path root, int compressionLevel) throws IOException {
        return open(root, compressionLevel, MAX_SEGMENT_BYTES);
    }

    static PersistentChunkCacheDiskStore openForTesting(Path root, int compressionLevel, long maxSegmentBytes) throws IOException {
        return open(root, compressionLevel, maxSegmentBytes);
    }

    private static PersistentChunkCacheDiskStore open(Path root, int compressionLevel, long maxSegmentBytes) throws IOException {
        PersistentChunkCacheDiskStore store = new PersistentChunkCacheDiskStore(root, compressionLevel, maxSegmentBytes);
        Files.createDirectories(store.segmentsRoot);
        Files.createDirectories(store.tempRoot);
        store.cleanupTemporaryFiles();
        store.scanSegments();
        return store;
    }

    Properties loadIndex() throws IOException {
        Properties index = loadCheckpoint();
        replayWal(index);
        return index;
    }

    void appendIndexEntry(String keyPrefix, Properties index) throws IOException {
        if (keyPrefix == null || keyPrefix.isBlank() || index == null) {
            throw new IOException("Invalid persistent cache index mutation");
        }
        ByteArrayOutputStream payloadBytes = new ByteArrayOutputStream(512);
        try (DataOutputStream payload = new DataOutputStream(payloadBytes)) {
            payload.writeUTF(keyPrefix);
            payload.writeInt(ENTRY_SUFFIXES.size());
            for (String suffix : ENTRY_SUFFIXES) {
                payload.writeUTF(suffix);
                payload.writeUTF(index.getProperty(keyPrefix + suffix, ""));
            }
        }
        appendWalRecord(payloadBytes.toByteArray());
    }

    void appendIndexRemovals(List<String> keyPrefixes) throws IOException {
        if (keyPrefixes == null || keyPrefixes.isEmpty()) {
            return;
        }
        for (int start = 0; start < keyPrefixes.size(); start += REMOVE_BATCH_ENTRIES) {
            int end = Math.min(start + REMOVE_BATCH_ENTRIES, keyPrefixes.size());
            ByteArrayOutputStream payloadBytes = new ByteArrayOutputStream((end - start) * 96);
            try (DataOutputStream payload = new DataOutputStream(payloadBytes)) {
                payload.writeUTF(REMOVE_BATCH_PREFIX);
                payload.writeInt(end - start);
                for (int index = start; index < end; index++) {
                    String keyPrefix = keyPrefixes.get(index);
                    if (keyPrefix == null || keyPrefix.isBlank()) {
                        throw new IOException("Invalid persistent cache index removal");
                    }
                    payload.writeUTF("entry");
                    payload.writeUTF(keyPrefix);
                }
            }
            appendWalRecord(payloadBytes.toByteArray());
        }
    }

    void checkpoint(Properties index) throws IOException {
        Files.createDirectories(root);
        Path temporaryIndex = temporaryPath("index", ".properties.gz.tmp");
        boolean completed = false;
        try {
            writeIndex(temporaryIndex, index);
            Path currentIndex = indexPath();
            if (Files.isRegularFile(currentIndex)) {
                moveReplacing(currentIndex, backupIndexPath());
            }
            moveReplacing(temporaryIndex, currentIndex);
            completed = true;
            try (FileChannel wal = FileChannel.open(
                    walPath(),
                    StandardOpenOption.CREATE,
                    StandardOpenOption.WRITE,
                    StandardOpenOption.TRUNCATE_EXISTING
            )) {
                wal.force(true);
            }
        } finally {
            if (!completed) {
                Files.deleteIfExists(temporaryIndex);
            }
        }
    }

    void writeBlob(String hash, byte[] packetBytes) throws IOException {
        String normalizedHash = normalizeHash(hash);
        if (normalizedHash.isBlank()
                || packetBytes == null
                || packetBytes.length == 0
                || packetBytes.length > MAX_BLOB_BYTES
                || !normalizedHash.equals(sha256(packetBytes))) {
            throw new IOException("Invalid persistent chunk blob");
        }
        if (blobs.containsKey(normalizedHash)) {
            return;
        }

        byte[] compressedBytes = compress(packetBytes);
        CRC32 crc32 = new CRC32();
        crc32.update(packetBytes);
        int recordBytes = SEGMENT_HEADER_BYTES + compressedBytes.length;
        Path segment = selectActiveSegment(recordBytes);
        try (FileChannel channel = FileChannel.open(
                segment,
                StandardOpenOption.CREATE,
                StandardOpenOption.WRITE,
                StandardOpenOption.READ
        )) {
            long offset = channel.size();
            channel.position(offset);
            ByteBuffer header = ByteBuffer.allocate(SEGMENT_HEADER_BYTES);
            header.putInt(SEGMENT_MAGIC);
            header.putInt(SEGMENT_VERSION);
            header.put(HexFormat.of().parseHex(normalizedHash));
            header.putInt(packetBytes.length);
            header.putInt(compressedBytes.length);
            header.putLong(crc32.getValue());
            header.flip();
            writeFully(channel, header);
            writeFully(channel, ByteBuffer.wrap(compressedBytes));
            maybeForce(channel);
            blobs.put(normalizedHash, new BlobLocation(segment, offset, packetBytes.length, compressedBytes.length, crc32.getValue()));
        }
    }

    byte[] readBlob(String hash) throws IOException {
        BlobLocation location = blobs.get(normalizeHash(hash));
        if (location == null || !Files.isRegularFile(location.path())) {
            return null;
        }
        try (FileChannel channel = FileChannel.open(location.path(), StandardOpenOption.READ)) {
            ByteBuffer header = ByteBuffer.allocate(SEGMENT_HEADER_BYTES);
            channel.position(location.offset());
            if (!readFully(channel, header)) {
                throw new IOException("Truncated persistent chunk segment header");
            }
            header.flip();
            int magic = header.getInt();
            int version = header.getInt();
            byte[] hashBytes = new byte[32];
            header.get(hashBytes);
            int rawLength = header.getInt();
            int compressedLength = header.getInt();
            long expectedCrc = header.getLong();
            if (magic != SEGMENT_MAGIC
                    || version != SEGMENT_VERSION
                    || rawLength != location.rawLength()
                    || compressedLength != location.compressedLength()
                    || expectedCrc != location.crc()) {
                throw new IOException("Persistent chunk segment index mismatch");
            }
            ByteBuffer compressed = ByteBuffer.allocate(compressedLength);
            if (!readFully(channel, compressed)) {
                throw new IOException("Truncated persistent chunk segment payload");
            }
            byte[] packetBytes = decompress(compressed.array(), rawLength);
            CRC32 crc32 = new CRC32();
            crc32.update(packetBytes);
            String actualHash = sha256(packetBytes);
            if (crc32.getValue() != expectedCrc || !actualHash.equals(HexFormat.of().formatHex(hashBytes))) {
                throw new IOException("Persistent chunk segment checksum mismatch");
            }
            return packetBytes;
        }
    }

    boolean containsBlob(String hash) {
        return blobs.containsKey(normalizeHash(hash));
    }

    Set<String> availableHashes() {
        return Set.copyOf(blobs.keySet());
    }

    int pruneUnreferencedBlobs(Set<String> referencedHashes, int deleteLimit) {
        Set<String> safeReferenced = protectedHashes(referencedHashes);
        int limit = Math.max(deleteLimit, 0);
        int removed = 0;
        var iterator = blobs.entrySet().iterator();
        while (iterator.hasNext() && (limit == 0 || removed < limit)) {
            if (!safeReferenced.contains(iterator.next().getKey())) {
                iterator.remove();
                removed++;
            }
        }
        return removed;
    }

    long storedBytes(String hash) {
        BlobLocation location = blobs.get(normalizeHash(hash));
        return location == null ? 0L : SEGMENT_HEADER_BYTES + (long) location.compressedLength();
    }

    long totalSegmentBytes() throws IOException {
        long bytes = 0L;
        try (DirectoryStream<Path> paths = Files.newDirectoryStream(segmentsRoot, "segment-*.dat")) {
            for (Path path : paths) {
                if (Files.isRegularFile(path)) {
                    bytes += Files.size(path);
                }
            }
        }
        return bytes;
    }

    CompactionResult compactOneSealedSegment(
            Set<String> referencedHashes,
            double minimumInvalidRatio,
            long bytesPerSecond,
            BooleanSupplier continueCompaction
    ) throws IOException {
        Set<String> protectedHashes = protectedHashes(referencedHashes);
        Path active = segmentPath(activeSegmentId);
        SegmentCandidate candidate = null;
        try (DirectoryStream<Path> paths = Files.newDirectoryStream(segmentsRoot, "segment-*.dat")) {
            for (Path path : paths) {
                if (!Files.isRegularFile(path) || path.equals(active)) {
                    continue;
                }
                long totalBytes = Files.size(path);
                long liveBytes = 0L;
                ArrayList<String> liveHashes = new ArrayList<>();
                for (Map.Entry<String, BlobLocation> entry : blobs.entrySet()) {
                    BlobLocation location = entry.getValue();
                    if (path.equals(location.path()) && protectedHashes.contains(entry.getKey())) {
                        liveBytes += SEGMENT_HEADER_BYTES + (long) location.compressedLength();
                        liveHashes.add(entry.getKey());
                    }
                }
                double invalidRatio = totalBytes <= 0L ? 1.0D : 1.0D - ((double) liveBytes / (double) totalBytes);
                if (liveBytes < totalBytes
                        && invalidRatio >= Math.max(0.0D, Math.min(1.0D, minimumInvalidRatio))
                        && (candidate == null || invalidRatio > candidate.invalidRatio())) {
                    candidate = new SegmentCandidate(path, totalBytes, liveBytes, invalidRatio, List.copyOf(liveHashes));
                }
            }
        }
        if (candidate == null) {
            return CompactionResult.none(totalSegmentBytes());
        }
        if (continueCompaction != null && !continueCompaction.getAsBoolean()) {
            return CompactionResult.cancelled(candidate.path(), candidate.totalBytes(), totalSegmentBytes());
        }
        if (candidate.liveHashes().isEmpty()) {
            Files.deleteIfExists(candidate.path());
            scanSegments();
            blobs.keySet().retainAll(protectedHashes);
            return new CompactionResult(true, false, candidate.path(), candidate.totalBytes(), 0L, totalSegmentBytes());
        }

        Path temporary = temporaryPath("compact-" + candidate.path().getFileName(), ".dat.tmp");
        long writtenBytes = 0L;
        long throttleStartNanos = System.nanoTime();
        boolean completed = false;
        try {
            try (FileChannel output = FileChannel.open(temporary, StandardOpenOption.CREATE_NEW, StandardOpenOption.WRITE)) {
                for (String hash : candidate.liveHashes()) {
                    if (continueCompaction != null && !continueCompaction.getAsBoolean()) {
                        return CompactionResult.cancelled(candidate.path(), candidate.totalBytes(), totalSegmentBytes());
                    }
                    byte[] packetBytes = readBlob(hash);
                    if (packetBytes == null) {
                        throw new IOException("Referenced persistent chunk blob disappeared during compaction");
                    }
                    writtenBytes += writeBlobRecord(output, hash, packetBytes);
                    throttle(writtenBytes, throttleStartNanos, bytesPerSecond, continueCompaction);
                }
                output.force(true);
            }
            verifySegment(temporary);
            if (continueCompaction != null && !continueCompaction.getAsBoolean()) {
                return CompactionResult.cancelled(candidate.path(), candidate.totalBytes(), totalSegmentBytes());
            }
            moveReplacing(temporary, candidate.path());
            completed = true;
        } finally {
            if (!completed) {
                Files.deleteIfExists(temporary);
            }
        }
        scanSegments();
        blobs.keySet().retainAll(protectedHashes);
        return new CompactionResult(true, false, candidate.path(), candidate.totalBytes(), writtenBytes, totalSegmentBytes());
    }

    Path root() {
        return root;
    }

    private Properties loadCheckpoint() throws IOException {
        Path indexPath = indexPath();
        if (Files.isRegularFile(indexPath)) {
            try {
                return readIndex(indexPath);
            } catch (IOException primaryFailure) {
                if (Files.isRegularFile(backupIndexPath())) {
                    return readIndex(backupIndexPath());
                }
                throw primaryFailure;
            }
        }
        return Files.isRegularFile(backupIndexPath()) ? readIndex(backupIndexPath()) : new Properties();
    }

    private void appendWalRecord(byte[] payload) throws IOException {
        if (payload.length == 0 || payload.length > MAX_WAL_RECORD_BYTES) {
            throw new IOException("Invalid persistent cache WAL record");
        }
        CRC32 crc32 = new CRC32();
        crc32.update(payload);
        ByteBuffer record = ByteBuffer.allocate(WAL_HEADER_BYTES + payload.length);
        record.putInt(WAL_MAGIC);
        record.putInt(WAL_VERSION);
        record.putInt(payload.length);
        record.putInt((int) crc32.getValue());
        record.put(payload);
        record.flip();
        try (FileChannel channel = FileChannel.open(
                walPath(),
                StandardOpenOption.CREATE,
                StandardOpenOption.WRITE,
                StandardOpenOption.READ
        )) {
            channel.position(channel.size());
            writeFully(channel, record);
            maybeForce(channel);
        }
    }

    private void replayWal(Properties index) throws IOException {
        Path walPath = walPath();
        if (!Files.isRegularFile(walPath)) {
            return;
        }
        long validBytes = 0L;
        try (FileChannel channel = FileChannel.open(walPath, StandardOpenOption.READ, StandardOpenOption.WRITE)) {
            while (channel.position() < channel.size()) {
                long recordStart = channel.position();
                ByteBuffer header = ByteBuffer.allocate(WAL_HEADER_BYTES);
                if (!readFully(channel, header)) {
                    break;
                }
                header.flip();
                int magic = header.getInt();
                int version = header.getInt();
                int payloadLength = header.getInt();
                long expectedCrc = Integer.toUnsignedLong(header.getInt());
                if (magic != WAL_MAGIC || version != WAL_VERSION || payloadLength <= 0 || payloadLength > MAX_WAL_RECORD_BYTES) {
                    break;
                }
                ByteBuffer payloadBuffer = ByteBuffer.allocate(payloadLength);
                if (!readFully(channel, payloadBuffer)) {
                    break;
                }
                byte[] payloadBytes = payloadBuffer.array();
                CRC32 crc32 = new CRC32();
                crc32.update(payloadBytes);
                if (crc32.getValue() != expectedCrc) {
                    break;
                }
                try {
                    applyWalRecord(index, payloadBytes);
                } catch (IOException invalidRecord) {
                    break;
                }
                validBytes = recordStart + WAL_HEADER_BYTES + payloadLength;
            }
            if (validBytes < channel.size()) {
                channel.truncate(validBytes);
                channel.force(true);
            }
        }
    }

    private void applyWalRecord(Properties index, byte[] payloadBytes) throws IOException {
        try (DataInputStream input = new DataInputStream(new ByteArrayInputStream(payloadBytes))) {
            String keyPrefix = input.readUTF();
            int count = input.readInt();
            int maximumCount = REMOVE_BATCH_PREFIX.equals(keyPrefix) ? REMOVE_BATCH_ENTRIES : 64;
            if (keyPrefix.isBlank() || count < 0 || count > maximumCount) {
                throw new IOException("Invalid persistent cache WAL mutation");
            }
            if (REMOVE_BATCH_PREFIX.equals(keyPrefix)) {
                for (int position = 0; position < count; position++) {
                    if (!"entry".equals(input.readUTF())) {
                        throw new IOException("Invalid persistent cache WAL removal");
                    }
                    removeIndexEntry(index, input.readUTF());
                }
                if (input.read() != -1) {
                    throw new IOException("Persistent cache WAL removal has trailing bytes");
                }
                return;
            }
            if (count == 0) {
                removeIndexEntry(index, keyPrefix);
            }
            for (int position = 0; position < count; position++) {
                String suffix = input.readUTF();
                String value = input.readUTF();
                if (ENTRY_SUFFIXES.contains(suffix)) {
                    index.setProperty(keyPrefix + suffix, value);
                }
            }
            if (input.read() != -1) {
                throw new IOException("Persistent cache WAL mutation has trailing bytes");
            }
        }
    }

    private void scanSegments() throws IOException {
        blobs.clear();
        List<Path> segments = new ArrayList<>();
        try (DirectoryStream<Path> paths = Files.newDirectoryStream(segmentsRoot, "segment-*.dat")) {
            for (Path path : paths) {
                if (Files.isRegularFile(path)) {
                    segments.add(path);
                }
            }
        }
        segments.sort(Comparator.comparing(Path::getFileName));
        for (int index = 0; index < segments.size(); index++) {
            Path segment = segments.get(index);
            activeSegmentId = Math.max(activeSegmentId, parseSegmentId(segment));
            scanSegment(segment, index == segments.size() - 1);
        }
        if (activeSegmentId == 0) {
            activeSegmentId = 1;
        }
    }

    private void scanSegment(Path segment, boolean repairTail) throws IOException {
        long validBytes = 0L;
        try (FileChannel channel = FileChannel.open(segment, StandardOpenOption.READ, StandardOpenOption.WRITE)) {
            while (channel.position() < channel.size()) {
                long recordStart = channel.position();
                ByteBuffer header = ByteBuffer.allocate(SEGMENT_HEADER_BYTES);
                if (!readFully(channel, header)) {
                    break;
                }
                header.flip();
                int magic = header.getInt();
                int version = header.getInt();
                byte[] hashBytes = new byte[32];
                header.get(hashBytes);
                int rawLength = header.getInt();
                int compressedLength = header.getInt();
                long crc = header.getLong();
                if (magic != SEGMENT_MAGIC
                        || version != SEGMENT_VERSION
                        || rawLength <= 0
                        || rawLength > MAX_BLOB_BYTES
                        || compressedLength <= 0
                        || compressedLength > MAX_BLOB_BYTES
                        || channel.size() - channel.position() < compressedLength) {
                    break;
                }
                String hash = HexFormat.of().formatHex(hashBytes);
                blobs.put(hash, new BlobLocation(segment, recordStart, rawLength, compressedLength, crc));
                channel.position(channel.position() + compressedLength);
                validBytes = channel.position();
            }
            if (repairTail && validBytes < channel.size()) {
                channel.truncate(validBytes);
                channel.force(true);
            }
        }
    }

    private Path selectActiveSegment(int recordBytes) throws IOException {
        Path active = segmentPath(activeSegmentId);
        if (Files.isRegularFile(active) && Files.size(active) + recordBytes > maxSegmentBytes) {
            activeSegmentId++;
            active = segmentPath(activeSegmentId);
        }
        return active;
    }

    private Properties readIndex(Path path) throws IOException {
        Properties properties = new Properties();
        try (InputStream input = new GZIPInputStream(Files.newInputStream(path))) {
            properties.load(input);
        }
        return properties;
    }

    private void writeIndex(Path path, Properties index) throws IOException {
        Properties safeIndex = new Properties();
        if (index != null) {
            safeIndex.putAll(index);
        }
        try (OutputStream fileOutput = Files.newOutputStream(path, StandardOpenOption.CREATE_NEW, StandardOpenOption.WRITE);
             GZIPOutputStream gzipOutput = new GZIPOutputStream(fileOutput)) {
            safeIndex.store(gzipOutput, "BandwidthOptimizer persistent client chunk cache v2");
            gzipOutput.finish();
        }
        try (FileChannel channel = FileChannel.open(path, StandardOpenOption.WRITE)) {
            channel.force(true);
        }
    }

    private byte[] compress(byte[] packetBytes) throws IOException {
        ByteArrayOutputStream output = new ByteArrayOutputStream(Math.max(packetBytes.length / 4, 256));
        Deflater deflater = new Deflater(compressionLevel, true);
        try (DeflaterOutputStream deflaterOutput = new DeflaterOutputStream(output, deflater, 8192)) {
            deflaterOutput.write(packetBytes);
            deflaterOutput.finish();
        } finally {
            deflater.end();
        }
        return output.toByteArray();
    }

    private byte[] decompress(byte[] compressedBytes, int expectedLength) throws IOException {
        Inflater inflater = new Inflater(true);
        try (InflaterInputStream inflaterInput = new InflaterInputStream(new ByteArrayInputStream(compressedBytes), inflater, 8192);
             ByteArrayOutputStream output = new ByteArrayOutputStream(expectedLength)) {
            inflaterInput.transferTo(output);
            byte[] packetBytes = output.toByteArray();
            if (packetBytes.length != expectedLength) {
                throw new IOException("Persistent chunk segment length mismatch");
            }
            return packetBytes;
        } finally {
            inflater.end();
        }
    }

    private void cleanupTemporaryFiles() throws IOException {
        try (DirectoryStream<Path> files = Files.newDirectoryStream(tempRoot)) {
            for (Path file : files) {
                if (Files.isRegularFile(file)) {
                    Files.deleteIfExists(file);
                }
            }
        }
    }

    private Set<String> protectedHashes(Set<String> referencedHashes) {
        HashSet<String> protectedHashes = new HashSet<>(normalizeHashes(referencedHashes));
        try {
            Properties backup = Files.isRegularFile(backupIndexPath()) ? readIndex(backupIndexPath()) : new Properties();
            for (String key : backup.stringPropertyNames()) {
                if (key.endsWith(".hash")) {
                    String hash = normalizeHash(backup.getProperty(key, ""));
                    if (!hash.isBlank()) {
                        protectedHashes.add(hash);
                    }
                }
            }
        } catch (IOException ignored) {
            protectedHashes.addAll(blobs.keySet());
        }
        return protectedHashes;
    }

    private static Set<String> normalizeHashes(Set<String> hashes) {
        HashSet<String> normalized = new HashSet<>();
        if (hashes != null) {
            for (String hash : hashes) {
                String safeHash = normalizeHash(hash);
                if (!safeHash.isBlank()) {
                    normalized.add(safeHash);
                }
            }
        }
        return normalized;
    }

    private int writeBlobRecord(FileChannel channel, String hash, byte[] packetBytes) throws IOException {
        byte[] compressedBytes = compress(packetBytes);
        CRC32 crc32 = new CRC32();
        crc32.update(packetBytes);
        ByteBuffer header = ByteBuffer.allocate(SEGMENT_HEADER_BYTES);
        header.putInt(SEGMENT_MAGIC);
        header.putInt(SEGMENT_VERSION);
        header.put(HexFormat.of().parseHex(hash));
        header.putInt(packetBytes.length);
        header.putInt(compressedBytes.length);
        header.putLong(crc32.getValue());
        header.flip();
        writeFully(channel, header);
        writeFully(channel, ByteBuffer.wrap(compressedBytes));
        return SEGMENT_HEADER_BYTES + compressedBytes.length;
    }

    private void verifySegment(Path segment) throws IOException {
        try (FileChannel channel = FileChannel.open(segment, StandardOpenOption.READ)) {
            while (channel.position() < channel.size()) {
                ByteBuffer header = ByteBuffer.allocate(SEGMENT_HEADER_BYTES);
                if (!readFully(channel, header)) {
                    throw new IOException("Truncated compacted persistent chunk header");
                }
                header.flip();
                if (header.getInt() != SEGMENT_MAGIC || header.getInt() != SEGMENT_VERSION) {
                    throw new IOException("Invalid compacted persistent chunk header");
                }
                byte[] hashBytes = new byte[32];
                header.get(hashBytes);
                int rawLength = header.getInt();
                int compressedLength = header.getInt();
                long expectedCrc = header.getLong();
                if (rawLength <= 0 || rawLength > MAX_BLOB_BYTES || compressedLength <= 0 || compressedLength > MAX_BLOB_BYTES) {
                    throw new IOException("Invalid compacted persistent chunk length");
                }
                ByteBuffer compressed = ByteBuffer.allocate(compressedLength);
                if (!readFully(channel, compressed)) {
                    throw new IOException("Truncated compacted persistent chunk payload");
                }
                byte[] packetBytes = decompress(compressed.array(), rawLength);
                CRC32 crc32 = new CRC32();
                crc32.update(packetBytes);
                if (crc32.getValue() != expectedCrc
                        || !sha256(packetBytes).equals(HexFormat.of().formatHex(hashBytes))) {
                    throw new IOException("Invalid compacted persistent chunk checksum");
                }
            }
        }
    }

    void rewriteBlob(String hash, byte[] packetBytes) throws IOException {
        String normalizedHash = normalizeHash(hash);
        BlobLocation previous = blobs.remove(normalizedHash);
        try {
            writeBlob(normalizedHash, packetBytes);
            BlobLocation replacement = blobs.get(normalizedHash);
            if (replacement == null) {
                throw new IOException("Persistent chunk blob rewrite produced no location");
            }
            try (FileChannel channel = FileChannel.open(replacement.path(), StandardOpenOption.WRITE)) {
                channel.force(true);
            }
        } catch (IOException | RuntimeException exception) {
            if (previous != null) {
                blobs.put(normalizedHash, previous);
            }
            throw exception;
        }
    }

    private static void throttle(long completedBytes, long startedNanos, long bytesPerSecond, BooleanSupplier continueCompaction) {
        if (bytesPerSecond <= 0L) {
            return;
        }
        long expectedNanos = (long) (((double) completedBytes / (double) bytesPerSecond) * 1_000_000_000D);
        while (true) {
            long remaining = expectedNanos - (System.nanoTime() - startedNanos);
            if (remaining <= 0L || (continueCompaction != null && !continueCompaction.getAsBoolean())) {
                return;
            }
            LockSupport.parkNanos(Math.min(remaining, 100_000_000L));
        }
    }

    private static void removeIndexEntry(Properties index, String keyPrefix) {
        if (index == null || keyPrefix == null || keyPrefix.isBlank()) {
            return;
        }
        for (String suffix : ENTRY_SUFFIXES) {
            index.remove(keyPrefix + suffix);
        }
    }

    private void maybeForce(FileChannel channel) throws IOException {
        long now = System.nanoTime();
        if (lastForceNanos == 0L || now - lastForceNanos >= FORCE_INTERVAL_NANOS) {
            channel.force(false);
            lastForceNanos = now;
        }
    }

    private Path segmentPath(int id) {
        return segmentsRoot.resolve(String.format(Locale.ROOT, "segment-%08d.dat", Math.max(id, 1)));
    }

    private int parseSegmentId(Path path) {
        String fileName = path.getFileName().toString();
        try {
            return Integer.parseInt(fileName.substring("segment-".length(), fileName.length() - ".dat".length()));
        } catch (RuntimeException ignored) {
            return 0;
        }
    }

    private Path temporaryPath(String prefix, String suffix) {
        return tempRoot.resolve(prefix + "-" + UUID.randomUUID() + suffix);
    }

    private Path indexPath() {
        return root.resolve(INDEX_FILE_NAME);
    }

    private Path backupIndexPath() {
        return root.resolve(BACKUP_INDEX_FILE_NAME);
    }

    private Path walPath() {
        return root.resolve(WAL_FILE_NAME);
    }

    private static String normalizeHash(String hash) {
        return hash != null && hash.matches("[0-9a-fA-F]{64}") ? hash.toLowerCase(Locale.ROOT) : "";
    }

    private static String sha256(byte[] bytes) throws IOException {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));
        } catch (NoSuchAlgorithmException exception) {
            throw new IOException("SHA-256 is unavailable", exception);
        }
    }

    private static boolean readFully(FileChannel channel, ByteBuffer buffer) throws IOException {
        while (buffer.hasRemaining()) {
            if (channel.read(buffer) < 0) {
                return false;
            }
        }
        return true;
    }

    private static void writeFully(FileChannel channel, ByteBuffer buffer) throws IOException {
        while (buffer.hasRemaining()) {
            channel.write(buffer);
        }
    }

    private static void moveReplacing(Path source, Path target) throws IOException {
        try {
            Files.move(source, target, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
        } catch (IOException atomicMoveFailure) {
            Files.move(source, target, StandardCopyOption.REPLACE_EXISTING);
        }
    }

    private record BlobLocation(Path path, long offset, int rawLength, int compressedLength, long crc) {
    }

    private record SegmentCandidate(
            Path path,
            long totalBytes,
            long liveBytes,
            double invalidRatio,
            List<String> liveHashes
    ) {
    }

    record CompactionResult(
            boolean compacted,
            boolean cancelled,
            Path segment,
            long beforeBytes,
            long afterBytes,
            long totalSegmentBytes
    ) {
        private static CompactionResult none(long totalSegmentBytes) {
            return new CompactionResult(false, false, null, 0L, 0L, totalSegmentBytes);
        }

        private static CompactionResult cancelled(Path segment, long beforeBytes, long totalSegmentBytes) {
            return new CompactionResult(false, true, segment, beforeBytes, beforeBytes, totalSegmentBytes);
        }
    }
}
