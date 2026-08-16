package com.PinkCats.bandwidthoptimizer.chunk.persistent;

import java.lang.reflect.Method;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.util.Arrays;
import java.util.HexFormat;
import java.util.Properties;
import java.util.Random;
import java.util.Set;

public final class PersistentChunkCacheDeltaRegressionMain {

    private static final String SCOPE = "a".repeat(64);

    private PersistentChunkCacheDeltaRegressionMain() {}

    public static void main(String[] args) throws Exception {
        Path root = Files.createTempDirectory("bo-persistent-cache-delta-");
        try {
            verifyCodecModes();
            verifyCostSelectionAndRecovery(root.resolve("storage"));
            verifyRetentionDependencies();
            verifyCorruptionRejection();
            System.out.println("Persistent chunk cache delta regression passed");
        } finally {
            deleteTree(root);
        }
    }

    private static void verifyCodecModes() throws Exception {
        byte[] base = repeatedPayload(128 * 1024, 7);
        byte[] sparse = base.clone();
        for (int index = 1024; index < sparse.length; index += 8192) {
            sparse[index] ^= 0x5a;
        }
        require(PersistentChunkCacheDeltaCodec.candidates(base, sparse).stream()
                        .anyMatch(candidate -> roundTrips(base, sparse, candidate)),
                "sparse XOR delta did not round trip");

        byte[] inserted = new byte[base.length + 173];
        int split = 43 * 1024;
        System.arraycopy(base, 0, inserted, 0, split);
        Arrays.fill(inserted, split, split + 173, (byte) 91);
        System.arraycopy(base, split, inserted, split + 173, base.length - split);
        require(PersistentChunkCacheDeltaCodec.candidates(base, inserted).stream()
                        .anyMatch(candidate -> roundTrips(base, inserted, candidate)),
                "length-changing replacement delta did not round trip");
    }

    private static void verifyCostSelectionAndRecovery(Path root) throws Exception {
        PersistentChunkCacheDiskStore store = PersistentChunkCacheDiskStore.open(root, 2);
        byte[] base = new byte[256 * 1024];
        new Random(19L).nextBytes(base);
        String baseHash = sha256(base);
        store.writeBlob(baseHash, base);

        byte[] first = base.clone();
        for (int index = 4096; index < first.length; index += 32768) {
            first[index] ^= 0x33;
        }
        PersistentChunkCacheDiskStore.DeltaEvaluation firstChoice = store.evaluateDelta(base, first, 0.40D);
        require(firstChoice.useDelta() && firstChoice.storedBytes() * 5L <= firstChoice.fullStoredBytes() * 2L,
                "small change was not selected below the 40 percent threshold");
        String firstDeltaHash = store.writeDeltaBlob(firstChoice);
        Properties firstIndex = deltaIndex("scope." + SCOPE + ".chunk.1.2.", first, baseHash, firstDeltaHash);
        require(Arrays.equals(first, restore(store, firstIndex, "scope." + SCOPE + ".chunk.1.2.")),
                "stored delta did not restore the final packet");

        byte[] second = base.clone();
        for (int index = 8192; index < second.length; index += 16384) {
            second[index] ^= 0x6c;
        }
        PersistentChunkCacheDiskStore.DeltaEvaluation secondChoice = store.evaluateDelta(base, second, 0.40D);
        require(secondChoice.useDelta(), "second update did not reuse the original FULL baseline");
        String secondDeltaHash = store.writeDeltaBlob(secondChoice);
        Properties secondIndex = deltaIndex("scope." + SCOPE + ".chunk.1.2.", second, baseHash, secondDeltaHash);
        store.checkpoint(secondIndex);
        PersistentChunkCacheDiskStore reopened = PersistentChunkCacheDiskStore.open(root, 2);
        require(Arrays.equals(second, restore(reopened, reopened.loadIndex(), "scope." + SCOPE + ".chunk.1.2.")),
                "delta restart recovery changed the final packet");

        byte[] unrelated = new byte[base.length];
        new Random(991L).nextBytes(unrelated);
        require(!store.evaluateDelta(base, unrelated, 0.40D).useDelta(),
                "large change did not promote to a FULL snapshot");
    }

