package com.PinkCats.bandwidthoptimizer.chunk.store.global;

import com.PinkCats.bandwidthoptimizer.chunk.classify.ChunkPacketDescriptor;
import com.PinkCats.bandwidthoptimizer.chunk.snapshot.ChunkSnapshotFingerprint;

final class ChunkGlobalSnapshotRecord {

    private final String storeKey;
    private final String hotspotName;
    private final String laneName;
    private final String snapshotHash;
    private final String snapshotShortHash;
    private final long firstSeenAtMillis;
    private long lastSeenAtMillis;
    private long observationCount;
    private long totalObservedBytes;
    private String lastChunkText;
    private int lastEncodedBytes;

    ChunkGlobalSnapshotRecord(String storeKey, ChunkPacketDescriptor descriptor, ChunkSnapshotFingerprint fingerprint) {
        long now = System.currentTimeMillis();
        this.storeKey = storeKey;
        this.hotspotName = descriptor.hotspotKind().logName();
        this.laneName = descriptor.laneKind().logName();
        this.snapshotHash = fingerprint.hashHex();
        this.snapshotShortHash = fingerprint.shortHash();
        this.firstSeenAtMillis = now;
        this.lastSeenAtMillis = now;
        this.lastChunkText = descriptor.coordinate().logText();
        this.lastEncodedBytes = Math.max(fingerprint.encodedBytes(), 0);
    }

    synchronized ChunkGlobalStoreObservation recordObservation(
            ChunkPacketDescriptor descriptor,
            ChunkSnapshotFingerprint fingerprint,
            boolean existingBeforeObserve,
            long hotspotDistinctEntryCount,
            long totalDistinctEntryCount
    ) {
        this.lastSeenAtMillis = System.currentTimeMillis();
        this.observationCount++;
        this.totalObservedBytes += Math.max(fingerprint.encodedBytes(), 0);
        this.lastChunkText = descriptor.coordinate().logText();
        this.lastEncodedBytes = Math.max(fingerprint.encodedBytes(), 0);
        return new ChunkGlobalStoreObservation(
                this.storeKey,
                this.hotspotName,
                this.laneName,
                this.snapshotHash,
                this.snapshotShortHash,
                existingBeforeObserve,
                this.observationCount,
                this.totalObservedBytes,
                hotspotDistinctEntryCount,
                totalDistinctEntryCount,
                this.lastChunkText,
                this.lastEncodedBytes
        );
    }
}
