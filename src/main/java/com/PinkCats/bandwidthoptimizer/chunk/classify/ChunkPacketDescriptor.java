package com.PinkCats.bandwidthoptimizer.chunk.classify;

public record ChunkPacketDescriptor(
        String protocolName,
        String packetClassName,
        ChunkHotspotKind hotspotKind,
        ChunkLaneKind laneKind,
        ChunkPacketCoordinate coordinate
) {

    public boolean hasChunkCoordinate() {
        return this.coordinate != null && this.coordinate.present();
    }

    public String summaryText() {
        return "protocol=" + this.protocolName
                + ", packet=" + this.packetClassName
                + ", kind=" + this.hotspotKind.logName()
                + ", lane=" + this.laneKind.logName()
                + ", chunk=" + (this.coordinate == null ? "<null>" : this.coordinate.logText());
    }
}
