package com.PinkCats.bandwidthoptimizer.chunk.persistent;

import java.io.BufferedInputStream;
import java.io.BufferedOutputStream;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.util.HashSet;
import java.util.Locale;
import java.util.Properties;
import java.util.Set;
import java.util.UUID;
import java.util.zip.CRC32;
import java.util.zip.Deflater;
import java.util.zip.DeflaterOutputStream;
import java.util.zip.GZIPInputStream;
import java.util.zip.GZIPOutputStream;
import java.util.zip.Inflater;
import java.util.zip.InflaterInputStream;

final class PersistentChunkCacheDiskStore {

    private static final int BLOB_MAGIC = 0x424F4332;
    private static final int BLOB_VERSION = 1;
    private static final int MAX_BLOB_BYTES = 64 * 1024 * 1024;
    private static final String INDEX_FILE_NAME = "index.properties.gz";
    private static final String BACKUP_INDEX_FILE_NAME = "index.backup.properties.gz";
    private static final String BLOBS_DIRECTORY_NAME = "blobs";
    private static final String TEMP_DIRECTORY_NAME = "tmp";

    private final Path root;
    private final Path blobsRoot;
    private final Path tempRoot;
    private final int compressionLevel;

    private PersistentChunkCacheDiskStore(Path root, int compressionLevel) {
        this.root = root;
        this.blobsRoot = root.resolve(BLOBS_DIRECTORY_NAME);
        this.tempRoot = root.resolve(TEMP_DIRECTORY_NAME);
        this.compressionLevel = Math.max(Deflater.NO_COMPRESSION, Math.min(Deflater.BEST_COMPRESSION, compressionLevel));
    }

    static PersistentChunkCacheDiskStore open(Path root, int compressionLevel) throws IOException {
        PersistentChunkCacheDiskStore store = new PersistentChunkCacheDiskStore(root, compressionLevel);
        Files.createDirectories(store.blobsRoot);
        Files.createDirectories(store.tempRoot);
        store.cleanupTemporaryFiles();
        return store;
    }

    Properties loadIndex() throws IOException {
        Path indexPath = indexPath();
        if (Files.isRegularFile(indexPath)) {
            try {
                return readIndex(indexPath);
            } catch (IOException primaryFailure) {
                Path backupPath = backupIndexPath();
                if (Files.isRegularFile(backupPath)) {
                    return readIndex(backupPath);
                }
                throw primaryFailure;
            }
        }
        Path backupPath = backupIndexPath();
        return Files.isRegularFile(backupPath) ? readIndex(backupPath) : new Properties();
    }

