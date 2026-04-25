package com.PinkCats.bandwidthoptimizer.chunk.protocol.hotspot;

import com.PinkCats.bandwidthoptimizer.chunk.classify.ChunkHotspotKind;
import com.PinkCats.bandwidthoptimizer.chunk.classify.ChunkLaneKind;
import com.PinkCats.bandwidthoptimizer.chunk.classify.packet.ChunkPacketCoordinate;


// Output Summary
public record ChunkHotspotFrame(
        int protocolVersion,
        ChunkHotspotFrameOp operation,
        long epoch,
        long observedPacketCount,
        String protocolName,
        String packetClassName,
        ChunkHotspotKind hotspotKind,
        ChunkLaneKind laneKind,
        ChunkPacketCoordinate coordinate,
        int originalEncodedBytes,
        long fullSnapshotVersion,
        long laneVersion,
        String baseSnapshotHash,
        String payloadHash,
        long deltaBytesSinceFullSnapshot,
        String reason
) {


    public String summaryText() {
        return "version=" + this.protocolVersion
                + ", op=" + this.operation.logName()
                + ", protocol=" + this.protocolName
                + ", packet=" + this.packetClassName
                + ", kind=" + this.hotspotKind.logName()
                + ", lane=" + this.laneKind.logName()
                + ", chunk=" + (this.coordinate == null ? "<null>" : this.coordinate.logText())
                + ", originalEncodedBytes=" + this.originalEncodedBytes
                + ", fullSnapshotVersion=" + this.fullSnapshotVersion
                + ", laneVersion=" + this.laneVersion
                + ", baseSnapshotHash=" + shortenHash(this.baseSnapshotHash)
                + ", payloadHash=" + shortenHash(this.payloadHash)
                + ", deltaBytesSinceFull=" + this.deltaBytesSinceFullSnapshot
                + ", reason=" + this.reason
                + ", observedPackets=" + this.observedPacketCount;
    }


    private static String shortenHash(String hashHex) {
        if (hashHex == null || hashHex.isBlank()) {
            return "<none>";
        }
        return hashHex.length() <= 12 ? hashHex : hashHex.substring(0, 12);
    }
}
