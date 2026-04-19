package com.PinkCats.bandwidthoptimizer.channel.algorithm.mapping;

import com.PinkCats.bandwidthoptimizer.channel.algorithm.TransportLayer;

// This placeholder layer is reserved for future map-table or template-matching logic.
public final class KineticMapTableLayer implements TransportLayer {

    @Override
    public byte[] encode(byte[] inputBytes) {
        return inputBytes;
    }

    @Override
    public byte[] decode(byte[] inputBytes) {
        return inputBytes;
    }
}
