package com.PinkCats.bandwidthoptimizer.Old.network.algorithm.legacy.dedup;

import com.PinkCats.bandwidthoptimizer.Config;
import com.PinkCats.bandwidthoptimizer.Old.network.algorithm.BatchAlgorithm;
import com.PinkCats.bandwidthoptimizer.Old.network.algorithm.BatchAlgorithmModule;

@Deprecated(forRemoval = false)
public final class ReferenceDedupBatchAlgorithmModule implements BatchAlgorithmModule {

    private static final boolean DEFAULT_ENABLED = true;
    private final BatchAlgorithm algorithm = new ReferenceDedupBatchAlgorithm();

    @Override
    public BatchAlgorithm algorithm() {
        return this.algorithm;
    }

    @Override
    public boolean enabled() {
        return Config.enableBatchReferenceDedup;
    }
}
