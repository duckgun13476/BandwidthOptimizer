package com.PinkCats.bandwidthoptimizer.chunk.persistent;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.Properties;
import java.util.Set;

public final class PersistentChunkCacheDiskStoreRegressionMain {

    private PersistentChunkCacheDiskStoreRegressionMain() {}

    public static void main(String[] args) throws Exception {
        Path root = Files.createTempDirectory("bo-persistent-cache-store-");
        try {
            verifyRoundTripAndCheckpoint(root);
            verifyBackupFallback(root);
            verifyTemporaryCleanup(root);
            verifyUnreferencedPrune(root);
            System.out.println("Persistent chunk cache disk store regression passed");
        } finally {
            deleteTree(root);
        }
    }

    private static void verifyRoundTripAndCheckpoint(Path root) throws Exception {
        PersistentChunkCacheDiskStore store = PersistentChunkCacheDiskStore.open(root, 2);
        byte[] packetBytes = repeatedPayload(128 * 1024, 17);
        String hash = "11".repeat(32);
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

    private static void verifyUnreferencedPrune(Path root) throws Exception {
        PersistentChunkCacheDiskStore store = PersistentChunkCacheDiskStore.open(root, 2);
        String retainedHash = "22".repeat(32);
        String staleHash = "33".repeat(32);
        byte[] packetBytes = repeatedPayload(32 * 1024, 29);
        store.writeBlob(retainedHash, packetBytes);
        store.writeBlob(staleHash, packetBytes);
        String indexedHash = store.loadIndex().getProperty("scope.test.chunk.1.2.hash");
        int deleted = store.pruneUnreferencedBlobs(Set.of(indexedHash, retainedHash), 16);
        require(deleted == 1, "unexpected prune count: " + deleted);
        require(store.containsBlob(retainedHash), "referenced blob was pruned");
        require(!store.containsBlob(staleHash), "unreferenced blob survived prune");
    }

    private static byte[] repeatedPayload(int length, int seed) {
        byte[] bytes = new byte[length];
        for (int index = 0; index < bytes.length; index++) {
            bytes[index] = (byte) ((index * 31 + seed) & 0x7f);
        }
        return bytes;
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
