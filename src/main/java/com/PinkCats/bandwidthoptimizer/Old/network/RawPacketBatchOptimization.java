package com.PinkCats.bandwidthoptimizer.Old.network;

import com.PinkCats.bandwidthoptimizer.Old.network.algorithm.BatchAlgorithm;

@Deprecated(forRemoval = false)
public interface RawPacketBatchOptimization {

    String id();

    BatchAlgorithm.EncodedBatch encode(java.util.List<byte[]> payloads);

    java.util.List<byte[]> decode(byte[] encodedBytes);
}
