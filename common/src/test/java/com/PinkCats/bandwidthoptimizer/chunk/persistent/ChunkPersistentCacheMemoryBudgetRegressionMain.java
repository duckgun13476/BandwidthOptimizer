package com.PinkCats.bandwidthoptimizer.chunk.persistent;

import com.PinkCats.bandwidthoptimizer.chunk.classify.ChunkHotspotKind;
import com.PinkCats.bandwidthoptimizer.chunk.classify.ChunkLaneKind;
import com.PinkCats.bandwidthoptimizer.chunk.classify.packet.ChunkPacketCoordinate;
import io.netty.channel.embedded.EmbeddedChannel;

import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;

public final class ChunkPersistentCacheMemoryBudgetRegressionMain {

    private static final String HASH_A = "a".repeat(64);
    private static final String HASH_B = "b".repeat(64);

    private ChunkPersistentCacheMemoryBudgetRegressionMain() {}

    public static void main(String[] args) throws Exception {
        resetState();
        verifyAllRetainedCopiesAreAccountedAndTrimmed();
        resetState();
        verifyPreparedStateReleasesOnClose();
        resetState();
        System.out.println("Persistent cache memory budget regression passed");
    }

    private static void verifyAllRetainedCopiesAreAccountedAndTrimmed() throws Exception {
        putReady(HASH_A, 4096);
        putPendingStore("scope:0:0", 2048);

        EmbeddedChannel channel = new EmbeddedChannel();
        Object prepared = preparedState(channel);
        require(putPrepared(prepared, HASH_B, 3072), "failed to retain prepared cache bytes");
        require(putPrepared(prepared, HASH_B, 1024), "failed to replace prepared cache bytes");

        ChunkPersistentClientCache.MemoryUsageSnapshot usage = ChunkPersistentClientCache.memoryUsageSnapshot();
        require(usage.readyBytes() == 4096L, "ready cache bytes were not counted exactly");
        require(usage.preparedBytes() == 1024L, "prepared replacement was double counted");
        require(usage.pendingStoreBytes() == 2048L, "pending store bytes were not counted exactly");
        require(usage.totalBytes() == 7168L, "unified persistent usage total is incorrect");

        ChunkPersistentClientCache.MemoryTrimResult firstTrim =
                ChunkPersistentClientCache.trimRetainedMemoryTo(5120L);
        require(firstTrim.after().pendingStoreBytes() == 0L, "pending work was not trimmed first");
        require(firstTrim.after().readyBytes() == 4096L, "ready cache was trimmed before disposable pending work");
        require(firstTrim.after().preparedBytes() == 1024L, "prepared cache was trimmed before the target required it");

        ChunkPersistentClientCache.MemoryTrimResult finalTrim =
                ChunkPersistentClientCache.trimRetainedMemoryTo(0L);
        require(finalTrim.after().totalBytes() == 0L, "persistent retained memory did not reach the target");
        require(readyBlobs().isEmpty(), "ready cache retained an evicted blob");
        channel.finishAndReleaseAll();
    }

    private static void verifyPreparedStateReleasesOnClose() throws Exception {
        EmbeddedChannel channel = new EmbeddedChannel();
        Object prepared = preparedState(channel);
        require(putPrepared(prepared, HASH_A, 8192), "failed to retain close-test bytes");
        require(ChunkPersistentClientCache.memoryUsageSnapshot().preparedBytes() == 8192L,
                "prepared bytes missing before close");
        channel.close().syncUninterruptibly();
        channel.runPendingTasks();
        require(ChunkPersistentClientCache.memoryUsageSnapshot().preparedBytes() == 0L,
                "channel close retained prepared bytes");
        channel.finishAndReleaseAll();
    }

    private static Object preparedState(EmbeddedChannel channel) throws Exception {
        Method method = ChunkPersistentClientCache.class.getDeclaredMethod("getOrCreatePreparedReadyState", io.netty.channel.Channel.class);
        method.setAccessible(true);
        return method.invoke(null, channel);
    }

    private static boolean putPrepared(Object state, String hash, int bytes) throws Exception {
        Method method = state.getClass().getDeclaredMethod(
                "put",
                String.class,
                ChunkPacketCoordinate.class,
                String.class,
                byte[].class,
                long.class
        );
        method.setAccessible(true);
        return (boolean) method.invoke(
                state,
                "c".repeat(64),
                ChunkPacketCoordinate.ofChunk(1, 2),
                hash,
                new byte[bytes],
                60_000_000_000L
        );
    }

    private static void putReady(String hash, int bytes) throws Exception {
        Method method = ChunkPersistentClientCache.class.getDeclaredMethod("putReadyBlobLocked", String.class, byte[].class);
        method.setAccessible(true);
        method.invoke(null, hash, new byte[bytes]);
    }

    @SuppressWarnings("unchecked")
    private static void putPendingStore(String key, int bytes) throws Exception {
        Class<?> type = Class.forName(ChunkPersistentClientCache.class.getName() + "$PendingStoreRequest");
        Constructor<?> constructor = type.getDeclaredConstructors()[0];
        constructor.setAccessible(true);
        Object request = constructor.newInstance(
                "c".repeat(64),
                "PLAY",
                1L,
                "test.Packet",
                ChunkHotspotKind.FULL_CHUNK,
                ChunkLaneKind.FULL,
                ChunkPacketCoordinate.ofChunk(0, 0),
                new byte[bytes],
                1L,
                "test"
        );
        Field requestsField = ChunkPersistentClientCache.class.getDeclaredField("PENDING_STORE_REQUESTS");
        requestsField.setAccessible(true);
        ((Map<String, Object>) requestsField.get(null)).put(key, request);
        atomicInteger("PENDING_STORE_REQUEST_COUNT").set(1);
        atomicLong("PENDING_STORE_REQUEST_BYTES").set(bytes);
    }

    @SuppressWarnings("unchecked")
    private static Map<String, byte[]> readyBlobs() throws Exception {
        Field field = ChunkPersistentClientCache.class.getDeclaredField("READY_BLOBS");
        field.setAccessible(true);
        return (Map<String, byte[]>) field.get(null);
    }

    private static void resetState() throws Exception {
        ChunkPersistentClientCache.trimRetainedMemoryTo(0L);
        readyBlobs().clear();
        field("readyBlobBytes").setLong(null, 0L);
        atomicInteger("PENDING_STORE_REQUEST_COUNT").set(0);
        atomicLong("PENDING_STORE_REQUEST_BYTES").set(0L);
        atomicLong("PREPARED_READY_BYTES").set(0L);
    }

    private static Field field(String name) throws Exception {
        Field field = ChunkPersistentClientCache.class.getDeclaredField(name);
        field.setAccessible(true);
        return field;
    }

    private static AtomicInteger atomicInteger(String name) throws Exception {
        return (AtomicInteger) field(name).get(null);
    }

    private static AtomicLong atomicLong(String name) throws Exception {
        return (AtomicLong) field(name).get(null);
    }

    private static void require(boolean condition, String message) {
        if (!condition) {
            throw new IllegalStateException(message);
        }
    }
}
