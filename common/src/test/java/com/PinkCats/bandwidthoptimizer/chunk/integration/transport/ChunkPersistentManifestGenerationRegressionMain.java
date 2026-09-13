package com.PinkCats.bandwidthoptimizer.chunk.integration.transport;

import com.PinkCats.bandwidthoptimizer.chunk.protocol.hotspot.ChunkHotspotFrame;
import com.PinkCats.bandwidthoptimizer.chunk.protocol.hotspot.ChunkHotspotFrameOp;

public final class ChunkPersistentManifestGenerationRegressionMain {

    private static final String SCOPE = "0123456789abcdef0123456789abcdef0123456789abcdef0123456789abcdef";

    private ChunkPersistentManifestGenerationRegressionMain() {}

    public static void main(String[] args) {
        long generation = 37L;
        ChunkHotspotFrame scope = ChunkTransportControlFrameSender.buildServerCacheScopeFrame(
                SCOPE, generation, "regression_scope");
        ChunkHotspotFrame batch = ChunkTransportControlFrameSender.buildPersistentManifestBatchFrame(
                128, 4, SCOPE, generation, "regression_batch");
        ChunkHotspotFrame complete = ChunkTransportControlFrameSender.buildPersistentManifestCompleteFrame(
                SCOPE, generation, "regression_complete");

        assertFrame(scope, ChunkHotspotFrameOp.SERVER_CACHE_SCOPE, generation, SCOPE);
        assertFrame(batch, ChunkHotspotFrameOp.CLIENT_CACHE_MANIFEST, generation, SCOPE);
        assertFrame(complete, ChunkHotspotFrameOp.CLIENT_CACHE_MANIFEST, generation, SCOPE);
        require(batch.originalEncodedBytes() == 128, "batch payload length was not retained");
        require(batch.fullSnapshotVersion() == 4L, "batch entry count was not retained");
        require(complete.originalEncodedBytes() == 0, "complete frame unexpectedly carries payload bytes");

        System.out.println("Chunk persistent manifest generation regression passed.");
    }

    private static void assertFrame(
            ChunkHotspotFrame frame,
            ChunkHotspotFrameOp operation,
            long generation,
            String scope
    ) {
        require(frame.operation() == operation, "unexpected operation: " + frame.operation());
        require(frame.epoch() == generation, "manifest generation was not preserved");
        require(scope.equals(frame.payloadHash()), "manifest scope was not preserved");
    }

    private static void require(boolean condition, String message) {
        if (!condition) {
            throw new IllegalStateException(message);
        }
    }
}