    void checkpoint(Properties index) throws IOException {
        Files.createDirectories(root);
        Files.createDirectories(tempRoot);
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
        } finally {
            if (!completed) {
                Files.deleteIfExists(temporaryIndex);
            }
        }
    }

    void writeBlob(String hash, byte[] packetBytes) throws IOException {
        if (!isSafeHash(hash) || packetBytes == null || packetBytes.length == 0 || packetBytes.length > MAX_BLOB_BYTES) {
            throw new IOException("Invalid persistent chunk blob");
        }
        Path target = blobPath(hash);
        if (Files.isRegularFile(target)) {
            return;
        }

        Files.createDirectories(target.getParent());
        Files.createDirectories(tempRoot);
        Path temporaryBlob = temporaryPath(hash.substring(0, 12), ".blob.tmp");
        boolean completed = false;
        try {
            byte[] compressedBytes = compress(packetBytes);
            CRC32 crc32 = new CRC32();
            crc32.update(packetBytes);
            try (OutputStream fileOutput = new BufferedOutputStream(Files.newOutputStream(
                    temporaryBlob,
                    StandardOpenOption.CREATE_NEW,
                    StandardOpenOption.WRITE
            )); DataOutputStream output = new DataOutputStream(fileOutput)) {
                output.writeInt(BLOB_MAGIC);
                output.writeInt(BLOB_VERSION);
                output.writeInt(packetBytes.length);
                output.writeInt(compressedBytes.length);
                output.writeLong(crc32.getValue());
                output.write(compressedBytes);
            }
            if (Files.isRegularFile(target)) {
                Files.deleteIfExists(temporaryBlob);
            } else {
                moveReplacing(temporaryBlob, target);
            }
            completed = true;
        } finally {
            if (!completed) {
                Files.deleteIfExists(temporaryBlob);
            }
        }
    }

    byte[] readBlob(String hash) throws IOException {
        if (!isSafeHash(hash)) {
            return null;
        }
        Path path = blobPath(hash);
        if (!Files.isRegularFile(path)) {
            return null;
        }
        try (InputStream fileInput = new BufferedInputStream(Files.newInputStream(path));
             DataInputStream input = new DataInputStream(fileInput)) {
            int magic = input.readInt();
            int version = input.readInt();
            int rawLength = input.readInt();
            int compressedLength = input.readInt();
            long expectedCrc = input.readLong();
            if (magic != BLOB_MAGIC
                    || version != BLOB_VERSION
                    || rawLength <= 0
                    || rawLength > MAX_BLOB_BYTES
                    || compressedLength <= 0
                    || compressedLength > MAX_BLOB_BYTES) {
                throw new IOException("Invalid persistent chunk blob header");
            }
            byte[] compressedBytes = input.readNBytes(compressedLength);
            if (compressedBytes.length != compressedLength || input.read() != -1) {
                throw new IOException("Truncated persistent chunk blob");
            }
            byte[] packetBytes = decompress(compressedBytes, rawLength);
            CRC32 crc32 = new CRC32();
            crc32.update(packetBytes);
            if (crc32.getValue() != expectedCrc) {
                throw new IOException("Persistent chunk blob checksum mismatch");
            }
            return packetBytes;
        }
    }

    boolean containsBlob(String hash) {
        return isSafeHash(hash) && Files.isRegularFile(blobPath(hash));
    }

    int pruneUnreferencedBlobs(Set<String> referencedHashes, int deleteLimit) throws IOException {
        if (deleteLimit <= 0 || !Files.isDirectory(blobsRoot)) {
            return 0;
        }
        Set<String> safeReferencedHashes = referencedHashes == null ? Set.of() : new HashSet<>(referencedHashes);
        int deleted = 0;
        try (DirectoryStream<Path> shards = Files.newDirectoryStream(blobsRoot)) {
            for (Path shard : shards) {
                if (!Files.isDirectory(shard)) {
                    continue;
                }
                try (DirectoryStream<Path> blobs = Files.newDirectoryStream(shard, "*.blob")) {
                    for (Path blob : blobs) {
                        String fileName = blob.getFileName().toString();
                        String hash = fileName.substring(0, fileName.length() - ".blob".length());
                        if (!safeReferencedHashes.contains(hash) && Files.deleteIfExists(blob)) {
                            deleted++;
                            if (deleted >= deleteLimit) {
                                return deleted;
                            }
                        }
                    }
                }
            }
        }
        return deleted;
    }

    Path root() {
        return root;
    }

    private Properties readIndex(Path path) throws IOException {
        Properties properties = new Properties();
        try (InputStream input = new GZIPInputStream(new BufferedInputStream(Files.newInputStream(path)))) {
            properties.load(input);
        }
        return properties;
    }

    private void writeIndex(Path path, Properties index) throws IOException {
        Properties safeIndex = new Properties();
        if (index != null) {
            safeIndex.putAll(index);
        }
        try (OutputStream fileOutput = new BufferedOutputStream(Files.newOutputStream(
                path,
                StandardOpenOption.CREATE_NEW,
                StandardOpenOption.WRITE
        )); GZIPOutputStream gzipOutput = new GZIPOutputStream(fileOutput)) {
            safeIndex.store(gzipOutput, "BandwidthOptimizer persistent client chunk cache v2");
            gzipOutput.finish();
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
        try (InflaterInputStream inflaterInput = new InflaterInputStream(
                new ByteArrayInputStream(compressedBytes),
                inflater,
                8192
        ); ByteArrayOutputStream output = new ByteArrayOutputStream(expectedLength)) {
            inflaterInput.transferTo(output);
            byte[] packetBytes = output.toByteArray();
            if (packetBytes.length != expectedLength) {
                throw new IOException("Persistent chunk blob length mismatch");
            }
            return packetBytes;
        } finally {
            inflater.end();
        }
    }

    private void cleanupTemporaryFiles() throws IOException {
        if (!Files.isDirectory(tempRoot)) {
            return;
        }
        try (DirectoryStream<Path> files = Files.newDirectoryStream(tempRoot)) {
            for (Path file : files) {
                if (Files.isRegularFile(file)) {
                    Files.deleteIfExists(file);
                }
            }
        }
    }

    private Path blobPath(String hash) {
        String normalizedHash = hash.toLowerCase(Locale.ROOT);
        return blobsRoot.resolve(normalizedHash.substring(0, 2)).resolve(normalizedHash + ".blob");
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

    private static boolean isSafeHash(String hash) {
        return hash != null && hash.matches("[0-9a-fA-F]{64}");
    }

    private static void moveReplacing(Path source, Path target) throws IOException {
        try {
            Files.move(source, target, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
        } catch (IOException atomicMoveFailure) {
            Files.move(source, target, StandardCopyOption.REPLACE_EXISTING);
        }
    }
}
