package com.PinkCats.bandwidthoptimizer.chunk.persistent;

import com.PinkCats.bandwidthoptimizer.chunk.classify.ChunkHotspotKind;
import com.PinkCats.bandwidthoptimizer.chunk.classify.ChunkLaneKind;
import com.PinkCats.bandwidthoptimizer.chunk.classify.packet.ChunkPacketCoordinate;
import com.PinkCats.bandwidthoptimizer.chunk.classify.packet.ChunkPacketDescriptor;
import com.PinkCats.bandwidthoptimizer.chunk.protocol.hotspot.ChunkHotspotFrame;
import com.PinkCats.bandwidthoptimizer.chunk.protocol.hotspot.ChunkHotspotFrameOp;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.ChannelInboundHandlerAdapter;
import io.netty.channel.embedded.EmbeddedChannel;
import io.netty.util.AttributeKey;

import java.io.IOException;
import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.security.MessageDigest;
import java.util.Arrays;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.Properties;
import java.util.Random;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

public final class PersistentChunkCacheBoundaryRegressionMain {

    private static final String OUTPUT_DIRECTORY_PROPERTY = "bandwidthoptimizer.outputDirectory";
    private static final String SCOPE_A = "a".repeat(64);
    private static final String SCOPE_B = "b".repeat(64);
    private static final int SEGMENT_HEADER_BYTES = 56;

    private PersistentChunkCacheBoundaryRegressionMain() {}

    public static void main(String[] args) throws Exception {
        Path root = Files.createTempDirectory("bo-persistent-cache-boundary-");
        String previousOutputDirectory = System.getProperty(OUTPUT_DIRECTORY_PROPERTY);
        try {
            verifyScopeIsolation(root.resolve("scope"));
            verifyPrepareDisconnectIsolation();
            verifyInterruptedLegacyMigration(root.resolve("migration"));
            verifyMiddleRecordCorruptionIsolation(root.resolve("middle-corruption"));
            System.out.println("Persistent chunk cache boundary regression passed");
        } finally {
            if (previousOutputDirectory == null) {
                System.clearProperty(OUTPUT_DIRECTORY_PROPERTY);
            } else {
                System.setProperty(OUTPUT_DIRECTORY_PROPERTY, previousOutputDirectory);
            }
            deleteTree(root);
        }
    }

    private static void verifyScopeIsolation(Path root) throws Exception {
        PersistentChunkCacheDiskStore store = PersistentChunkCacheDiskStore.open(root, 2);
        byte[] payloadA = randomPayload(32 * 1024, 1);
        byte[] payloadB = randomPayload(32 * 1024, 2);
        String hashA = sha256(payloadA);
        String hashB = sha256(payloadB);
        store.writeBlob(hashA, payloadA);
        store.writeBlob(hashB, payloadB);

        Properties index = new Properties();
        addIndexEntry(index, SCOPE_A, 7, 9, hashA, payloadA.length);
        addIndexEntry(index, SCOPE_B, 7, 9, hashB, payloadB.length);

        Method preload = ChunkPersistentClientCache.class.getDeclaredMethod(
                "preloadReadyBlobs",
                PersistentChunkCacheDiskStore.class,
                Properties.class,
                String.class
        );
        preload.setAccessible(true);
        @SuppressWarnings("unchecked")
        LinkedHashMap<String, byte[]> loadedA = (LinkedHashMap<String, byte[]>) preload.invoke(null, store, index, SCOPE_A);
        @SuppressWarnings("unchecked")
        LinkedHashMap<String, byte[]> loadedB = (LinkedHashMap<String, byte[]>) preload.invoke(null, store, index, SCOPE_B);

        require(loadedA.size() == 1 && Arrays.equals(payloadA, loadedA.get(hashA)), "scope A loaded foreign cache data");
        require(loadedB.size() == 1 && Arrays.equals(payloadB, loadedB.get(hashB)), "scope B loaded foreign cache data");
        require(!loadedA.containsKey(hashB) && !loadedB.containsKey(hashA), "scope filtering admitted a foreign hash");
    }

