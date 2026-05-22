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
        long accessCount,
        long lastAccessAtMillis,
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
        accessCount = Math.max(accessCount, 0L);
        lastAccessAtMillis = Math.max(lastAccessAtMillis, 0L);
        originalPacketBytes = originalPacketBytes == null
                ? new byte[0]
                : Arrays.copyOf(originalPacketBytes, originalPacketBytes.length);
    }

    public byte[] copyOriginalPacketBytes() {
        return Arrays.copyOf(this.originalPacketBytes, this.originalPacketBytes.length);
    }

    public boolean hasOriginalPacketBytes() {
        return this.originalPacketBytes.length > 0;
    }

    public long retainedOriginalBytes() {
        return this.originalPacketBytes.length;
    }

    public ChunkLanePacketSnapshot withoutOriginalPacketBytes() {
        return new ChunkLanePacketSnapshot(
                this.protocolName,
                this.packetClassName,
                this.hotspotKind,
                this.laneKind,
                this.semanticKey,
                this.sequenceVersion,
                this.fullSnapshotVersion,
                this.payloadHash,
                this.payloadShortHash,
                this.encodedBytes,
                this.observedAtMillis,
                this.accessCount,
                this.lastAccessAtMillis,
                new byte[0]
        );
    }

    public ChunkLanePacketSnapshot markAccess(long accessedAtMillis) {
        return withAccessStats(this.accessCount + 1L, accessedAtMillis);
    }

    public ChunkLanePacketSnapshot withAccessStats(long accessCount, long accessedAtMillis) {
        return new ChunkLanePacketSnapshot(
                this.protocolName,
                this.packetClassName,
                this.hotspotKind,
                this.laneKind,
                this.semanticKey,
                this.sequenceVersion,
                this.fullSnapshotVersion,
                this.payloadHash,
                this.payloadShortHash,
                this.encodedBytes,
                this.observedAtMillis,
                accessCount,
                accessedAtMillis,
                this.originalPacketBytes
        );
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
