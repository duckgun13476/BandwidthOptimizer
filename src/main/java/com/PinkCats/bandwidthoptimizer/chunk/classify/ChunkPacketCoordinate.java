package com.PinkCats.bandwidthoptimizer.chunk.classify;

public record ChunkPacketCoordinate(
        boolean present,
        int chunkX,
        int chunkZ
) {

    public static ChunkPacketCoordinate unknown() {
        return new ChunkPacketCoordinate(false, 0, 0);
    }

    public static ChunkPacketCoordinate ofChunk(int chunkX, int chunkZ) {
        return new ChunkPacketCoordinate(true, chunkX, chunkZ);
    }


    public String logText() {
        if (!this.present) {
            return "<unknown>";
        }
        return "(" + this.chunkX + ", " + this.chunkZ + ")";
    }
}
