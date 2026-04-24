package com.PinkCats.bandwidthoptimizer.chunk.state.peer;

public record ChunkPeerChunkStateSnapshot(
        ChunkPeerChunkKey chunkKey,
        long epoch,
        boolean knownSnapshotPublished,
        boolean receiverSnapshotAcknowledged,
        long totalObservedPacketCount,
        long fullSnapshotVersion,
        long acknowledgedSnapshotVersion,
        long mutationVersion,
        long lightLaneVersion,
        long sectionBlocksLaneVersion,
        long blockLaneVersion,
        long blockEntityLaneVersion,
        long deltaPacketCountSinceFullSnapshot,
        long deltaBytesSinceFullSnapshot,
        String lastHotspotKind,
        String lastLaneKind,
        String knownSnapshotHash,
        String knownSnapshotShortHash,
        String acknowledgedSnapshotHash,
        String lastPayloadHash,
        String lastPayloadShortHash,
        int lastEncodedBytes,
        int lastFullSnapshotEncodedBytes,
        long lastObservedChannelPacketCount,
        long lastObservedAtMillis,
        long lastAcknowledgedAtMillis,
        long lastNegativeAckAtMillis,
        long lastInvalidatedAtMillis
) {

    public String summaryText() {
        return "chunk=" + this.chunkKey.logText()
                + ", knownSnapshot=" + this.knownSnapshotPublished
                + ", receiverAcked=" + this.receiverSnapshotAcknowledged
                + ", fullVersion=" + this.fullSnapshotVersion
                + ", acknowledgedVersion=" + this.acknowledgedSnapshotVersion
                + ", mutationVersion=" + this.mutationVersion
                + ", laneVersions={light=" + this.lightLaneVersion
                + ", sectionBlocks=" + this.sectionBlocksLaneVersion
                + ", block=" + this.blockLaneVersion
                + ", blockEntity=" + this.blockEntityLaneVersion + "}"
                + ", deltaPacketsSinceFull=" + this.deltaPacketCountSinceFullSnapshot
                + ", deltaBytesSinceFull=" + this.deltaBytesSinceFullSnapshot
                + ", lastHotspot=" + this.lastHotspotKind
                + ", lastLane=" + this.lastLaneKind
                + ", knownSnapshotHash=" + this.knownSnapshotShortHash
                + ", acknowledgedSnapshotHash=" + shortenHash(this.acknowledgedSnapshotHash)
                + ", lastPayloadHash=" + this.lastPayloadShortHash
                + ", lastEncodedBytes=" + this.lastEncodedBytes
                + ", lastFullSnapshotBytes=" + this.lastFullSnapshotEncodedBytes;
    }

    private static String shortenHash(String hashHex) {
        if (hashHex == null || hashHex.isBlank()) {
            return "<none>";
        }
        return hashHex.length() <= 12 ? hashHex : hashHex.substring(0, 12);
    }
}