    private static void verifyRetentionDependencies() throws Exception {
        Properties index = new Properties();
        String sharedBase = "b".repeat(64);
        addDeltaRetentionEntry(index, 1, sharedBase, "c".repeat(64), 1_000L);
        addDeltaRetentionEntry(index, 2, sharedBase, "d".repeat(64), 2_000L);
        PersistentChunkCacheRetentionPolicy.RetentionPlan plan = PersistentChunkCacheRetentionPolicy.plan(
                index,
                ignored -> 100L,
                new PersistentChunkCacheRetentionPolicy.RetentionLimits(10_000L, 1, 0)
        );
        require(plan.evictedKeyPrefixes().size() == 1, "delta retention did not enforce the entry limit");
        require(plan.referencedHashes().size() == 2 && plan.referencedHashes().contains(sharedBase),
                "delta retention lost or double-counted the shared FULL baseline");
        require(plan.retainedBytes() == 200L, "delta retention physical byte accounting is incorrect");
    }

    private static void verifyCorruptionRejection() throws Exception {
        byte[] base = repeatedPayload(4096, 4);
        byte[] target = base.clone();
        target[100] ^= 1;
        byte[] delta = PersistentChunkCacheDeltaCodec.candidates(base, target).get(0).clone();
        delta[0] ^= 1;
        boolean rejected = false;
        try {
            PersistentChunkCacheDeltaCodec.decode(base, delta, 8192);
        } catch (Exception expected) {
            rejected = true;
        }
        require(rejected, "corrupt delta header was accepted");
    }

    private static boolean roundTrips(byte[] base, byte[] target, byte[] candidate) {
        try {
            return Arrays.equals(target, PersistentChunkCacheDeltaCodec.decode(base, candidate, target.length + 1));
        } catch (Exception ignored) {
            return false;
        }
    }

    private static Properties deltaIndex(String prefix, byte[] target, String baseHash, String deltaHash) throws Exception {
        Properties index = new Properties();
        index.setProperty(prefix + "hash", sha256(target));
        index.setProperty(prefix + "encodedBytes", Integer.toString(target.length));
        index.setProperty(prefix + "serverScopeHash", SCOPE);
        index.setProperty(prefix + "storageKind", "DELTA");
        index.setProperty(prefix + "baseHash", baseHash);
        index.setProperty(prefix + "deltaHash", deltaHash);
        return index;
    }

    private static byte[] restore(PersistentChunkCacheDiskStore store, Properties index, String prefix) throws Exception {
        Method method = ChunkPersistentClientCache.class.getDeclaredMethod(
                "readStoredPacketBytes",
                PersistentChunkCacheDiskStore.class,
                Properties.class,
                String.class
        );
        method.setAccessible(true);
        return (byte[]) method.invoke(null, store, index, prefix);
    }

    private static void addDeltaRetentionEntry(
            Properties index,
            int coordinate,
            String baseHash,
            String deltaHash,
            long lastUsed
    ) throws Exception {
        String prefix = "scope." + SCOPE + ".chunk." + coordinate + ".0.";
        index.setProperty(prefix + "hash", sha256((baseHash + deltaHash).getBytes(java.nio.charset.StandardCharsets.UTF_8)));
        index.setProperty(prefix + "serverScopeHash", SCOPE);
        index.setProperty(prefix + "lastUsedAtMillis", Long.toString(lastUsed));
        index.setProperty(prefix + "storageKind", "DELTA");
        index.setProperty(prefix + "baseHash", baseHash);
        index.setProperty(prefix + "deltaHash", deltaHash);
    }

    private static byte[] repeatedPayload(int size, int seed) {
        byte[] bytes = new byte[size];
        for (int index = 0; index < size; index++) {
            bytes[index] = (byte) ((index * 31 + seed) & 0xff);
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

    private static void deleteTree(Path root) throws Exception {
        if (!Files.exists(root)) {
            return;
        }
        try (var paths = Files.walk(root)) {
            paths.sorted((left, right) -> right.compareTo(left)).forEach(path -> {
                try {
                    Files.deleteIfExists(path);
                } catch (Exception ignored) {
                }
            });
        }
    }
}
