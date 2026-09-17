package com.PinkCats.bandwidthoptimizer.recipe;

import com.PinkCats.bandwidthoptimizer.util.BandwidthOptimizerOutputPaths;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Random;

public final class RecipeSyncPersistentDeltaRegressionMain {

    private RecipeSyncPersistentDeltaRegressionMain() {}

    public static void main(String[] arguments) throws Exception {
        Path outputRoot = Path.of("build", "recipe-sync-regression").toAbsolutePath();
        System.setProperty(BandwidthOptimizerOutputPaths.OUTPUT_DIRECTORY_PROPERTY, outputRoot.toString());

        List<byte[]> records = recipeRecords(4_096, 640);
        byte[] base = join(records);
        List<byte[]> reordered = new ArrayList<>(records);
        Collections.shuffle(reordered, new Random(0xB0A5EEDL));
        reordered.set(32, recipeRecord(99_999, 640));
        reordered.remove(64);
        reordered.add(recipeRecord(100_001, 640));
        byte[] target = join(reordered);

        byte[] delta = RecipeSyncDeltaCodec.encode(base, target);
        require(delta != null, "delta must be produced");
        System.out.println("RECIPE_SYNC_DELTA_SAMPLE"
                + " baseBytes=" + base.length
                + " targetBytes=" + target.length
                + " deltaBytes=" + delta.length
                + " ratio=" + String.format(java.util.Locale.ROOT, "%.4f", (double) delta.length / target.length));
        require(delta.length < target.length / 2, "reordered recipe delta must be materially smaller than full");
        require(java.util.Arrays.equals(target, RecipeSyncDeltaCodec.decode(base, delta, target.length + 1)),
                "delta must reconstruct exact original bytes");

        byte[] structuralPrefix = new byte[] {0x42, (byte) 0x80, 0x20};
        List<byte[]> structuralRecords = recipeRecords(4_096, 96);
        byte[] structuralTarget = join(structuralPrefix, structuralRecords);
        List<byte[]> structuralBaseRecords = new ArrayList<>(structuralRecords);
        structuralBaseRecords.set(2_048, recipeRecord(200_000, 96));
        byte[] structuralBase = join(structuralPrefix, structuralBaseRecords);
        RecipeSyncStructuralView structuralView = RecipeSyncStructuralView.verified(
                structuralTarget, structuralRecords);
        require(structuralView != null, "real record boundaries must verify against the target packet");
        byte[] structuralDelta = RecipeSyncDeltaCodec.encodeStructured(structuralBase, structuralView);
        require(structuralDelta != null && structuralDelta.length < 256,
                "one changed recipe must produce a record-sized structural delta");
        require(java.util.Arrays.equals(structuralTarget,
                        RecipeSyncDeltaCodec.decode(structuralBase, structuralDelta, structuralTarget.length + 1)),
                "structural delta must reconstruct the exact target packet");
        RecipeSyncDeltaCodec.SingleRecordSample structuralSample =
                RecipeSyncDeltaCodec.sampleSingleRecordReplacement(
                        structuralTarget, structuralView, structuralTarget.length + 1);
        require(structuralSample.available() && structuralSample.recordBytes() == 96
                        && structuralSample.deltaBytes() < 256,
                "single-record RunAll sample must use the production structural codec");

        List<byte[]> fragmentedRecords = recipeRecords(65_536, 80);
        byte[] fragmentedBase = join(fragmentedRecords);
        Collections.shuffle(fragmentedRecords, new Random(0x5EED5EEDL));
        byte[] fragmentedTarget = join(fragmentedRecords);
        byte[] fragmentedDelta = RecipeSyncDeltaCodec.encode(fragmentedBase, fragmentedTarget);
        require(fragmentedDelta != null, "large reordered recipe delta must not fall back at the old command limit");
        require(fragmentedDelta.length < fragmentedTarget.length / 2,
                "large reordered recipe delta must remain materially smaller than full");
        require(java.util.Arrays.equals(fragmentedTarget,
                        RecipeSyncDeltaCodec.decode(fragmentedBase, fragmentedDelta, fragmentedTarget.length + 1)),
                "large reordered recipe delta must reconstruct exact original bytes");

        byte[] malformed = delta.clone();
        malformed[0] ^= 0x40;
        expectFailure(() -> RecipeSyncDeltaCodec.decode(base, malformed, target.length + 1),
                "corrupt delta header must fail closed");
        expectFailure(() -> RecipeSyncDeltaCodec.decode(base, delta, target.length - 1),
                "oversized reconstructed packet must fail closed");

        String scope = RecipeSyncDeltaCodec.sha256("regression-scope".getBytes(StandardCharsets.UTF_8));
        String targetHash = RecipeSyncDeltaCodec.sha256(target);
        require(RecipeSyncPersistentStore.storeServerAsync(scope, target).get(), "server base must persist");
        RecipeSyncPersistentStore.StoredBase loaded = RecipeSyncPersistentStore.loadServerAsync(scope, targetHash).get();
        require(loaded != null && java.util.Arrays.equals(target, loaded.copyPacketBytes()),
                "server base must survive a disk round trip");

        Path blob = outputRoot.resolve("recipe-sync-cache-v1/server")
                .resolve(scope).resolve("blobs").resolve(targetHash + ".bor");
        Files.write(blob, new byte[] {1, 2, 3, 4});
        require(RecipeSyncPersistentStore.loadServerAsync(scope, targetHash).get() == null,
                "corrupt persistent base must be rejected and trigger full rebuild");
        Files.createDirectories(blob.getParent());
        Files.write(blob, new byte[] {1, 2, 3, 4});
        require(RecipeSyncPersistentStore.storeServerAsync(scope, target).get(),
                "storing an existing corrupt base must rebuild it");
        RecipeSyncPersistentStore.StoredBase rebuilt =
                RecipeSyncPersistentStore.loadServerAsync(scope, targetHash).get();
        require(rebuilt != null && java.util.Arrays.equals(target, rebuilt.copyPacketBytes()),
                "rebuilt server base must be readable immediately");

        require(RecipeSyncPersistentStore.storeClientAsync(scope, base).get(), "older client base must persist");
        require(RecipeSyncPersistentStore.storeClientAsync(scope, target).get(), "client base must persist");
        RecipeSyncPersistentStore.StoredBase client = RecipeSyncPersistentStore.loadLatestClientAsync(scope).get();
        require(client != null && targetHash.equals(client.hash()), "client latest manifest must resolve verified base");
        List<RecipeSyncPersistentStore.StoredBase> recent = RecipeSyncPersistentStore.loadRecentClientAsync(scope).get();
        require(recent.size() == 2 && targetHash.equals(recent.get(0).hash())
                        && RecipeSyncDeltaCodec.sha256(base).equals(recent.get(1).hash()),
                "client must retain and advertise the two most recent verified bases");

        String fiveScope = RecipeSyncDeltaCodec.sha256("five-base-scope".getBytes(StandardCharsets.UTF_8));
        ArrayList<String> fiveHashes = new ArrayList<>();
        for (int index = 0; index < 6; index++) {
            byte[] tree = ("recipe-tree-" + index).getBytes(StandardCharsets.UTF_8);
            require(RecipeSyncPersistentStore.storeClientAsync(fiveScope, tree).get(),
                    "client recipe tree " + index + " must persist");
            fiveHashes.add(RecipeSyncDeltaCodec.sha256(tree));
        }
        List<RecipeSyncPersistentStore.StoredBase> fiveRecent =
                RecipeSyncPersistentStore.loadRecentClientAsync(fiveScope).get();
        require(fiveRecent.size() == 5 && fiveHashes.get(5).equals(fiveRecent.get(0).hash()),
                "client must retain its five most recent recipe trees");
        require(RecipeSyncPersistentStore.load(
                        RecipeSyncPersistentStore.Role.CLIENT, fiveScope, fiveHashes.get(0)) == null,
                "the sixth tree must evict the oldest client recipe tree");

        byte[] promotedTree = ("recipe-tree-1").getBytes(StandardCharsets.UTF_8);
        require(RecipeSyncPersistentStore.storeClientAsync(fiveScope, promotedTree).get(),
                "using an older retained tree must promote it to most recent");
        byte[] seventhTree = "recipe-tree-6".getBytes(StandardCharsets.UTF_8);
        String seventhHash = RecipeSyncDeltaCodec.sha256(seventhTree);
        require(RecipeSyncPersistentStore.storeClientAsync(fiveScope, seventhTree).get(),
                "a new tree after promotion must persist");
        List<RecipeSyncPersistentStore.StoredBase> promotedRecent =
                RecipeSyncPersistentStore.loadRecentClientAsync(fiveScope).get();
        require(promotedRecent.size() == 5 && seventhHash.equals(promotedRecent.get(0).hash())
                        && fiveHashes.get(1).equals(promotedRecent.get(1).hash()),
                "the previously used tree must remain directly behind the newest tree");
        require(RecipeSyncPersistentStore.load(
                        RecipeSyncPersistentStore.Role.CLIENT, fiveScope, fiveHashes.get(2)) == null,
                "adding a tree after promotion must evict the actual least-recently-used tree");

        RecipeSyncTrafficStats.resetForTests();
        RecipeSyncTrafficStats.record(RecipeSyncTrafficStats.Mode.FULL, 1_000, 1_000, 1_024);
        RecipeSyncTrafficStats.record(RecipeSyncTrafficStats.Mode.IDENTITY, 1_000, 14, 48);
        RecipeSyncTrafficStats.record(RecipeSyncTrafficStats.Mode.STRUCTURAL_DELTA, 1_000, 124, 160);
        RecipeSyncTrafficStats.record(RecipeSyncTrafficStats.Mode.BYTE_DELTA, 1_000, 300, 336);
        RecipeSyncTrafficStats.Snapshot traffic = RecipeSyncTrafficStats.snapshot();
        require(traffic.totalFrames() == 4L && traffic.logicalBytes() == 4_000L
                        && traffic.payloadBytes() == 1_438L && traffic.frameBytes() == 1_568L
                        && traffic.savedBytes() == 2_432L,
                "recipe traffic counters must preserve each accounting stage");
        System.out.println("RECIPE_SYNC_PERSISTENT_DELTA_REGRESSION_OK"
                + " baseBytes=" + base.length
                + " targetBytes=" + target.length
                + " deltaBytes=" + delta.length
                + " ratio=" + String.format(java.util.Locale.ROOT, "%.4f", (double) delta.length / target.length)
                + " fragmentedDeltaBytes=" + fragmentedDelta.length
                + " singleRecipeDeltaBytes=" + structuralDelta.length);
    }

