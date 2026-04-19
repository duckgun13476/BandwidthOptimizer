package com.PinkCats.bandwidthoptimizer.channel.algorithm.batch;

import com.PinkCats.bandwidthoptimizer.channel.algorithm.KineticAlgorithmLayer;

// This placeholder layer is reserved for future packet batching logic.
public final class KineticBatchLayer implements KineticAlgorithmLayer {

    @Override
    public byte[] encode(byte[] inputBytes) {
        return inputBytes;
    }

    @Override
    public byte[] decode(byte[] inputBytes) {
        return inputBytes;
    }
}
