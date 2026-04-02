package com.PinkCats.bandwidthoptimizer.network.algorithm.zstd;

import com.PinkCats.bandwidthoptimizer.network.algorithm.BatchAlgorithm;

import java.util.List;

public final class PostStreamingZstdBatchAlgorithm implements BatchAlgorithm {

    private final String id;
    private final BatchAlgorithm delegate;

    public PostStreamingZstdBatchAlgorithm(String id, BatchAlgorithm delegate) {
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
            private final BatchAlgorithm.Session streamingSession = new StreamingZstdBatchAlgorithm().createSession();

            @Override
            public EncodedBatch encode(List<byte[]> payloads) {
                EncodedBatch delegated = this.delegateSession.encode(payloads);
                EncodedBatch compressed = this.streamingSession.encode(List.of(delegated.bytes()));
                return new EncodedBatch(
                        compressed.bytes(),
                        delegated.entryInfos(),
                        delegated.addedMappings(),
                        delegated.removedMappings()
                );
            }

            @Override
            public List<byte[]> decode(byte[] encodedBytes) {
                List<byte[]> restored = this.streamingSession.decode(encodedBytes);
                if (restored.size() != 1) {
                    throw new IllegalStateException("Expected exactly one restored delegate payload, got " + restored.size());
                }
                return this.delegateSession.decode(restored.get(0));
            }

            @Override
            public void reset() {
                this.delegateSession.reset();
                this.streamingSession.reset();
            }
        };
    }
}
