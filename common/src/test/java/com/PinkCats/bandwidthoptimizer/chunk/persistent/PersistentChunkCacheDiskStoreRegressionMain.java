package com.PinkCats.bandwidthoptimizer.chunk.persistent;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.util.Arrays;
import java.util.HexFormat;
import java.util.Properties;

public final class PersistentChunkCacheDiskStoreRegressionMain {

    private PersistentChunkCacheDiskStoreRegressionMain() {}

    public static void main(String[] args) throws Exception {
        Path root = Files.createTempDirectory("bo-persistent-cache-store-");
        try {
            verifyRoundTripAndCheckpoint(root);
            verifyBackupFallback(root);
            verifyTemporaryCleanup(root);
            verifyWalReplayAndTailRepair(root);
            verifySegmentTailRepair(root);
            System.out.println("Persistent chunk cache disk store regression passed");
        } finally {
            deleteTree(root);
        }
    }

    private static void verifyRoundTripAndCheckpoint(Path root) throws Exception {
        PersistentChunkCacheDiskStore store = PersistentChunkCacheDiskStore.open(root, 2);
        byte[] packetBytes = repeatedPayload(128 * 1024, 17);
        String hash = sha256(packetBytes);
        store.writeBlob(hash, packetBytes);
        require(Arrays.equals(packetBytes, store.readBlob(hash)), "blob round trip failed");

        Properties index = new Properties();
        index.setProperty("scope.test.chunk.1.2.hash", hash);
        index.setProperty("scope.test.chunk.1.2.encodedBytes", Integer.toString(packetBytes.length));
        store.checkpoint(index);
        Properties loaded = store.loadIndex();
        require(hash.equals(loaded.getProperty("scope.test.chunk.1.2.hash")), "index checkpoint failed");
    }

    private static void verifyBackupFallback(Path root) throws Exception {
        PersistentChunkCacheDiskStore store = PersistentChunkCacheDiskStore.open(root, 2);
        Properties second = store.loadIndex();
        second.setProperty("generation", "2");
        store.checkpoint(second);
        Files.write(root.resolve("index.properties.gz"), new byte[]{1, 2, 3, 4});
        Properties recovered = store.loadIndex();
        require(recovered.getProperty("scope.test.chunk.1.2.hash") != null, "backup index fallback failed");
    }

    private static void verifyTemporaryCleanup(Path root) throws Exception {
        Path temporaryRoot = root.resolve("tmp");
        Files.createDirectories(temporaryRoot);
        Path stale = temporaryRoot.resolve("stale.blob.tmp");
        Files.write(stale, new byte[]{9});
        PersistentChunkCacheDiskStore.open(root, 2);
        require(!Files.exists(stale), "stale temporary file was not cleaned");
    }

    private static void verifyWalReplayAndTailRepair(Path root) throws Exception {
        PersistentChunkCacheDiskStore store = PersistentChunkCacheDiskStore.open(root, 2);
        byte[] packetBytes = repeatedPayload(32 * 1024, 29);
        String hash = sha256(packetBytes);
        store.writeBlob(hash, packetBytes);
        String prefix = "scope.test.chunk.3.4.";
        Properties mutation = new Properties();
        mutation.setProperty(prefix + "hash", hash);
        mutation.setProperty(prefix + "encodedBytes", Integer.toString(packetBytes.length));
        store.appendIndexEntry(prefix, mutation);
        Files.write(root.resolve("index.wal"), new byte[]{9, 8, 7}, java.nio.file.StandardOpenOption.APPEND);

        PersistentChunkCacheDiskStore reopened = PersistentChunkCacheDiskStore.open(root, 2);
        Properties replayed = reopened.loadIndex();
        require(hash.equals(replayed.getProperty(prefix + "hash")), "WAL replay failed");
        require(Files.size(root.resolve("index.wal")) > 3L, "WAL tail repair removed valid records");
    }

    private static void verifySegmentTailRepair(Path root) throws Exception {
        Path segment;
        try (var paths = Files.list(root.resolve("segments"))) {
            segment = paths.filter(Files::isRegularFile).findFirst().orElseThrow();
        }
        long validLength = Files.size(segment);
        Files.write(segment, new byte[]{1, 2, 3, 4, 5}, java.nio.file.StandardOpenOption.APPEND);
        PersistentChunkCacheDiskStore reopened = PersistentChunkCacheDiskStore.open(root, 2);
        require(Files.size(segment) == validLength, "segment tail was not repaired");
        String hash = reopened.loadIndex().getProperty("scope.test.chunk.1.2.hash");
        require(Arrays.equals(reopened.readBlob(hash), repeatedPayload(128 * 1024, 17)), "segment recovery changed a valid blob");
    }

    private static byte[] repeatedPayload(int length, int seed) {
        byte[] bytes = new byte[length];
        for (int index = 0; index < bytes.length; index++) {
            bytes[index] = (byte) ((index * 31 + seed) & 0x7f);
        }
        return bytes;
    }

    private static String sha256(byte[] bytes) throws Exception {
        return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));
    }

    private static void require(boolean condition, String message) {
        if (!condition) {
            throw new IllegalStateException(message);
        }
    }

    private static void deleteTree(Path root) throws IOException {
        if (!Files.exists(root)) {
            return;
        }
        try (var paths = Files.walk(root)) {
            paths.sorted((left, right) -> right.compareTo(left)).forEach(path -> {
                try {
                    Files.deleteIfExists(path);
                } catch (IOException ignored) {
                }
            });
        }
    }
}
