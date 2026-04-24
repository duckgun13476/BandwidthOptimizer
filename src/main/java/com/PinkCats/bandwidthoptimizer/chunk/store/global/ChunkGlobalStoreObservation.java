package com.PinkCats.bandwidthoptimizer.chunk.store.global;

public record ChunkGlobalStoreObservation(
        String storeKey,
        String hotspotName,
        String laneName,
        String snapshotHash,
        String snapshotShortHash,
        boolean existingBeforeObserve,
        long observationCount,
        long totalObservedBytes,
        long hotspotDistinctEntryCount,
        long totalDistinctEntryCount,
        String lastChunkText,
        int encodedBytes
) {
    public String summaryText() {
        return "storeKey=" + this.storeKey
                + ", hotspot=" + this.hotspotName
                + ", lane=" + this.laneName
                + ", hash=" + this.snapshotShortHash
                + ", reused=" + this.existingBeforeObserve
                + ", observedCount=" + this.observationCount
                + ", hotspotDistinctEntries=" + this.hotspotDistinctEntryCount
                + ", totalDistinctEntries=" + this.totalDistinctEntryCount
                + ", lastChunk=" + this.lastChunkText
                + ", encodedBytes=" + this.encodedBytes;
    }
}