    private static void verifyPrepareDisconnectIsolation() throws Exception {
        EmbeddedChannel channel = new EmbeddedChannel(new ChannelInboundHandlerAdapter());
        try {
            Field stateKeyField = ChunkPersistentPrepareGate.class.getDeclaredField("STATE_KEY");
            stateKeyField.setAccessible(true);
            @SuppressWarnings("unchecked")
            AttributeKey<Object> stateKey = (AttributeKey<Object>) stateKeyField.get(null);
            Class<?> stateType = Class.forName(ChunkPersistentPrepareGate.class.getName() + "$PrepareGateState");
            Constructor<?> constructor = stateType.getDeclaredConstructor();
            constructor.setAccessible(true);
            Method installBloom = stateType.getDeclaredMethod("installBloom", String.class, ChunkPersistentBloomCatalog.class);
            Method reserve = stateType.getDeclaredMethod(
                    "reserve",
                    long.class,
                    ChunkPacketDescriptor.class,
                    String.class,
                    int.class
            );
            Method find = stateType.getDeclaredMethod("find", long.class);
            installBloom.setAccessible(true);
            reserve.setAccessible(true);
            find.setAccessible(true);

            ChunkPacketCoordinate coordinate = ChunkPacketCoordinate.ofChunk(12, -4);
            ChunkPacketDescriptor descriptor = new ChunkPacketDescriptor(
                    "test",
                    "test.FullChunkPacket",
                    ChunkHotspotKind.FULL_CHUNK,
                    ChunkLaneKind.FULL,
                    coordinate
            );
            Object oldState = constructor.newInstance();
            installBloom.invoke(oldState, SCOPE_A, ChunkPersistentBloomCatalog.build(java.util.List.of(coordinate)));
            long oldToken = (long) reserve.invoke(oldState, 11L, descriptor, "c".repeat(64), 4096);
            channel.attr(stateKey).set(oldState);
            require(oldToken > 0L, "failed to reserve the old PREPARE request");

            ChunkPersistentPrepareGate.clear(channel);
            require(channel.attr(stateKey).get() == null, "disconnect did not clear PREPARE state");

            Object newState = constructor.newInstance();
            installBloom.invoke(newState, SCOPE_B, ChunkPersistentBloomCatalog.build(java.util.List.of(coordinate)));
            long newToken = (long) reserve.invoke(newState, 12L, descriptor, "d".repeat(64), 4096);
            channel.attr(stateKey).set(newState);
            ChannelHandlerContext context = channel.pipeline().firstContext();
            ChunkPersistentPrepareGate.handleMiss(context, responseFrame(oldToken, 11L, coordinate, SCOPE_A));

            require(find.invoke(newState, newToken) != null, "a late PREPARE response released the new connection");
            require(find.invoke(newState, oldToken) == null, "the new connection inherited an old PREPARE token");
        } finally {
            channel.finishAndReleaseAll();
        }
    }

