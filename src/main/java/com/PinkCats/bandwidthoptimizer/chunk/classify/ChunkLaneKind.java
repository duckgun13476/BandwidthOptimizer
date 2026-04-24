package com.PinkCats.bandwidthoptimizer.chunk.classify;

public enum ChunkLaneKind {

    FULL("full"),
    LIGHT("light"),
    SECTION_BLOCKS("section_blocks"),
    BLOCK("block"),
    BLOCK_ENTITY("block_entity");

    private final String logName;
    public String logName() {return this.logName;}



    ChunkLaneKind(String logName) {this.logName = logName;}


    public static ChunkLaneKind fromLogName(String logName) {
        for (ChunkLaneKind laneKind : values()) {
            if (laneKind.logName.equals(logName)) {
                return laneKind;
            }
        }
        throw new IllegalArgumentException("Unknown chunk lane logName: " + logName);
    }
}
