package com.PinkCats.bandwidthoptimizer.network.algorithm.legacy.dictionary;

import com.PinkCats.bandwidthoptimizer.Config;
import com.PinkCats.bandwidthoptimizer.network.algorithm.BatchAlgorithm;
import com.PinkCats.bandwidthoptimizer.network.algorithm.BatchAlgorithmModule;

@Deprecated(forRemoval = false)
public final class Sha256DictionaryBatchAlgorithmModule implements BatchAlgorithmModule {

    private static final boolean DEFAULT_ENABLED = true;
    private static final int DEFAULT_MAX_PACKET_BYTES = 2000;
    private static final int DEFAULT_MAX_ENTRIES = 8192;
    private static final int DEFAULT_MAX_PAYLOAD_BYTES = 1048576;
    private final BatchAlgorithm algorithm = new Sha256DictionaryBatchAlgorithm();

    @Override
    public BatchAlgorithm algorithm() {
        return this.algorithm;
    }

    @Override
    public boolean enabled() {
        return Config.enableBatchSha256Dictionary;
    }

    static int maxEntries() {
        return Config.batchSha256DictionaryMaxEntries > 0
                ? Config.batchSha256DictionaryMaxEntries
                : DEFAULT_MAX_ENTRIES;
    }

    static int maxPayloadBytes() {
        return Config.batchSha256DictionaryMaxPayloadBytes > 0
                ? Config.batchSha256DictionaryMaxPayloadBytes
                : DEFAULT_MAX_PAYLOAD_BYTES;
    }

    static int maxPacketBytes() {
        return Config.batchSha256DictionaryMaxPacketBytes > 0
                ? Config.batchSha256DictionaryMaxPacketBytes
                : DEFAULT_MAX_PACKET_BYTES;
    }
}
