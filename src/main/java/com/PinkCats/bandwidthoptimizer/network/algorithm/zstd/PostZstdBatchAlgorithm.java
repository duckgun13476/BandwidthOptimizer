package com.PinkCats.bandwidthoptimizer.network.algorithm.zstd;

import com.PinkCats.bandwidthoptimizer.network.algorithm.BatchAlgorithm;

import java.util.List;

@Deprecated(forRemoval = false)
public final class PostZstdBatchAlgorithm implements BatchAlgorithm {

    private final String id;
    private final BatchAlgorithm delegate;

    public PostZstdBatchAlgorithm(String id, BatchAlgorithm delegate) {
        this.id = id;
        this.delegate = delegate;
    }

    @Override
    public String id() {
        return this.id;
    }

    @Override
    public Session createSession() {
        return new Session() {
            private final BatchAlgorithm.Session delegateSession = delegate.createSession();

            @Override
            public EncodedBatch encode(List<byte[]> payloads) {
                EncodedBatch delegated = this.delegateSession.encode(payloads);
                byte[] bytes = ZstdAlgorithmSupport.compressOrStore(delegated.bytes(), ZstdBatchAlgorithmModule.level());
                return new EncodedBatch(
                        bytes,
                        delegated.entryInfos(),
                        delegated.addedMappings(),
                        delegated.removedMappings()
                );
            }

            @Override
            public List<byte[]> decode(byte[] encodedBytes) {
                byte[] restored = ZstdAlgorithmSupport.restore(encodedBytes);
                return this.delegateSession.decode(restored);
            }

            @Override
            public void reset() {
                this.delegateSession.reset();
            }
        };
    }
}
