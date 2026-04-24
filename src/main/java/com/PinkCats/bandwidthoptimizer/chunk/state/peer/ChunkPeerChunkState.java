package com.PinkCats.bandwidthoptimizer.chunk.state.peer;

import com.PinkCats.bandwidthoptimizer.chunk.classify.ChunkHotspotKind;
import com.PinkCats.bandwidthoptimizer.chunk.classify.ChunkPacketDescriptor;
import com.PinkCats.bandwidthoptimizer.chunk.snapshot.ChunkSnapshotFingerprint;

final class ChunkPeerChunkState {

    private final ChunkPeerChunkKey chunkKey;
    private boolean knownSnapshotPublished;
    private long totalObservedPacketCount;
    private long fullSnapshotVersion;
    private long mutationVersion;
    private long lightLaneVersion;
    private long sectionBlocksLaneVersion;
    private long blockLaneVersion;
    private long blockEntityLaneVersion;
    private long deltaPacketCountSinceFullSnapshot;
    private long deltaBytesSinceFullSnapshot;
    private String lastHotspotKind = "";
    private String lastLaneKind = "";
    private String knownSnapshotHash = "";
    private String knownSnapshotShortHash = "";
    private String lastPayloadHash = "";
    private String lastPayloadShortHash = "";
    private int lastEncodedBytes;
    private int lastFullSnapshotEncodedBytes;
    private long lastObservedChannelPacketCount;
    private long lastObservedAtMillis;
    private long epoch;

    ChunkPeerChunkState(ChunkPeerChunkKey chunkKey) {
        this.chunkKey = chunkKey;
    }

    // Observation Central
    ChunkPeerChunkStateSnapshot recordObservation(
            ChunkPacketDescriptor descriptor,
            ChunkSnapshotFingerprint snapshotFingerprint,
            long epoch,
            long channelObservedPacketCount
    ) {
        this.epoch = Math.max(epoch, 0L);
        this.totalObservedPacketCount++;
        this.mutationVersion++;
        this.lastObservedAtMillis = System.currentTimeMillis();
        this.lastObservedChannelPacketCount = Math.max(channelObservedPacketCount, 0L);
        this.lastHotspotKind = descriptor.hotspotKind().logName();
        this.lastLaneKind = descriptor.laneKind().logName();
        this.lastEncodedBytes = snapshotFingerprint == null ? 0 : Math.max(snapshotFingerprint.encodedBytes(), 0);
        this.lastPayloadHash = snapshotFingerprint == null ? "" : snapshotFingerprint.hashHex();
        this.lastPayloadShortHash = snapshotFingerprint == null ? "" : snapshotFingerprint.shortHash();

        if (descriptor.hotspotKind() == ChunkHotspotKind.FULL_CHUNK) {
            applyFullSnapshotObservation();
        } else if (descriptor.hotspotKind() == ChunkHotspotKind.LIGHT_UPDATE) {
            this.lightLaneVersion++;
            recordDeltaBudget();
        } else if (descriptor.hotspotKind() == ChunkHotspotKind.SECTION_BLOCKS_UPDATE) {
            this.sectionBlocksLaneVersion++;
            recordDeltaBudget();
        } else if (descriptor.hotspotKind() == ChunkHotspotKind.BLOCK_UPDATE) {
            this.blockLaneVersion++;
            recordDeltaBudget();
        } else if (descriptor.hotspotKind() == ChunkHotspotKind.BLOCK_ENTITY_UPDATE) {
            this.blockEntityLaneVersion++;
            recordDeltaBudget();
        }

        return snapshot();
    }

    private void applyFullSnapshotObservation() {
        this.knownSnapshotPublished = true;
        if (!this.lastPayloadHash.equals(this.knownSnapshotHash)) {
            this.fullSnapshotVersion++;
        }
        this.knownSnapshotHash = this.lastPayloadHash;
        this.knownSnapshotShortHash = this.lastPayloadShortHash;
        this.lightLaneVersion = 0L;
        this.sectionBlocksLaneVersion = 0L;
        this.blockLaneVersion = 0L;
        this.blockEntityLaneVersion = 0L;
        this.deltaPacketCountSinceFullSnapshot = 0L;
        this.deltaBytesSinceFullSnapshot = 0L;
        this.lastFullSnapshotEncodedBytes = this.lastEncodedBytes;
    }


    private void recordDeltaBudget() {
        this.deltaPacketCountSinceFullSnapshot++;
        this.deltaBytesSinceFullSnapshot += this.lastEncodedBytes;
    }

    ChunkPeerChunkStateSnapshot snapshotForQuery() {
        return snapshot();
    }


    private ChunkPeerChunkStateSnapshot snapshot() {
        return new ChunkPeerChunkStateSnapshot(
                this.chunkKey,
                this.epoch,
                this.knownSnapshotPublished,
                this.totalObservedPacketCount,
                this.fullSnapshotVersion,
                this.mutationVersion,
                this.lightLaneVersion,
                this.sectionBlocksLaneVersion,
                this.blockLaneVersion,
                this.blockEntityLaneVersion,
                this.deltaPacketCountSinceFullSnapshot,
                this.deltaBytesSinceFullSnapshot,
                this.lastHotspotKind,
                this.lastLaneKind,
                this.knownSnapshotHash,
                this.knownSnapshotShortHash,
                this.lastPayloadHash,
                this.lastPayloadShortHash,
                this.lastEncodedBytes,
                this.lastFullSnapshotEncodedBytes,
                this.lastObservedChannelPacketCount,
                this.lastObservedAtMillis
        );
    }
}
