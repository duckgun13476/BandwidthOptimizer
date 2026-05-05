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
        long referenceCount,
        long hotspotDistinctEntryCount,
        long totalDistinctEntryCount,
        long materializedSnapshotCount,
        long retainedBlobBytes,
        long blobBudgetBytes,
        long totalBlobEvictions,
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
                + ", refCount=" + this.referenceCount
                + ", hotspotDistinctEntries=" + this.hotspotDistinctEntryCount
                + ", totalDistinctEntries=" + this.totalDistinctEntryCount
                + ", materializedSnapshots=" + this.materializedSnapshotCount
                + ", retainedBlobBytes=" + this.retainedBlobBytes
                + "/" + this.blobBudgetBytes
                + ", evictedBlobs=" + this.totalBlobEvictions
                + ", lastChunk=" + this.lastChunkText
                + ", encodedBytes=" + this.encodedBytes;
    }
}