    private static void verifyInterruptedLegacyMigration(Path outputRoot) throws Exception {
        Files.createDirectories(outputRoot);
        System.setProperty(OUTPUT_DIRECTORY_PROPERTY, outputRoot.toString());
        byte[] first = randomPayload(24 * 1024, 31);
        byte[] second = randomPayload(24 * 1024, 32);
        String firstHash = sha256(first);
        String secondHash = sha256(second);
        Properties legacyIndex = new Properties();
        addIndexEntry(legacyIndex, SCOPE_A, 1, 2, firstHash, first.length);
        addIndexEntry(legacyIndex, SCOPE_A, 3, 4, secondHash, second.length);
        Path legacyZip = outputRoot.resolve("client-persistent-chunk-cache.zip");
        writeLegacyZip(legacyZip, legacyIndex, firstHash, first, secondHash, second);

        Path v2Root = outputRoot.resolve("client-persistent-chunk-cache-v2");
        PersistentChunkCacheDiskStore store = PersistentChunkCacheDiskStore.open(v2Root, 2);
        String firstPrefix = entryPrefix(SCOPE_A, 1, 2);
        store.writeBlob(firstHash, first);
        store.appendIndexEntry(firstPrefix, legacyIndex);

        Method migrate = ChunkPersistentClientCache.class.getDeclaredMethod(
                "migrateLegacyCaches",
                PersistentChunkCacheDiskStore.class,
                Properties.class
        );
        migrate.setAccessible(true);
        Properties resumed = store.loadIndex();
        migrate.invoke(null, store, resumed);
        migrate.invoke(null, store, store.loadIndex());

        PersistentChunkCacheDiskStore reopened = PersistentChunkCacheDiskStore.open(v2Root, 2);
        Properties migrated = reopened.loadIndex();
        require(firstHash.equals(migrated.getProperty(firstPrefix + "hash")), "migration continuation lost the first blob");
        require(secondHash.equals(migrated.getProperty(entryPrefix(SCOPE_A, 3, 4) + "hash")), "migration continuation lost the second blob");
        require(Arrays.equals(first, reopened.readBlob(firstHash)), "migration continuation changed the first blob");
        require(Arrays.equals(second, reopened.readBlob(secondHash)), "migration continuation changed the second blob");
        Path marker = v2Root.resolve("legacy-zip-migration.pending");
        require(Files.isRegularFile(legacyZip) && Files.isRegularFile(marker), "migration removed legacy recovery data too early");

        byte[] newerFirst = randomPayload(24 * 1024, 33);
        String newerFirstHash = sha256(newerFirst);
        Properties updatedIndex = reopened.loadIndex();
        reopened.writeBlob(newerFirstHash, newerFirst);
        updatedIndex.setProperty(firstPrefix + "hash", newerFirstHash);
        reopened.appendIndexEntry(firstPrefix, updatedIndex);
        reopened.checkpoint(updatedIndex);

        Path migratedSegment = v2Root.resolve("segments").resolve("segment-00000001.dat");
        long migratedSecondRecord = nextRecordOffset(migratedSegment, 0L);
        corruptPayloadByte(migratedSegment, migratedSecondRecord);
        PersistentChunkCacheDiskStore damaged = PersistentChunkCacheDiskStore.open(v2Root, 2);
        Method verifyMigration = ChunkPersistentClientCache.class.getDeclaredMethod(
                "verifyOrRepairLegacyMigration",
                PersistentChunkCacheDiskStore.class,
                Properties.class
        );
        verifyMigration.setAccessible(true);
        require(
                (boolean) verifyMigration.invoke(null, damaged, damaged.loadIndex()),
                "verified restart did not repair the damaged migrated blob"
        );
        require(
                newerFirstHash.equals(damaged.loadIndex().getProperty(firstPrefix + "hash")),
                "verified restart replaced a newer cache entry with legacy data"
        );
        require(Arrays.equals(second, damaged.readBlob(secondHash)), "legacy recovery changed the repaired blob");

        Method cleanup = ChunkPersistentClientCache.class.getDeclaredMethod("cleanupLegacyCachesAfterVerifiedRestart");
        cleanup.setAccessible(true);
        cleanup.invoke(null);
        require(!Files.exists(legacyZip) && !Files.exists(marker), "verified restart did not finish legacy cleanup");
    }

    private static void verifyMiddleRecordCorruptionIsolation(Path root) throws Exception {
        PersistentChunkCacheDiskStore store = PersistentChunkCacheDiskStore.openForTesting(root, 1, 512L * 1024L);
        byte[][] payloads = new byte[7][];
        String[] hashes = new String[payloads.length];
        for (int index = 0; index < payloads.length; index++) {
            payloads[index] = randomPayload(64 * 1024, 100 + index);
            hashes[index] = sha256(payloads[index]);
            store.writeBlob(hashes[index], payloads[index]);
        }
        Path firstSegment = root.resolve("segments").resolve("segment-00000001.dat");
        long secondRecord = nextRecordOffset(firstSegment, 0L);
        try (FileChannel channel = FileChannel.open(firstSegment, StandardOpenOption.READ, StandardOpenOption.WRITE)) {
            channel.position(secondRecord + SEGMENT_HEADER_BYTES + 8L);
            ByteBuffer one = ByteBuffer.allocate(1);
            require(channel.read(one) == 1, "failed to read the middle record payload");
            one.flip();
            byte corrupted = (byte) (one.get() ^ 0x5a);
            channel.position(secondRecord + SEGMENT_HEADER_BYTES + 8L);
            channel.write(ByteBuffer.wrap(new byte[]{corrupted}));
            channel.force(true);
        }

        PersistentChunkCacheDiskStore reopened = PersistentChunkCacheDiskStore.openForTesting(root, 1, 512L * 1024L);
        boolean rejected = false;
        try {
            reopened.readBlob(hashes[1]);
        } catch (IOException expected) {
            rejected = true;
        }
        require(rejected, "a corrupt middle record returned unverified bytes");
        require(Arrays.equals(payloads[0], reopened.readBlob(hashes[0])), "middle corruption changed an earlier record");
        require(Arrays.equals(payloads[2], reopened.readBlob(hashes[2])), "middle corruption hid a later valid record");
        require(Arrays.equals(payloads[6], reopened.readBlob(hashes[6])), "middle corruption contaminated another segment");
    }

