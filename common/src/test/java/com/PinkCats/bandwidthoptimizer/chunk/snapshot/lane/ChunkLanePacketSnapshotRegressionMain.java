package com.PinkCats.bandwidthoptimizer.chunk.snapshot.lane;

import java.lang.reflect.Field;
import java.util.Arrays;

public final class ChunkLanePacketSnapshotRegressionMain {

    private static final int PAYLOAD_BYTES = 4 * 1024 * 1024;

    private ChunkLanePacketSnapshotRegressionMain() {}

    public static void main(String[] args) throws Exception {
        byte[] source = new byte[PAYLOAD_BYTES];
        Arrays.fill(source, (byte) 0x5A);
        ChunkLanePacketSnapshot captured = snapshot(source);

        source[0] = 0;
        assertByte(captured.copyOriginalPacketBytes()[0], (byte) 0x5A, "capture boundary did not copy input");

        Field payloadField = ChunkLanePacketSnapshot.class.getDeclaredField("originalPacketBytes");
        payloadField.setAccessible(true);
        Object payloadIdentity = payloadField.get(captured);

        ChunkLanePacketSnapshot touched = captured;
        for (int index = 1; index <= 10_000; index++) {
            touched = touched.markAccess(index);
            if (payloadField.get(touched) != payloadIdentity) {
                throw new AssertionError("Access metadata update copied the retained payload at iteration " + index);
            }
        }
        if (touched.accessCount() != 10_001L || touched.lastAccessAtMillis() != 10_000L) {
            throw new AssertionError("Access metadata did not advance correctly");
        }

        ChunkLanePacketSnapshot rewrittenStats = touched.withAccessStats(7L, 9L);
        if (payloadField.get(rewrittenStats) != payloadIdentity) {
            throw new AssertionError("withAccessStats copied the retained payload");
        }

        byte[] exported = touched.copyOriginalPacketBytes();
        exported[0] = 0;
        assertByte(touched.copyOriginalPacketBytes()[0], (byte) 0x5A, "output boundary exposed retained payload");

        ChunkLanePacketSnapshot evicted = touched.withoutOriginalPacketBytes();
        if (evicted.hasOriginalPacketBytes() || evicted.retainedOriginalBytes() != 0L) {
            throw new AssertionError("Payload eviction did not release retained bytes");
        }
        if (!touched.hasOriginalPacketBytes() || touched.retainedOriginalBytes() != PAYLOAD_BYTES) {
            throw new AssertionError("Derived eviction mutated the source snapshot");
        }

        System.out.println("Chunk lane packet snapshot ownership regression passed");
    }

    private static ChunkLanePacketSnapshot snapshot(byte[] payload) {
        return new ChunkLanePacketSnapshot(
                "play",
                "test.Packet",
                null,
                null,
                "full",
                1L,
                1L,
                "hash",
                "short",
                payload.length,
                1L,
                1L,
                1L,
                payload
        );
    }

    private static void assertByte(byte actual, byte expected, String message) {
        if (actual != expected) {
            throw new AssertionError(message + ": expected=" + expected + " actual=" + actual);
        }
    }
}
