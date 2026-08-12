package com.PinkCats.bandwidthoptimizer.chunk.persistent;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.util.Arrays;
import java.util.HexFormat;
import java.util.List;
import java.util.Properties;
import java.util.Set;
import java.util.Random;
import java.util.ArrayDeque;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

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
            verifyWalRemovalReplay(root.resolve("wal-removal"));
            verifyRetentionPolicy();
            verifySealedSegmentCompaction(root.resolve("compaction"));
            verifyCapacityCompactionBelowNormalThreshold(root.resolve("capacity-compaction"));
            verifyMaintenanceCoordinator();
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

    private static void verifyMaintenanceCoordinator() {
        ArrayDeque<Runnable> scheduled = new ArrayDeque<>();
        AtomicBoolean windowOpen = new AtomicBoolean(true);
        AtomicInteger maintenanceRuns = new AtomicInteger();
        AtomicReference<Runnable> requestDuringRun = new AtomicReference<>();
        PersistentCacheMaintenanceCoordinator coordinator = new PersistentCacheMaintenanceCoordinator(
                scheduled::addLast,
                windowOpen::get,
                ignored -> {
                    maintenanceRuns.incrementAndGet();
                    if (maintenanceRuns.get() == 1) {
                        requestDuringRun.get().run();
                    }
                }
        );
        requestDuringRun.set(() -> coordinator.request("second-disconnect"));

        coordinator.request("first-disconnect");
        require(scheduled.size() == 1, "maintenance coordinator queued duplicate runners");
        scheduled.removeFirst().run();
        require(maintenanceRuns.get() == 2, "maintenance coordinator lost a request during maintenance");

        windowOpen.set(false);
        coordinator.request("reconnected");
        scheduled.removeFirst().run();
        require(maintenanceRuns.get() == 2, "maintenance coordinator ran while connected");
    }

    private static void verifyWalRemovalReplay(Path root) throws Exception {
        PersistentChunkCacheDiskStore store = PersistentChunkCacheDiskStore.open(root, 2);
        byte[] packetBytes = repeatedPayload(16 * 1024, 41);
        String hash = sha256(packetBytes);
        store.writeBlob(hash, packetBytes);
        Properties index = new Properties();
        String prefix = "scope.test.chunk.8.9.";
        index.setProperty(prefix + "hash", hash);
        index.setProperty(prefix + "serverScopeHash", "test");
        store.checkpoint(index);
        store.appendIndexRemovals(List.of(prefix));

        PersistentChunkCacheDiskStore reopened = PersistentChunkCacheDiskStore.open(root, 2);
        require(!reopened.loadIndex().containsKey(prefix + "hash"), "WAL removal replay failed");
    }

    private static void verifyRetentionPolicy() {
        Properties index = new Properties();
        addRetentionEntry(index, "scope-a", 0, 1_000L, 0L, 0L, "a");
        addRetentionEntry(index, "scope-a", 1, 2_000L, 0L, 0L, "b");
        addRetentionEntry(index, "scope-a", 2, 3_000L, 10L, 1_000_000L, "c");
        addRetentionEntry(index, "scope-b", 0, 4_000L, 0L, 0L, "d");
        addRetentionEntry(index, "scope-b", 1, 5_000L, 0L, 0L, "e");
        addRetentionEntry(index, "scope-b", 2, 6_000L, 20L, 2_000_000L, "f");

        PersistentChunkCacheRetentionPolicy.RetentionPlan plan = PersistentChunkCacheRetentionPolicy.plan(
                index,
                ignored -> 100L,
                new PersistentChunkCacheRetentionPolicy.RetentionLimits(10_000L, 4, 1)
        );
        require(plan.evictedKeyPrefixes().size() == 2, "retention entry limit was not enforced");
        require(plan.referencedHashes().size() == 4, "retention hashes did not match retained entries");
        require(plan.temperatures().size() == 6, "retention temperatures were not assigned");
        require(plan.evictedKeyPrefixes().stream().noneMatch(prefix -> prefix.contains(".chunk.2.")),
                "retention evicted a hotter entry before a cold entry");
    }

    private static void verifySealedSegmentCompaction(Path root) throws Exception {
        PersistentChunkCacheDiskStore store = PersistentChunkCacheDiskStore.openForTesting(root, 1, 220L * 1024L);
        String[] hashes = new String[6];
        byte[][] payloads = new byte[6][];
        for (int index = 0; index < payloads.length; index++) {
            payloads[index] = randomPayload(64 * 1024, 100 + index);
            hashes[index] = sha256(payloads[index]);
            store.writeBlob(hashes[index], payloads[index]);
        }
        Set<String> referenced = Set.of(hashes[0], hashes[3], hashes[4], hashes[5]);
        Properties fullIndex = indexForHashes(hashes);
        store.checkpoint(fullIndex);
        Properties retainedIndex = indexForHashes(hashes[0], hashes[3], hashes[4], hashes[5]);
        store.checkpoint(retainedIndex);
        store.pruneUnreferencedBlobs(referenced, 0);
        PersistentChunkCacheDiskStore.CompactionResult protectedByBackup = store.compactOneSealedSegment(
                referenced,
                0.60D,
                0L,
                () -> true
        );
        require(!protectedByBackup.compacted(), "compaction removed a blob still referenced by the backup index");
        require(store.availableHashes().contains(hashes[1]), "backup-protected blob was removed");

        store.checkpoint(retainedIndex);
        store.pruneUnreferencedBlobs(referenced, 0);
        long before = store.totalSegmentBytes();
        AtomicInteger continuationChecks = new AtomicInteger();
        PersistentChunkCacheDiskStore.CompactionResult cancelled = store.compactOneSealedSegment(
                referenced,
                0.60D,
                0L,
                () -> continuationChecks.incrementAndGet() == 1
        );
        require(cancelled.cancelled(), "compaction did not honor cancellation");
        require(store.totalSegmentBytes() == before, "cancelled compaction changed segment bytes");
        PersistentChunkCacheDiskStore.CompactionResult result = store.compactOneSealedSegment(
                referenced,
                0.60D,
                0L,
                () -> true
        );
        require(result.compacted(), "sealed segment compaction did not run");
        require(store.totalSegmentBytes() < before, "sealed segment compaction did not reclaim bytes");
        require(Arrays.equals(payloads[0], store.readBlob(hashes[0])), "compaction changed a live blob");
        require(!store.availableHashes().contains(hashes[1]), "compaction retained an unreferenced blob");
        try (var temporaryFiles = Files.list(root.resolve("tmp"))) {
            require(temporaryFiles.findAny().isEmpty(), "compaction left a temporary file");
        }
    }

    private static void verifyCapacityCompactionBelowNormalThreshold(Path root) throws Exception {
        PersistentChunkCacheDiskStore store = PersistentChunkCacheDiskStore.openForTesting(root, 1, 280L * 1024L);
        byte[][] payloads = new byte[5][];
        String[] hashes = new String[payloads.length];
        for (int index = 0; index < payloads.length; index++) {
            payloads[index] = randomPayload(64 * 1024, 300 + index);
            hashes[index] = sha256(payloads[index]);
            store.writeBlob(hashes[index], payloads[index]);
        }
        Set<String> referenced = Set.of(hashes[0], hashes[1], hashes[2], hashes[4]);
        Properties fullIndex = indexForHashes(hashes);
        store.checkpoint(fullIndex);
        Properties retainedIndex = indexForHashes(hashes[0], hashes[1], hashes[2], hashes[4]);
        store.checkpoint(retainedIndex);
        store.checkpoint(retainedIndex);
        store.pruneUnreferencedBlobs(referenced, 0);

        PersistentChunkCacheDiskStore.CompactionResult normal = store.compactOneSealedSegment(
                referenced,
                0.60D,
                0L,
                () -> true
        );
        require(!normal.compacted(), "normal compaction rewrote a low-garbage segment");
        long before = store.totalSegmentBytes();
        PersistentChunkCacheDiskStore.CompactionResult capacity = store.compactOneSealedSegment(
                referenced,
                0.0D,
                0L,
                () -> true
        );
        require(capacity.compacted(), "capacity compaction ignored reclaimable segment bytes");
        require(store.totalSegmentBytes() < before, "capacity compaction did not reclaim bytes");
        require(Arrays.equals(payloads[0], store.readBlob(hashes[0])), "capacity compaction changed a live blob");
    }

    private static Properties indexForHashes(String... hashes) {
        Properties index = new Properties();
        for (int position = 0; position < hashes.length; position++) {
            String prefix = "scope.test.chunk." + position + "." + position + ".";
            index.setProperty(prefix + "hash", hashes[position]);
            index.setProperty(prefix + "serverScopeHash", "test");
        }
        return index;
    }

    private static void addRetentionEntry(
            Properties index,
            String scope,
            int coordinate,
            long lastUsed,
            long hits,
            long savedBytes,
            String hashSeed
    ) {
        String prefix = "scope." + scope + ".chunk." + coordinate + "." + coordinate + ".";
        String hash = String.format("%064x", new java.math.BigInteger(1, hashSeed.getBytes(java.nio.charset.StandardCharsets.UTF_8)));
        index.setProperty(prefix + "hash", hash);
        index.setProperty(prefix + "serverScopeHash", scope);
        index.setProperty(prefix + "lastUsedAtMillis", Long.toString(lastUsed));
        index.setProperty(prefix + "hitCount", Long.toString(hits));
        index.setProperty(prefix + "savedBytes", Long.toString(savedBytes));
    }

    private static byte[] repeatedPayload(int length, int seed) {
        byte[] bytes = new byte[length];
        for (int index = 0; index < bytes.length; index++) {
            bytes[index] = (byte) ((index * 31 + seed) & 0x7f);
        }
        return bytes;
    }

    private static byte[] randomPayload(int length, int seed) {
        byte[] bytes = new byte[length];
        new Random(seed).nextBytes(bytes);
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
