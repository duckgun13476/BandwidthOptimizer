package com.PinkCats.bandwidthoptimizer.chunk.store.blob;

import java.util.Arrays;

public record ChunkBlobHandle(
        String payloadHash,
        String payloadShortHash,
        int encodedBytes,
        byte[] blobBytes
) {

    public ChunkBlobHandle {
        payloadHash = payloadHash == null ? "" : payloadHash;
        payloadShortHash = payloadShortHash == null ? "" : payloadShortHash;
        encodedBytes = Math.max(encodedBytes, 0);
        blobBytes = blobBytes == null ? new byte[0] : Arrays.copyOf(blobBytes, blobBytes.length);
    }

    public byte[] copyBlobBytes() {
        return Arrays.copyOf(this.blobBytes, this.blobBytes.length);
    }
}
