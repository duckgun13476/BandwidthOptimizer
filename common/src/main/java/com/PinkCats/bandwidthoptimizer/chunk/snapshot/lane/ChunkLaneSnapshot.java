package com.PinkCats.bandwidthoptimizer.chunk.snapshot.lane;

import com.PinkCats.bandwidthoptimizer.chunk.classify.ChunkLaneKind;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;

public record ChunkLaneSnapshot(
        ChunkLaneKind laneKind,
        long laneVersion,
        String latestSemanticKey,
        Map<String, ChunkLanePacketSnapshot> packetSnapshots
) {

    public ChunkLaneSnapshot {
        latestSemanticKey = latestSemanticKey == null ? "" : latestSemanticKey;
        LinkedHashMap<String, ChunkLanePacketSnapshot> safeSnapshots = new LinkedHashMap<>();
        if (packetSnapshots != null) {
            safeSnapshots.putAll(packetSnapshots);
        }
        packetSnapshots = Collections.unmodifiableMap(safeSnapshots);
        laneVersion = Math.max(laneVersion, 0L);
    }

    public int packetCount() {
        return this.packetSnapshots.size();
    }

    public ChunkLanePacketSnapshot latestPacket() {
        if (this.packetSnapshots.isEmpty()) {
            return null;
        }

        ChunkLanePacketSnapshot latestPacket = this.packetSnapshots.get(this.latestSemanticKey);
        if (latestPacket != null) {
            return latestPacket;
        }

        ChunkLanePacketSnapshot fallbackPacket = null;
        for (ChunkLanePacketSnapshot packetSnapshot : this.packetSnapshots.values()) {
            fallbackPacket = packetSnapshot;
        }
        return fallbackPacket;
    }

    public ChunkLanePacketSnapshot packet(String semanticKey) {
        if (semanticKey == null || semanticKey.isBlank()) {
            return latestPacket();
        }
        return this.packetSnapshots.get(semanticKey);
    }

    public String summaryText() {
        return "lane=" + (this.laneKind == null ? "<unknown>" : this.laneKind.logName())
                + ", laneVersion=" + this.laneVersion
                + ", packetCount=" + this.packetSnapshots.size()
                + ", latestSemanticKey=" + (this.latestSemanticKey.isBlank() ? "<none>" : this.latestSemanticKey);
    }
}
