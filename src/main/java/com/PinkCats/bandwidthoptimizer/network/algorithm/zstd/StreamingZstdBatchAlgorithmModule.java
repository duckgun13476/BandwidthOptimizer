package com.PinkCats.bandwidthoptimizer.network.algorithm.zstd;

import com.PinkCats.bandwidthoptimizer.Config;
import com.PinkCats.bandwidthoptimizer.network.algorithm.BatchAlgorithm;
import com.PinkCats.bandwidthoptimizer.network.algorithm.BatchAlgorithmModule;

@Deprecated(forRemoval = false)
public final class StreamingZstdBatchAlgorithmModule implements BatchAlgorithmModule {

    private static final boolean DEFAULT_ENABLED = false;
    private static final int DEFAULT_LEVEL = 4;
    private final BatchAlgorithm algorithm = new StreamingZstdBatchAlgorithm();

    @Override
    public BatchAlgorithm algorithm() {
        return this.algorithm;
    }

    @Override
    public boolean enabled() {
        return Config.enableBatchStreamingZstd;
    }

    static int level() {
        return Config.batchStreamingZstdLevel > 0 ? Config.batchStreamingZstdLevel : DEFAULT_LEVEL;
    }
}
