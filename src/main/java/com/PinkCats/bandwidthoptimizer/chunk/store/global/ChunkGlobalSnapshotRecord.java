package com.PinkCats.bandwidthoptimizer.chunk.store.global;

import com.PinkCats.bandwidthoptimizer.chunk.classify.ChunkPacketDescriptor;
import com.PinkCats.bandwidthoptimizer.chunk.snapshot.ChunkSnapshotFingerprint;

import java.util.Arrays;
import java.util.LinkedHashSet;
import java.util.Set;

final class ChunkGlobalSnapshotRecord {

    private final String storeKey;
    private final String snapshotHash;
    private final String snapshotShortHash;
    private final int encodedBytes;
    private final byte[] blobBytes;
    private final LinkedHashSet<String> observedHotspotNames = new LinkedHashSet<>();
    private final long firstSeenAtMillis;
    private String hotspotName;
    private String laneName;
    private long lastSeenAtMillis;
    private long lastAccessAtMillis;
    private long observationCount;
    private long totalObservedBytes;
    private long referenceCount;
    private String lastChunkText;

    ChunkGlobalSnapshotRecord(
            String storeKey,
            ChunkPacketDescriptor descriptor,
            ChunkSnapshotFingerprint fingerprint,
            byte[] encodedPacketBytes
    ) {
        long now = System.currentTimeMillis();
        this.storeKey = storeKey;
        this.snapshotHash = fingerprint.hashHex();
        this.snapshotShortHash = fingerprint.shortHash();
        this.encodedBytes = Math.max(fingerprint.encodedBytes(), 0);
        this.blobBytes = encodedPacketBytes == null ? new byte[0] : Arrays.copyOf(encodedPacketBytes, encodedPacketBytes.length);
        this.firstSeenAtMillis = now;
        this.lastSeenAtMillis = now;
        this.lastAccessAtMillis = now;
        this.hotspotName = descriptor.hotspotKind().logName();
        this.laneName = descriptor.laneKind().logName();
        this.lastChunkText = descriptor.coordinate().logText();
        this.observedHotspotNames.add(this.hotspotName);
    }

    synchronized boolean rememberHotspot(String hotspotName) {
        this.lastAccessAtMillis = System.currentTimeMillis();
        if (hotspotName == null || hotspotName.isBlank()) {
            return false;
        }
        return this.observedHotspotNames.add(hotspotName);
    }

    //  blob calculate
    synchronized ChunkGlobalStoreObservation recordObservation(
            ChunkPacketDescriptor descriptor,
            ChunkSnapshotFingerprint fingerprint,
            boolean existingBeforeObserve,
            long hotspotDistinctEntryCount,
            long totalDistinctEntryCount,
            long materializedSnapshotCount,
            long retainedBlobBytes,
            long blobBudgetBytes,
            long totalBlobEvictions
    ) {
        this.lastSeenAtMillis = System.currentTimeMillis();
        this.lastAccessAtMillis = this.lastSeenAtMillis;
        this.observationCount++;
        this.totalObservedBytes += Math.max(fingerprint.encodedBytes(), 0);
        this.hotspotName = descriptor.hotspotKind().logName();
        this.laneName = descriptor.laneKind().logName();
        this.lastChunkText = descriptor.coordinate().logText();
        return new ChunkGlobalStoreObservation(
                this.storeKey,
                this.hotspotName,
                this.laneName,
                this.snapshotHash,
                this.snapshotShortHash,
                existingBeforeObserve,
                this.observationCount,
                this.totalObservedBytes,
                this.referenceCount,
                hotspotDistinctEntryCount,
                totalDistinctEntryCount,
                materializedSnapshotCount,
                retainedBlobBytes,
                blobBudgetBytes,
                totalBlobEvictions,
                this.lastChunkText,
                this.encodedBytes
        );
    }

    // prevent this delete
    synchronized void retainReference() {
        this.referenceCount++;
        this.lastAccessAtMillis = System.currentTimeMillis();
    }

    // prevent this remain
    synchronized void releaseReference() {
        if (this.referenceCount > 0L) {
            this.referenceCount--;
        }
        this.lastAccessAtMillis = System.currentTimeMillis();
    }

    synchronized boolean evictable() {
        return this.referenceCount <= 0L;
    }

    synchronized long referenceCount() {
        return this.referenceCount;
    }

    synchronized long lastAccessAtMillis() {
        return this.lastAccessAtMillis;
    }

    synchronized int encodedBytes() {
        return this.encodedBytes;
    }

    synchronized String snapshotHash() {
        return this.snapshotHash;
    }

    synchronized String snapshotShortHash() {
        return this.snapshotShortHash;
    }

    synchronized ChunkBlobHandle copyBlobHandle() {
        this.lastAccessAtMillis = System.currentTimeMillis();
        return new ChunkBlobHandle(this.snapshotHash, this.snapshotShortHash, this.encodedBytes, this.blobBytes);
    }

    synchronized ChunkBlobRef copyBlobRef() {
        return new ChunkBlobRef(this.snapshotHash, this.snapshotShortHash, this.encodedBytes, this.referenceCount);
    }

    synchronized Set<String> observedHotspotNames() {
        return Set.copyOf(this.observedHotspotNames);
    }
}
