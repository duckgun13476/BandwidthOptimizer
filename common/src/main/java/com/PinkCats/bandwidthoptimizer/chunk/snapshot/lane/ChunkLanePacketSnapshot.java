package com.PinkCats.bandwidthoptimizer.chunk.snapshot.lane;

import com.PinkCats.bandwidthoptimizer.chunk.classify.ChunkHotspotKind;
import com.PinkCats.bandwidthoptimizer.chunk.classify.ChunkLaneKind;

import java.util.Arrays;

public final class ChunkLanePacketSnapshot {

    private static final byte[] EMPTY_PAYLOAD = new byte[0];

    private final String protocolName;
    private final String packetClassName;
    private final ChunkHotspotKind hotspotKind;
    private final ChunkLaneKind laneKind;
    private final String semanticKey;
    private final long sequenceVersion;
    private final long fullSnapshotVersion;
    private final String payloadHash;
    private final String payloadShortHash;
    private final int encodedBytes;
    private final long observedAtMillis;
    private final long accessCount;
    private final long lastAccessAtMillis;
    private final byte[] originalPacketBytes;

    public ChunkLanePacketSnapshot(
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
        this(
                protocolName,
                packetClassName,
                hotspotKind,
                laneKind,
                semanticKey,
                sequenceVersion,
                fullSnapshotVersion,
                payloadHash,
                payloadShortHash,
                encodedBytes,
                observedAtMillis,
                accessCount,
                lastAccessAtMillis,
                copyPayload(originalPacketBytes),
                true
        );
    }

    private ChunkLanePacketSnapshot(
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
            byte[] ownedPacketBytes,
            boolean trustedPayload
    ) {
        this.protocolName = protocolName == null ? "" : protocolName;
        this.packetClassName = packetClassName == null ? "" : packetClassName;
        this.hotspotKind = hotspotKind;
        this.laneKind = laneKind;
        this.semanticKey = semanticKey == null || semanticKey.isBlank() ? "default" : semanticKey;
        this.sequenceVersion = sequenceVersion;
        this.fullSnapshotVersion = fullSnapshotVersion;
        this.payloadHash = payloadHash == null ? "" : payloadHash;
        this.payloadShortHash = payloadShortHash == null ? "" : payloadShortHash;
        this.encodedBytes = Math.max(encodedBytes, 0);
        this.observedAtMillis = Math.max(observedAtMillis, 0L);
        this.accessCount = Math.max(accessCount, 0L);
        this.lastAccessAtMillis = Math.max(lastAccessAtMillis, 0L);
        this.originalPacketBytes = trustedPayload && ownedPacketBytes != null
                ? ownedPacketBytes
                : copyPayload(ownedPacketBytes);
    }

    public String protocolName() {
        return this.protocolName;
    }

    public String packetClassName() {
        return this.packetClassName;
    }

    public ChunkHotspotKind hotspotKind() {
        return this.hotspotKind;
    }

    public ChunkLaneKind laneKind() {
        return this.laneKind;
    }

    public String semanticKey() {
        return this.semanticKey;
    }

    public long sequenceVersion() {
        return this.sequenceVersion;
    }

    public long fullSnapshotVersion() {
        return this.fullSnapshotVersion;
    }

    public String payloadHash() {
        return this.payloadHash;
    }

    public String payloadShortHash() {
        return this.payloadShortHash;
    }

    public int encodedBytes() {
        return this.encodedBytes;
    }

    public long observedAtMillis() {
        return this.observedAtMillis;
    }

    public long accessCount() {
        return this.accessCount;
    }

    public long lastAccessAtMillis() {
        return this.lastAccessAtMillis;
    }

    public byte[] originalPacketBytes() {
        return copyOriginalPacketBytes();
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
        return derived(this.accessCount, this.lastAccessAtMillis, EMPTY_PAYLOAD);
    }

    public ChunkLanePacketSnapshot markAccess(long accessedAtMillis) {
        return withAccessStats(this.accessCount + 1L, accessedAtMillis);
    }

    public ChunkLanePacketSnapshot withAccessStats(long accessCount, long accessedAtMillis) {
        return derived(accessCount, accessedAtMillis, this.originalPacketBytes);
    }

    private ChunkLanePacketSnapshot derived(long nextAccessCount, long nextAccessedAtMillis, byte[] ownedPacketBytes) {
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
                nextAccessCount,
                nextAccessedAtMillis,
                ownedPacketBytes,
                true
        );
    }

    private static byte[] copyPayload(byte[] payload) {
        return payload == null || payload.length == 0
                ? EMPTY_PAYLOAD
                : Arrays.copyOf(payload, payload.length);
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
