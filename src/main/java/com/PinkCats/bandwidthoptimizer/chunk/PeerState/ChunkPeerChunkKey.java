package com.PinkCats.bandwidthoptimizer.chunk.PeerState;

import com.PinkCats.bandwidthoptimizer.chunk.classify.packet.ChunkPacketCoordinate;

public record ChunkPeerChunkKey(
        int chunkX,
        int chunkZ
) {
    public static ChunkPeerChunkKey fromCoordinate(ChunkPacketCoordinate coordinate) {
        if (coordinate == null || !coordinate.present())
            throw new IllegalArgumentException("ChunkPacketCoordinate must be present");
        return new ChunkPeerChunkKey(coordinate.chunkX(), coordinate.chunkZ());
    }

    public String logText() {return "(" + this.chunkX + ", " + this.chunkZ + ")";}

}
