package com.PinkCats.bandwidthoptimizer.network.batch;

import com.PinkCats.bandwidthoptimizer.network.message.ClientboundChunkCacheBatchPacket;

import java.util.List;

public final class ChunkCachePacketBatching {

    public static final long WINDOW_MILLIS = PayloadBatching.WINDOW_MILLIS;

    private ChunkCachePacketBatching() {
    }

    public static ChunkCachePacketBatchCodec.EncodedChunkCacheBatch encodeBatch(
            List<? extends ChunkCachePacketBatchCodec.Entry> packets,
            PayloadBatching.Session session
    ) {
        return ChunkCachePacketBatchCodec.encode(packets, session);
    }

    public static List<ChunkCachePacketBatchCodec.Entry> decodeBatch(
            String algorithmId,
            byte[] encodedBytes,
            PayloadBatching.Session session
    ) {
        return ChunkCachePacketBatchCodec.decode(algorithmId, encodedBytes, session);
    }

    public static ClientboundChunkCacheBatchPacket createBatchPacket(
            ChunkCachePacketBatchCodec.EncodedChunkCacheBatch encodedBatch,
            boolean resetSession
    ) {
        return new ClientboundChunkCacheBatchPacket(
                encodedBatch.algorithmId(),
                resetSession,
                encodedBatch.bytes(),
                encodedBatch.packetCount()
        );
    }
}
