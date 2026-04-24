package com.PinkCats.bandwidthoptimizer.chunk.snapshot;

public record ChunkSnapshotFingerprint(
        String algorithmName,
        String hashHex,
        String shortHash,
        int encodedBytes
) {

    public String summaryText() {
        return "algorithm=" + this.algorithmName
                + ", hash=" + this.shortHash
                + ", encodedBytes=" + this.encodedBytes;
    }
}
