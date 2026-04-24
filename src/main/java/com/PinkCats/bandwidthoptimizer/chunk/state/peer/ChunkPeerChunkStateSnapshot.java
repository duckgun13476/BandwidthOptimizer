package com.PinkCats.bandwidthoptimizer.chunk.state.peer;

public record ChunkPeerChunkStateSnapshot(
        ChunkPeerChunkKey chunkKey,
        long epoch,
        boolean knownSnapshotPublished,
        long totalObservedPacketCount,
        long fullSnapshotVersion,
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
        String lastPayloadHash,
        String lastPayloadShortHash,
        int lastEncodedBytes,
        int lastFullSnapshotEncodedBytes,
        long lastObservedChannelPacketCount,
        long lastObservedAtMillis
) {

    public String summaryText() {
        return "chunk=" + this.chunkKey.logText()
                + ", knownSnapshot=" + this.knownSnapshotPublished
                + ", fullVersion=" + this.fullSnapshotVersion
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
                + ", lastPayloadHash=" + this.lastPayloadShortHash
                + ", lastEncodedBytes=" + this.lastEncodedBytes
                + ", lastFullSnapshotBytes=" + this.lastFullSnapshotEncodedBytes;
    }
}
