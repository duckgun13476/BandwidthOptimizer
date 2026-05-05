package com.PinkCats.bandwidthoptimizer.chunk.snapshot;

import com.PinkCats.bandwidthoptimizer.chunk.classify.ChunkLaneKind;
import com.PinkCats.bandwidthoptimizer.chunk.snapshot.lane.ChunkLanePacketSnapshot;
import com.PinkCats.bandwidthoptimizer.chunk.snapshot.lane.ChunkLaneSnapshot;
import com.PinkCats.bandwidthoptimizer.chunk.snapshot.shadow.ChunkShadowSnapshot;

import java.util.ArrayList;
import java.util.List;

public final class ChunkSnapshotMaterializer {

    private ChunkSnapshotMaterializer() {}
    public static byte[] materializeFullChunkPacket(
            ChunkShadowSnapshot snapshot,
            long expectedFullSnapshotVersion,
            String expectedPayloadHash
    ) {
        if (snapshot == null || !snapshot.hasFullSnapshot()) {
            return null;
        }

        if (expectedFullSnapshotVersion > 0L && snapshot.fullSnapshotVersion() != expectedFullSnapshotVersion) {
            return null;
        }

        if (expectedPayloadHash != null
                && !expectedPayloadHash.isBlank()
                && !expectedPayloadHash.equals(snapshot.fullSnapshotHash())) {
            return null;
        }

        ChunkLanePacketSnapshot fullPacketSnapshot = snapshot.fullPacketSnapshot();
        return fullPacketSnapshot == null ? null : fullPacketSnapshot.copyOriginalPacketBytes();
    }


    public static List<byte[]> materializeLanePacketBytes(ChunkShadowSnapshot snapshot, ChunkLaneKind laneKind) {
        if (snapshot == null || laneKind == null)
            return List.of();

        ChunkLaneSnapshot laneSnapshot = snapshot.laneSnapshot(laneKind);
        if (laneSnapshot == null || laneSnapshot.packetSnapshots().isEmpty())
            return List.of();

        List<byte[]> materializedPacketBytes = new ArrayList<>(laneSnapshot.packetSnapshots().size());
        for (ChunkLanePacketSnapshot packetSnapshot : laneSnapshot.packetSnapshots().values()) {
            materializedPacketBytes.add(packetSnapshot.copyOriginalPacketBytes());
        }
        return List.copyOf(materializedPacketBytes);
    }

    // extract
    public static List<byte[]> materializeAllCurrentPacketBytes(ChunkShadowSnapshot snapshot) {
        if (snapshot == null) {
            return List.of();
        }

        List<byte[]> materializedPacketBytes = new ArrayList<>();
        for (ChunkLaneKind laneKind : ChunkLaneKind.values()) {
            materializedPacketBytes.addAll(materializeLanePacketBytes(snapshot, laneKind));
        }
        return List.copyOf(materializedPacketBytes);
    }
}
