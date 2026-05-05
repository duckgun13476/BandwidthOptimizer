package com.PinkCats.bandwidthoptimizer.chunk.store.blob;

public record ChunkBlobRef(
        String payloadHash,
        String payloadShortHash,
        int encodedBytes,
        long referenceCount
) {

    public ChunkBlobRef {
        payloadHash = payloadHash == null ? "" : payloadHash;
        payloadShortHash = payloadShortHash == null ? "" : payloadShortHash;
        encodedBytes = Math.max(encodedBytes, 0);
        referenceCount = Math.max(referenceCount, 0L);
    }
}
