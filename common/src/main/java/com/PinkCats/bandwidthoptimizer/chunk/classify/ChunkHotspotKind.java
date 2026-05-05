package com.PinkCats.bandwidthoptimizer.chunk.classify;

public enum ChunkHotspotKind {

    FULL_CHUNK("full_chunk", ChunkLaneKind.FULL),
    LIGHT_UPDATE("light_update", ChunkLaneKind.LIGHT),
    SECTION_BLOCKS_UPDATE("section_blocks_update", ChunkLaneKind.SECTION_BLOCKS),
    BLOCK_UPDATE("block_update", ChunkLaneKind.BLOCK),
    BLOCK_ENTITY_UPDATE("block_entity_update", ChunkLaneKind.BLOCK_ENTITY);

    private final String logName;
    private final ChunkLaneKind laneKind;

    ChunkHotspotKind(String logName, ChunkLaneKind laneKind) {
        this.logName = logName;
        this.laneKind = laneKind;
    }

    public String logName() {return this.logName;}
    public ChunkLaneKind laneKind() {return this.laneKind;}

    public static ChunkHotspotKind fromLogName(String logName) {
        for (ChunkHotspotKind hotspotKind : values()) {
            if (hotspotKind.logName.equals(logName)) {
                return hotspotKind;
            }
        }
        throw new IllegalArgumentException("Unknown chunk hotspot logName: " + logName);
    }
}
