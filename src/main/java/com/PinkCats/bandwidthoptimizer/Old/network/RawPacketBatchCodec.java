package com.PinkCats.bandwidthoptimizer.Old.network;

import com.PinkCats.bandwidthoptimizer.Old.network.algorithm.BatchAlgorithm;
import com.PinkCats.bandwidthoptimizer.Old.network.algorithm.legacy.dedup.ReferenceDedupBatchAlgorithm;

@Deprecated(forRemoval = false)
public final class RawPacketBatchCodec implements RawPacketBatchOptimization {

    private final ReferenceDedupBatchAlgorithm delegate = new ReferenceDedupBatchAlgorithm();
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
