package com.PinkCats.bandwidthoptimizer.chunk.persistent;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.Properties;
import java.util.Random;
import java.util.Set;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

public final class PersistentChunkCacheLoadRegressionMain {

    private static final int LARGE_INDEX_ENTRIES = 34_000;
    private static final int MIXED_OPERATIONS = 10_000;

    private PersistentChunkCacheLoadRegressionMain() {}

    public static void main(String[] args) throws Exception {
        Path root = Files.createTempDirectory("bo-persistent-cache-load-");
        long startedNanos = System.nanoTime();
        long heapBefore = usedHeap();
        try {
            verifyLongTermCapacityConvergence();
            MixedLoadResult mixed = verifyMixedDiskLoad(root.resolve("mixed"));
            verifyConcurrentMaintenanceRequests();
            long elapsedMillis = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - startedNanos);
            long heapDelta = Math.max(usedHeap() - heapBefore, 0L);
            require(elapsedMillis < 120_000L, "persistent cache load regression exceeded 120 seconds");
            require(heapDelta < 384L * 1024L * 1024L, "persistent cache load regression retained excessive heap");
            System.out.println("Persistent chunk cache load regression passed"
                    + " entries=" + LARGE_INDEX_ENTRIES
                    + " operations=" + MIXED_OPERATIONS
                    + " elapsedMs=" + elapsedMillis
                    + " segmentBytes=" + mixed.segmentBytes()
                    + " heapDeltaBytes=" + heapDelta);
        } finally {
            deleteTree(root);
        }
    }

    private static void verifyLongTermCapacityConvergence() {
        Properties index = new Properties();
        Map<String, Long> storedBytes = new HashMap<>();
        long now = System.currentTimeMillis();
        for (int entry = 0; entry < LARGE_INDEX_ENTRIES; entry++) {
            String scope = String.format("%064x", entry % 17 + 1);
            String hash = String.format("%064x", entry + 1000L);
            String prefix = "scope." + scope + ".chunk." + entry + "." + (-entry) + ".";
            index.setProperty(prefix + "hash", hash);
            index.setProperty(prefix + "serverScopeHash", scope);
            index.setProperty(prefix + "lastUsedAtMillis", Long.toString(now - (long) entry * 1000L));
            index.setProperty(prefix + "hitCount", Long.toString(entry % 101));
            index.setProperty(prefix + "savedBytes", Long.toString((long) (entry % 4096) * 4096L));
            storedBytes.put(hash, 8192L + entry % 2048L);
        }

        PersistentChunkCacheRetentionPolicy.RetentionLimits limits =
                new PersistentChunkCacheRetentionPolicy.RetentionLimits(48L * 1024L * 1024L, 4096, 32);
        PersistentChunkCacheRetentionPolicy.RetentionPlan plan =
                PersistentChunkCacheRetentionPolicy.plan(index, hash -> storedBytes.getOrDefault(hash, 0L), limits);
        for (String prefix : plan.evictedKeyPrefixes()) {
            removeEntry(index, prefix);
        }
        long remainingEntries = index.stringPropertyNames().stream().filter(key -> key.endsWith(".hash")).count();
        require(remainingEntries <= limits.maxEntries(), "retention did not converge to the entry limit");
        require(plan.retainedBytes() <= limits.maxBytes(), "retention did not converge to the byte limit");
        for (int scopeIndex = 1; scopeIndex <= 17; scopeIndex++) {
            String scope = String.format("%064x", scopeIndex);
            long scopeEntries = index.stringPropertyNames().stream()
                    .filter(key -> key.endsWith(".serverScopeHash") && scope.equals(index.getProperty(key)))
                    .count();
            require(scopeEntries >= limits.minimumEntriesPerScope(), "retention starved scope " + scopeIndex);
        }
        PersistentChunkCacheRetentionPolicy.RetentionPlan converged =
                PersistentChunkCacheRetentionPolicy.plan(index, hash -> storedBytes.getOrDefault(hash, 0L), limits);
        require(converged.evictedKeyPrefixes().isEmpty(), "retention did not remain stable after convergence");
    }

    private static MixedLoadResult verifyMixedDiskLoad(Path root) throws Exception {
        PersistentChunkCacheDiskStore store = PersistentChunkCacheDiskStore.openForTesting(root, 1, 2L * 1024L * 1024L);
        Properties index = new Properties();
        ArrayList<String> knownHashes = new ArrayList<>();
        Random random = new Random(9917L);
        int fullWrites = 0;
        int readyReads = 0;
        int referenceReads = 0;
        for (int operation = 0; operation < MIXED_OPERATIONS; operation++) {
            int lane = operation % 20;
            if (knownHashes.isEmpty() || lane < 3) {
                byte[] payload = new byte[4096 + random.nextInt(4096)];
                random.nextBytes(payload);
                String hash = sha256(payload);
                store.writeBlob(hash, payload);
                knownHashes.add(hash);
                String prefix = "scope." + String.format("%064x", operation % 8 + 1)
                        + ".chunk." + operation + "." + (-operation) + ".";
                index.setProperty(prefix + "hash", hash);
                index.setProperty(prefix + "serverScopeHash", String.format("%064x", operation % 8 + 1));
                index.setProperty(prefix + "encodedBytes", Integer.toString(payload.length));
                index.setProperty(prefix + "lastUsedAtMillis", Long.toString(operation));
                store.appendIndexEntry(prefix, index);
                fullWrites++;
            } else {
                String hash = knownHashes.get(random.nextInt(knownHashes.size()));
                byte[] restored = store.readBlob(hash);
                require(restored != null && hash.equals(sha256(restored)), "mixed load returned invalid cached bytes");
                if (lane < 6) {
                    readyReads++;
                } else {
                    referenceReads++;
                }
            }
            if (operation > 0 && operation % 1000 == 0) {
                store.checkpoint(index);
            }
        }
        store.checkpoint(index);
        PersistentChunkCacheDiskStore reopened = PersistentChunkCacheDiskStore.openForTesting(root, 1, 2L * 1024L * 1024L);
        Properties recovered = reopened.loadIndex();
        require(recovered.stringPropertyNames().stream().filter(key -> key.endsWith(".hash")).count() == fullWrites,
                "mixed load restart lost committed entries");
        require(fullWrites == 1500 && readyReads == 1500 && referenceReads == 7000,
                "mixed load did not exercise the intended FULL/PREPARE/REF ratio");
        return new MixedLoadResult(reopened.totalSegmentBytes());
    }

    private static void verifyConcurrentMaintenanceRequests() throws Exception {
        ArrayDeque<Runnable> scheduled = new ArrayDeque<>();
        AtomicBoolean windowOpen = new AtomicBoolean(true);
        AtomicInteger runs = new AtomicInteger();
        PersistentCacheMaintenanceCoordinator coordinator = new PersistentCacheMaintenanceCoordinator(
                runnable -> {
                    synchronized (scheduled) {
                        scheduled.addLast(runnable);
                    }
                },
                windowOpen::get,
                ignored -> runs.incrementAndGet()
        );
        int workers = 8;
        int requestsPerWorker = 1000;
        ExecutorService executor = Executors.newFixedThreadPool(workers);
        CountDownLatch ready = new CountDownLatch(workers);
        CountDownLatch start = new CountDownLatch(1);
        for (int worker = 0; worker < workers; worker++) {
            int workerId = worker;
            executor.execute(() -> {
                ready.countDown();
                try {
                    start.await();
                    for (int request = 0; request < requestsPerWorker; request++) {
                        coordinator.request("worker-" + workerId + "-" + request);
                    }
                } catch (InterruptedException exception) {
                    Thread.currentThread().interrupt();
                }
            });
        }
        require(ready.await(10, TimeUnit.SECONDS), "concurrent maintenance workers did not become ready");
        start.countDown();
        executor.shutdown();
        require(executor.awaitTermination(30, TimeUnit.SECONDS), "concurrent maintenance workers did not finish");
        while (true) {
            Runnable runnable;
            synchronized (scheduled) {
                runnable = scheduled.pollFirst();
            }
            if (runnable == null) {
                break;
            }
            runnable.run();
        }
        require(runs.get() > 0, "concurrent maintenance requests were lost");

        coordinator.request("after-drain");
        Runnable finalDrain;
        synchronized (scheduled) {
            finalDrain = scheduled.pollFirst();
        }
        require(finalDrain != null, "maintenance coordinator remained stuck after concurrent drain");
        int before = runs.get();
        finalDrain.run();
        require(runs.get() == before + 1, "maintenance coordinator did not accept a post-drain request");
    }

    private static void removeEntry(Properties index, String prefix) {
        List<String> suffixes = List.of(
                "hash",
                "serverScopeHash",
                "lastUsedAtMillis",
                "hitCount",
                "savedBytes",
                "encodedBytes",
                "temperature"
        );
        for (String suffix : suffixes) {
            index.remove(prefix + suffix);
        }
    }

    private static long usedHeap() {
        Runtime runtime = Runtime.getRuntime();
        return runtime.totalMemory() - runtime.freeMemory();
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

    private record MixedLoadResult(long segmentBytes) {}
}
