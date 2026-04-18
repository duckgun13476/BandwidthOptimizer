package com.PinkCats.bandwidthoptimizer.Old.network.algorithm.template;

import com.PinkCats.bandwidthoptimizer.Config;
import com.PinkCats.bandwidthoptimizer.Old.network.algorithm.BatchAlgorithm;
import com.PinkCats.bandwidthoptimizer.Old.network.algorithm.BatchAlgorithmModule;

public final class TemplateDictionaryBatchAlgorithmModule implements BatchAlgorithmModule {

    private static final boolean DEFAULT_ENABLED = true;
    private static final int DEFAULT_MAX_PACKET_BYTES = 4096;
    private static final int DEFAULT_MAX_ENTRIES = 8192;
    private static final int DEFAULT_MAX_PAYLOAD_BYTES = 2097152;
    private static final int DEFAULT_MAX_DIFF_RUNS = 8;
    private static final int DEFAULT_MAX_CHANGED_BYTES = 128;
    private final BatchAlgorithm algorithm = new TemplateDictionaryBatchAlgorithm();

    @Override
    public BatchAlgorithm algorithm() {
        return this.algorithm;
    }

    @Override
    public boolean enabled() {
        return Config.enableBatchTemplateDictionary;
    }

    static int maxPacketBytes() {
        return Config.batchTemplateDictionaryMaxPacketBytes > 0
                ? Config.batchTemplateDictionaryMaxPacketBytes
                : DEFAULT_MAX_PACKET_BYTES;
    }

    static int maxEntries() {
        return Config.batchTemplateDictionaryMaxEntries > 0
                ? Config.batchTemplateDictionaryMaxEntries
                : DEFAULT_MAX_ENTRIES;
    }

    static int maxPayloadBytes() {
        return Config.batchTemplateDictionaryMaxPayloadBytes > 0
                ? Config.batchTemplateDictionaryMaxPayloadBytes
                : DEFAULT_MAX_PAYLOAD_BYTES;
    }

    static int maxDiffRuns() {
        return Config.batchTemplateDictionaryMaxDiffRuns > 0
                ? Config.batchTemplateDictionaryMaxDiffRuns
                : DEFAULT_MAX_DIFF_RUNS;
    }

    static int maxChangedBytes() {
        return Config.batchTemplateDictionaryMaxChangedBytes > 0
                ? Config.batchTemplateDictionaryMaxChangedBytes
                : DEFAULT_MAX_CHANGED_BYTES;
    }
}
