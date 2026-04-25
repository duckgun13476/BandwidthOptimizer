package com.PinkCats.bandwidthoptimizer.chunk.snapshot.shadow;

import com.PinkCats.bandwidthoptimizer.chunk.classify.ChunkLaneKind;
import com.PinkCats.bandwidthoptimizer.chunk.classify.packet.ChunkPacketCoordinate;
import com.PinkCats.bandwidthoptimizer.chunk.snapshot.lane.ChunkLanePacketSnapshot;
import com.PinkCats.bandwidthoptimizer.chunk.snapshot.lane.ChunkLaneSnapshot;

import java.util.Collections;
import java.util.EnumMap;
import java.util.Map;

public record ChunkShadowSnapshot(
        ChunkPacketCoordinate coordinate,
        long epoch,
        long mutationVersion,
        long fullSnapshotVersion,
        String fullSnapshotHash,
        String fullSnapshotShortHash,
        long lastUpdatedAtMillis,
        Map<ChunkLaneKind, ChunkLaneSnapshot> laneSnapshots
) {

    public ChunkShadowSnapshot {
        coordinate = coordinate == null ? ChunkPacketCoordinate.unknown() : coordinate;
        epoch = Math.max(epoch, 0L);
        mutationVersion = Math.max(mutationVersion, 0L);
        fullSnapshotVersion = Math.max(fullSnapshotVersion, 0L);
        fullSnapshotHash = fullSnapshotHash == null ? "" : fullSnapshotHash;
        fullSnapshotShortHash = fullSnapshotShortHash == null ? "" : fullSnapshotShortHash;
        lastUpdatedAtMillis = Math.max(lastUpdatedAtMillis, 0L);

        EnumMap<ChunkLaneKind, ChunkLaneSnapshot> safeLaneSnapshots = new EnumMap<>(ChunkLaneKind.class);
        if (laneSnapshots != null) {
            safeLaneSnapshots.putAll(laneSnapshots);
        }
        laneSnapshots = Collections.unmodifiableMap(safeLaneSnapshots);
    }

    public boolean hasFullSnapshot() {
        return this.fullSnapshotVersion > 0L
                && !this.fullSnapshotHash.isBlank()
                && this.laneSnapshots.containsKey(ChunkLaneKind.FULL);
    }

    public ChunkLaneSnapshot laneSnapshot(ChunkLaneKind laneKind) {
        return laneKind == null ? null : this.laneSnapshots.get(laneKind);
    }

    public ChunkLanePacketSnapshot fullPacketSnapshot() {
        ChunkLaneSnapshot fullLaneSnapshot = laneSnapshot(ChunkLaneKind.FULL);
        return fullLaneSnapshot == null ? null : fullLaneSnapshot.latestPacket();
    }

    public String summaryText() {
        return "chunk=" + this.coordinate.logText()
                + ", epoch=" + this.epoch
                + ", mutationVersion=" + this.mutationVersion
                + ", fullSnapshotVersion=" + this.fullSnapshotVersion
                + ", fullSnapshotHash=" + (this.fullSnapshotShortHash.isBlank() ? "<none>" : this.fullSnapshotShortHash)
                + ", laneCount=" + this.laneSnapshots.size();
    }
}
