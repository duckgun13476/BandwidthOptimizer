package com.PinkCats.bandwidthoptimizer.chunk.PeerState;

import com.PinkCats.bandwidthoptimizer.chunk.classify.ChunkHotspotKind;
import com.PinkCats.bandwidthoptimizer.chunk.classify.packet.ChunkPacketDescriptor;
import com.PinkCats.bandwidthoptimizer.chunk.snapshot.ChunkSnapshotFingerprint;

final class ChunkPeerChunkState {

    private final ChunkPeerChunkKey chunkKey;
    private boolean knownSnapshotPublished;
    private boolean receiverSnapshotAcknowledged;
    private boolean fullReplayRequiredBeforeDelta;
    private long totalObservedPacketCount;
    private long fullSnapshotVersion;
    private long acknowledgedSnapshotVersion;
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
    private String acknowledgedSnapshotHash = "";
    private String lastPayloadHash = "";
    private String lastPayloadShortHash = "";
    private int lastEncodedBytes;
    private int lastFullSnapshotEncodedBytes;
    private long lastObservedChannelPacketCount;
    private long lastObservedAtMillis;
    private long lastAcknowledgedAtMillis;
    private long lastNegativeAckAtMillis;
    private long lastInvalidatedAtMillis;
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
        boolean snapshotChanged = !this.lastPayloadHash.equals(this.knownSnapshotHash);
        if (snapshotChanged) {
            this.fullSnapshotVersion++;
            clearReceiverAcknowledgement();
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
        this.fullReplayRequiredBeforeDelta = false;
    }


    private void recordDeltaBudget() {
        this.deltaPacketCountSinceFullSnapshot++;
        this.deltaBytesSinceFullSnapshot += this.lastEncodedBytes;
    }


    ChunkPeerChunkStateSnapshot recordAcknowledgement(long fullSnapshotVersion, String acknowledgedSnapshotHash) {
        this.lastAcknowledgedAtMillis = System.currentTimeMillis();
        if (!this.knownSnapshotPublished
                || fullSnapshotVersion <= 0L
                || this.fullSnapshotVersion != fullSnapshotVersion
                || acknowledgedSnapshotHash == null
                || acknowledgedSnapshotHash.isBlank()
                || !acknowledgedSnapshotHash.equals(this.knownSnapshotHash)) {
            return snapshot();
        }

        this.receiverSnapshotAcknowledged = true;
        this.acknowledgedSnapshotVersion = fullSnapshotVersion;
        this.acknowledgedSnapshotHash = acknowledgedSnapshotHash;
        this.fullReplayRequiredBeforeDelta = false;
        return snapshot();
    }

    ChunkPeerChunkStateSnapshot recordNegativeAcknowledgement() {
        this.lastNegativeAckAtMillis = System.currentTimeMillis();
        clearReceiverAcknowledgement();
        return snapshot();
    }

    ChunkPeerChunkStateSnapshot recordInvalidate() {
        this.lastInvalidatedAtMillis = System.currentTimeMillis();
        clearReceiverAcknowledgement();
        this.knownSnapshotPublished = false;
        this.fullReplayRequiredBeforeDelta = false;
        this.knownSnapshotHash = "";
        this.knownSnapshotShortHash = "";
        this.deltaPacketCountSinceFullSnapshot = 0L;
        this.deltaBytesSinceFullSnapshot = 0L;
        this.lightLaneVersion = 0L;
        this.sectionBlocksLaneVersion = 0L;
        this.blockLaneVersion = 0L;
        this.blockEntityLaneVersion = 0L;
        return snapshot();
    }

    ChunkPeerChunkStateSnapshot recordPersistentClientManifest(
            long epoch,
            long fullSnapshotVersion,
            String payloadHash,
            int encodedBytes
    ) {
        this.lastAcknowledgedAtMillis = System.currentTimeMillis();
        if (payloadHash == null || payloadHash.isBlank()) {
            return snapshot();
        }
        if (this.knownSnapshotPublished
                && this.knownSnapshotHash != null
                && !this.knownSnapshotHash.isBlank()
                && !this.knownSnapshotHash.equals(payloadHash)) {
            return snapshot();
        }

        this.epoch = Math.max(epoch, 0L);
        this.knownSnapshotPublished = true;
        this.receiverSnapshotAcknowledged = true;
        this.fullReplayRequiredBeforeDelta = false;
        this.fullSnapshotVersion = Math.max(Math.max(this.fullSnapshotVersion, fullSnapshotVersion), 1L);
        this.acknowledgedSnapshotVersion = this.fullSnapshotVersion;
        this.knownSnapshotHash = payloadHash;
        this.knownSnapshotShortHash = shortenHash(payloadHash);
        this.acknowledgedSnapshotHash = payloadHash;
        this.lastPayloadHash = payloadHash;
        this.lastPayloadShortHash = this.knownSnapshotShortHash;
        this.lastHotspotKind = ChunkHotspotKind.FULL_CHUNK.logName();
        this.lastLaneKind = "full";
        this.lastEncodedBytes = Math.max(encodedBytes, 0);
        this.lastFullSnapshotEncodedBytes = Math.max(encodedBytes, 0);
        this.lastObservedAtMillis = this.lastAcknowledgedAtMillis;
        this.lightLaneVersion = 0L;
        this.sectionBlocksLaneVersion = 0L;
        this.blockLaneVersion = 0L;
        this.blockEntityLaneVersion = 0L;
        this.deltaPacketCountSinceFullSnapshot = 0L;
        this.deltaBytesSinceFullSnapshot = 0L;
        return snapshot();
    }

    ChunkPeerChunkStateSnapshot recordWatchBoundaryRetainCache() {
        this.lastInvalidatedAtMillis = System.currentTimeMillis();
        if (!this.knownSnapshotPublished) {
            return snapshot();
        }
        clearReceiverAcknowledgement();
        this.fullReplayRequiredBeforeDelta = true;
        this.lightLaneVersion = 0L;
        this.sectionBlocksLaneVersion = 0L;
        this.blockLaneVersion = 0L;
        this.blockEntityLaneVersion = 0L;
        return snapshot();
    }

    ChunkPeerChunkStateSnapshot snapshotForQuery() {
        return snapshot();
    }

    private void clearReceiverAcknowledgement() {
        this.receiverSnapshotAcknowledged = false;
        this.acknowledgedSnapshotVersion = 0L;
        this.acknowledgedSnapshotHash = "";
    }

    private static String shortenHash(String hashHex) {
        if (hashHex == null || hashHex.isBlank()) {
            return "";
        }
        return hashHex.length() <= 12 ? hashHex : hashHex.substring(0, 12);
    }


    private ChunkPeerChunkStateSnapshot snapshot() {
        return new ChunkPeerChunkStateSnapshot(
                this.chunkKey,
                this.epoch,
                this.knownSnapshotPublished,
                this.receiverSnapshotAcknowledged,
                this.fullReplayRequiredBeforeDelta,
                this.totalObservedPacketCount,
                this.fullSnapshotVersion,
                this.acknowledgedSnapshotVersion,
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
                this.acknowledgedSnapshotHash,
                this.lastPayloadHash,
                this.lastPayloadShortHash,
                this.lastEncodedBytes,
                this.lastFullSnapshotEncodedBytes,
                this.lastObservedChannelPacketCount,
                this.lastObservedAtMillis,
                this.lastAcknowledgedAtMillis,
                this.lastNegativeAckAtMillis,
                this.lastInvalidatedAtMillis
        );
    }
}
