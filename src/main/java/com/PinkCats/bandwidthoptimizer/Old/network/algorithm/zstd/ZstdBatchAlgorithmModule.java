package com.PinkCats.bandwidthoptimizer.Old.network.algorithm.zstd;

import com.PinkCats.bandwidthoptimizer.Config;
import com.PinkCats.bandwidthoptimizer.Old.network.algorithm.BatchAlgorithm;
import com.PinkCats.bandwidthoptimizer.Old.network.algorithm.BatchAlgorithmModule;

@Deprecated(forRemoval = false)
public final class ZstdBatchAlgorithmModule implements BatchAlgorithmModule {

    private static final boolean DEFAULT_ENABLED = false;
    private static final int DEFAULT_LEVEL = 3;
    private final BatchAlgorithm algorithm = new ZstdBatchAlgorithm();

    @Override
    public BatchAlgorithm algorithm() {
        return this.algorithm;
    }

    @Override
    public boolean enabled() {
        return Config.enableBatchZstd;
    }

    static int level() {
        return Config.batchZstdLevel > 0 ? Config.batchZstdLevel : DEFAULT_LEVEL;
    }
}