    private static List<byte[]> recipeRecords(int count, int bytesPerRecord) {
        ArrayList<byte[]> records = new ArrayList<>(count);
        for (int index = 0; index < count; index++) {
            records.add(recipeRecord(index, bytesPerRecord));
        }
        return records;
    }

    private static byte[] recipeRecord(int index, int length) {
        byte[] bytes = new byte[length];
        byte[] prefix = ("mod:recipe_" + index + "|").getBytes(StandardCharsets.UTF_8);
        System.arraycopy(prefix, 0, bytes, 0, Math.min(prefix.length, bytes.length));
        Random random = new Random(index * 31L + 17L);
        for (int offset = prefix.length; offset < bytes.length; offset++) {
            bytes[offset] = (byte) random.nextInt(256);
        }
        return bytes;
    }

    private static byte[] join(List<byte[]> records) {
        int length = records.stream().mapToInt(record -> record.length).sum();
        byte[] joined = new byte[length];
        int offset = 0;
        for (byte[] record : records) {
            System.arraycopy(record, 0, joined, offset, record.length);
            offset += record.length;
        }
        return joined;
    }

    private static byte[] join(byte[] prefix, List<byte[]> records) {
        byte[] recordsBytes = join(records);
        byte[] joined = new byte[prefix.length + recordsBytes.length];
        System.arraycopy(prefix, 0, joined, 0, prefix.length);
        System.arraycopy(recordsBytes, 0, joined, prefix.length, recordsBytes.length);
        return joined;
    }

    private static void expectFailure(ThrowingRunnable runnable, String message) throws Exception {
        try {
            runnable.run();
        } catch (java.io.IOException expected) {
            return;
        }
        throw new AssertionError(message);
    }

    private static void require(boolean condition, String message) {
        if (!condition) {
            throw new AssertionError(message);
        }
    }

    @FunctionalInterface
    private interface ThrowingRunnable {
        void run() throws Exception;
    }
}
