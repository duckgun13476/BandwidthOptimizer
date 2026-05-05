package com.PinkCats.bandwidthoptimizer.chunk.snapshot.lane;

import com.PinkCats.bandwidthoptimizer.chunk.classify.ChunkHotspotKind;
import com.PinkCats.bandwidthoptimizer.chunk.classify.ChunkLaneKind;

import java.util.Arrays;

public record ChunkLanePacketSnapshot(
        String protocolName,
        String packetClassName,
        ChunkHotspotKind hotspotKind,
        ChunkLaneKind laneKind,
        String semanticKey,
        long sequenceVersion,
        long fullSnapshotVersion,
        String payloadHash,
        String payloadShortHash,
        int encodedBytes,
        long observedAtMillis,
        byte[] originalPacketBytes
) {

    public ChunkLanePacketSnapshot {
        protocolName = protocolName == null ? "" : protocolName;
        packetClassName = packetClassName == null ? "" : packetClassName;
        semanticKey = semanticKey == null || semanticKey.isBlank() ? "default" : semanticKey;
        payloadHash = payloadHash == null ? "" : payloadHash;
        payloadShortHash = payloadShortHash == null ? "" : payloadShortHash;
        encodedBytes = Math.max(encodedBytes, 0);
        observedAtMillis = Math.max(observedAtMillis, 0L);
        originalPacketBytes = originalPacketBytes == null
                ? new byte[0]
                : Arrays.copyOf(originalPacketBytes, originalPacketBytes.length);
    }

    public byte[] copyOriginalPacketBytes() {
        return Arrays.copyOf(this.originalPacketBytes, this.originalPacketBytes.length);
    }

    public String summaryText() {
        return "packet=" + this.packetClassName
                + ", kind=" + (this.hotspotKind == null ? "<unknown>" : this.hotspotKind.logName())
                + ", lane=" + (this.laneKind == null ? "<unknown>" : this.laneKind.logName())
                + ", semanticKey=" + this.semanticKey
                + ", sequenceVersion=" + this.sequenceVersion
                + ", fullSnapshotVersion=" + this.fullSnapshotVersion
                + ", payloadHash=" + this.payloadShortHash
                + ", encodedBytes=" + this.encodedBytes;
    }
}
