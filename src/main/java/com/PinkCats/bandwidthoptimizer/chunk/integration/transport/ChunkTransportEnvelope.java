package com.PinkCats.bandwidthoptimizer.chunk.integration.transport;

import com.PinkCats.bandwidthoptimizer.chunk.protocol.ChunkHotspotFrame;

import java.util.Arrays;

public record ChunkTransportEnvelope(
        ChunkHotspotFrame frame,
        byte[] originalPacketBytes
) {

    public ChunkTransportEnvelope {
        if (frame == null) {
            throw new IllegalArgumentException("frame must not be null");
        }
        originalPacketBytes = copyBytes(originalPacketBytes);
    }

    public byte[] copyOriginalPacketBytes() {
        return copyBytes(this.originalPacketBytes);
    }

    private static byte[] copyBytes(byte[] packetBytes) {
        return packetBytes == null ? new byte[0] : Arrays.copyOf(packetBytes, packetBytes.length);
    }
}