    private static long nextRecordOffset(Path segment, long recordOffset) throws IOException {
        try (FileChannel channel = FileChannel.open(segment, StandardOpenOption.READ)) {
            ByteBuffer header = ByteBuffer.allocate(SEGMENT_HEADER_BYTES);
            channel.position(recordOffset);
            while (header.hasRemaining() && channel.read(header) >= 0) {
                // Keep reading until the complete fixed-size header is available.
            }
            require(!header.hasRemaining(), "truncated segment header in boundary fixture");
            header.flip();
            header.position(44);
            int compressedLength = header.getInt();
            return recordOffset + SEGMENT_HEADER_BYTES + compressedLength;
        }
    }

    private static void corruptPayloadByte(Path segment, long recordOffset) throws IOException {
        try (FileChannel channel = FileChannel.open(segment, StandardOpenOption.READ, StandardOpenOption.WRITE)) {
            long position = recordOffset + SEGMENT_HEADER_BYTES + 8L;
            channel.position(position);
            ByteBuffer one = ByteBuffer.allocate(1);
            require(channel.read(one) == 1, "failed to read migrated payload byte");
            one.flip();
            channel.position(position);
            channel.write(ByteBuffer.wrap(new byte[]{(byte) (one.get() ^ 0x5a)}));
            channel.force(true);
        }
    }

    private static ChunkHotspotFrame responseFrame(
            long token,
            long epoch,
            ChunkPacketCoordinate coordinate,
            String scope
    ) {
        return new ChunkHotspotFrame(
                1,
                ChunkHotspotFrameOp.CACHE_MISS,
                epoch,
                token,
                "test",
                "test.FullChunkPacket",
                ChunkHotspotKind.FULL_CHUNK,
                ChunkLaneKind.FULL,
                coordinate,
                4096,
                0L,
                0L,
                scope,
                "",
                0L,
                "boundary_regression"
        );
    }

    private static void addIndexEntry(
            Properties index,
            String scope,
            int chunkX,
            int chunkZ,
            String hash,
            int encodedBytes
    ) {
        String prefix = entryPrefix(scope, chunkX, chunkZ);
        index.setProperty(prefix + "hash", hash);
        index.setProperty(prefix + "encodedBytes", Integer.toString(encodedBytes));
        index.setProperty(prefix + "serverScopeHash", scope);
        index.setProperty(prefix + "protocolName", "test");
        index.setProperty(prefix + "packetClassName", "test.FullChunkPacket");
        index.setProperty(prefix + "lastUsedAtMillis", "1");
    }

    private static String entryPrefix(String scope, int chunkX, int chunkZ) {
        return "scope." + scope + ".chunk." + chunkX + "." + chunkZ + ".";
    }

    private static void writeLegacyZip(
            Path path,
            Properties index,
            String firstHash,
            byte[] first,
            String secondHash,
            byte[] second
    ) throws IOException {
        try (ZipOutputStream output = new ZipOutputStream(Files.newOutputStream(path))) {
            output.putNextEntry(new ZipEntry("index.properties"));
            index.store(output, "boundary regression");
            output.closeEntry();
            writeZipBlob(output, firstHash, first);
            writeZipBlob(output, secondHash, second);
        }
    }

    private static void writeZipBlob(ZipOutputStream output, String hash, byte[] bytes) throws IOException {
        output.putNextEntry(new ZipEntry("blobs/" + hash + ".bin"));
        output.write(bytes);
        output.closeEntry();
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
