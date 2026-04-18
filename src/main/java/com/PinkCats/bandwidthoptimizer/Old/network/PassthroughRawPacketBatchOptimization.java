package com.PinkCats.bandwidthoptimizer.Old.network;

import com.PinkCats.bandwidthoptimizer.Old.network.algorithm.BatchAlgorithm;
import com.PinkCats.bandwidthoptimizer.Old.network.algorithm.PassthroughBatchAlgorithm;

@Deprecated(forRemoval = false)
public final class PassthroughRawPacketBatchOptimization implements RawPacketBatchOptimization {

    private final PassthroughBatchAlgorithm delegate = new PassthroughBatchAlgorithm();
    private final BatchAlgorithm.Session session = this.delegate.createSession();

    @Override
    public String id() {
        return this.delegate.id();
    }

    @Override
    public BatchAlgorithm.EncodedBatch encode(java.util.List<byte[]> payloads) {
        return this.session.encode(payloads);
    }

    @Override
    public java.util.List<byte[]> decode(byte[] encodedBytes) {
        return this.session.decode(encodedBytes);
    }
}
