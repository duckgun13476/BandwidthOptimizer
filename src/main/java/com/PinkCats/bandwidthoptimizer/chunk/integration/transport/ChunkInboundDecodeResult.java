package com.PinkCats.bandwidthoptimizer.chunk.integration.transport;


public record ChunkInboundDecodeResult(
        boolean consumeAsChunkControlFrame,
        byte[] restoredPacketBytes
) {


    public static ChunkInboundDecodeResult passthrough(byte[] restoredPacketBytes) {
        return new ChunkInboundDecodeResult(false, restoredPacketBytes);
    }

    public static ChunkInboundDecodeResult consumeControlFrame() {
        return new ChunkInboundDecodeResult(true, null);
    }

    public boolean shouldDecodeVanillaPacket() {
        return this.restoredPacketBytes != null;
    }
}
