package com.PinkCats.bandwidthoptimizer.Old.network.client;

import com.PinkCats.bandwidthoptimizer.Old.network.batch.ChunkCachePacketBatchCodec;
import com.PinkCats.bandwidthoptimizer.Old.network.batch.ChunkCachePacketBatching;
import com.PinkCats.bandwidthoptimizer.Old.network.batch.PayloadBatching;
import com.PinkCats.bandwidthoptimizer.Old.network.message.ClientboundChunkCacheBatchPacket;

import java.util.List;

public final class ClientChunkCacheBatchHandler {

    private static final PayloadBatching.Session BATCH_DECODE_SESSION = new PayloadBatching.Session();

    private ClientChunkCacheBatchHandler() {
    }

    public static void handleBatch(ClientboundChunkCacheBatchPacket batchPacket) {
        if (batchPacket.resetSession()) {
            BATCH_DECODE_SESSION.resetAll();
        }

        List<ChunkCachePacketBatchCodec.Entry> packets = ChunkCachePacketBatching.decodeBatch(
                batchPacket.algorithmId(),
                batchPacket.encodedBytes(),
                BATCH_DECODE_SESSION
        );

        for (ChunkCachePacketBatchCodec.Entry packet : packets) {
            if (packet instanceof ChunkCachePacketBatchCodec.RefreshEntry refreshEntry) {
                ClientChunkCacheManager.handleRefresh(refreshEntry.packet());
            } else if (packet instanceof ChunkCachePacketBatchCodec.UseEntry useEntry) {
                ClientChunkCacheManager.handleUse(useEntry.packet());
            } else if (packet instanceof ChunkCachePacketBatchCodec.DeltaEntry deltaEntry) {
                ClientChunkCacheManager.handleDelta(deltaEntry.packet());
            } else {
                throw new IllegalStateException("Unsupported chunk cache batch entry: " + packet.getClass().getName());
            }
        }
    }

}
