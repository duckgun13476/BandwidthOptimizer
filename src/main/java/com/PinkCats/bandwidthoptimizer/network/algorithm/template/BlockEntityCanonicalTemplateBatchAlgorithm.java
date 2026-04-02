package com.PinkCats.bandwidthoptimizer.network.algorithm.template;

import com.PinkCats.bandwidthoptimizer.network.algorithm.BatchAlgorithm;

import com.PinkCats.bandwidthoptimizer.network.payload.BlockEntityPayloadSupport;

import java.util.List;

public final class BlockEntityCanonicalTemplateBatchAlgorithm implements BatchAlgorithm {

    @Override
    public String id() {
        return "block_entity_template";
    }

    @Override
    public Session createSession() {
        return new Session() {
            private final BatchAlgorithm.Session delegate = new TemplateDictionaryBatchAlgorithm().createSession();

            @Override
            public EncodedBatch encode(List<byte[]> payloads) {
                List<byte[]> canonicalPayloads = payloads.stream()
                        .map(BlockEntityPayloadSupport::toCanonical)
                        .toList();
                return this.delegate.encode(canonicalPayloads);
            }

            @Override
            public List<byte[]> decode(byte[] encodedBytes) {
                List<byte[]> canonicalPayloads = this.delegate.decode(encodedBytes);
                return canonicalPayloads.stream()
                        .map(BlockEntityPayloadSupport::fromCanonical)
                        .toList();
            }

            @Override
            public void reset() {
                this.delegate.reset();
            }
        };
    }
}
