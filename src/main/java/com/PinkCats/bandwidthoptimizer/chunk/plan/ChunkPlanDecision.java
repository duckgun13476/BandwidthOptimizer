package com.PinkCats.bandwidthoptimizer.chunk.plan;

public record ChunkPlanDecision(
        ChunkPlanDecisionKind decisionKind,
        String reason,
        boolean knownSnapshotPublished,
        long fullSnapshotVersion,
        long mutationVersion,
        long laneVersion,
        long deltaPacketCountSinceFullSnapshot,
        long deltaBytesSinceFullSnapshot,
        boolean reusedGlobalSnapshot,
        long globalObservationCount,
        String knownSnapshotHash,
        String knownSnapshotShortHash,
        String currentPayloadHash,
        String currentPayloadShortHash,
        String laneName,
        String chunkText,
        int encodedBytes,
        int bypassBytes,
        int fullTransportBytes,
        int refTransportBytes,
        int patchTransportBytes,
        int selectedTransportBytes
) {

    public String summaryText() {
        return "decision=" + this.decisionKind.logName()
                + ", reason=" + this.reason
                + ", knownSnapshot=" + this.knownSnapshotPublished
                + ", fullVersion=" + this.fullSnapshotVersion
                + ", mutationVersion=" + this.mutationVersion
                + ", lane=" + this.laneName
                + ", laneVersion=" + this.laneVersion
                + ", deltaPacketsSinceFull=" + this.deltaPacketCountSinceFullSnapshot
                + ", deltaBytesSinceFull=" + this.deltaBytesSinceFullSnapshot
                + ", reusedGlobalSnapshot=" + this.reusedGlobalSnapshot
                + ", globalObservationCount=" + this.globalObservationCount
                + ", knownSnapshotHash=" + this.knownSnapshotShortHash
                + ", currentPayloadHash=" + this.currentPayloadShortHash
                + ", chunk=" + this.chunkText
                + ", encodedBytes=" + this.encodedBytes
                + ", estimates={bypass=" + this.bypassBytes
                + ", full=" + this.fullTransportBytes
                + ", ref=" + this.refTransportBytes
                + ", patch=" + this.patchTransportBytes + "}"
                + ", selectedBytes=" + this.selectedTransportBytes;
    }
}
