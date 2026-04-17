package com.PinkCats.bandwidthoptimizer.network.batch;

import com.PinkCats.bandwidthoptimizer.network.algorithm.BatchAlgorithm;
import com.PinkCats.bandwidthoptimizer.network.message.ClientboundChunkCacheDeltaPacket;
import com.PinkCats.bandwidthoptimizer.network.message.ClientboundChunkCacheRefreshPacket;
import com.PinkCats.bandwidthoptimizer.network.message.ClientboundChunkCacheUsePacket;
import io.netty.buffer.Unpooled;
import net.minecraft.network.FriendlyByteBuf;

import java.util.ArrayList;
import java.util.List;

public final class ChunkCachePacketBatchCodec {

    private static final int TYPE_REFRESH = 1;
    private static final int TYPE_USE = 2;
    private static final int TYPE_DELTA = 3;

    private ChunkCachePacketBatchCodec() {
    }

    public static EncodedChunkCacheBatch encode(List<? extends Entry> packets, PayloadBatching.Session session) {
        List<BatchAlgorithm.BatchInput> entries = new ArrayList<>(packets.size());
        List<Integer> payloadSizes = new ArrayList<>(packets.size());
        for (Entry packet : packets) {
            byte[] encodedPacket = encodeEntry(packet);
            entries.add(new BatchAlgorithm.BatchInput(packet.groupKey(), encodedPacket));
            payloadSizes.add(encodedPacket.length);
        }

        PayloadBatching.EncodedPayloadBatch encodedBatch = PayloadBatching.encodeEntries(entries, session);
        return new EncodedChunkCacheBatch(
                encodedBatch.algorithmId(),
                encodedBatch.bytes(),
                encodedBatch.entryInfos(),
                encodedBatch.addedMappings(),
                encodedBatch.removedMappings(),
                packets.size(),
                List.copyOf(payloadSizes)
        );
    }

    public static List<Entry> decode(String algorithmId, byte[] encodedBytes, PayloadBatching.Session session) {
        List<byte[]> payloads = PayloadBatching.decodePayloads(algorithmId, encodedBytes, session);
        List<Entry> packets = new ArrayList<>(payloads.size());
        for (byte[] payload : payloads) {
            packets.add(decodeEntry(payload));
        }
        return List.copyOf(packets);
    }

    private static byte[] encodeEntry(Entry packet) {
        FriendlyByteBuf buffer = new FriendlyByteBuf(Unpooled.buffer());
        try {
            if (packet instanceof RefreshEntry refreshEntry) {
                buffer.writeByte(TYPE_REFRESH);
                ClientboundChunkCacheRefreshPacket.encode(refreshEntry.packet(), buffer);
            } else if (packet instanceof UseEntry useEntry) {
                buffer.writeByte(TYPE_USE);
                ClientboundChunkCacheUsePacket.encode(useEntry.packet(), buffer);
            } else if (packet instanceof DeltaEntry deltaEntry) {
                buffer.writeByte(TYPE_DELTA);
                ClientboundChunkCacheDeltaPacket.encode(deltaEntry.packet(), buffer);
            } else {
                throw new IllegalArgumentException("Unsupported chunk cache batch entry: " + packet.getClass().getName());
            }
            byte[] bytes = new byte[buffer.readableBytes()];
            buffer.getBytes(0, bytes);
            return bytes;
        } finally {
            buffer.release();
        }
    }

    private static Entry decodeEntry(byte[] bytes) {
        FriendlyByteBuf buffer = new FriendlyByteBuf(Unpooled.wrappedBuffer(bytes));
        try {
            int type = buffer.readByte() & 0xFF;
            return switch (type) {
                case TYPE_REFRESH -> new RefreshEntry(ClientboundChunkCacheRefreshPacket.decode(buffer));
                case TYPE_USE -> new UseEntry(ClientboundChunkCacheUsePacket.decode(buffer));
                case TYPE_DELTA -> new DeltaEntry(ClientboundChunkCacheDeltaPacket.decode(buffer));
                default -> throw new IllegalStateException("Unknown chunk cache batch packet type " + type);
            };
        } finally {
            buffer.release();
        }
    }

    public interface Entry {
        String groupKey();
    }

    public record RefreshEntry(ClientboundChunkCacheRefreshPacket packet) implements Entry {
        @Override
        public String groupKey() {
            return "chunk_cache_refresh";
        }
    }

    public record UseEntry(ClientboundChunkCacheUsePacket packet) implements Entry {
        @Override
        public String groupKey() {
            return "chunk_cache_use";
        }
    }

    public record DeltaEntry(ClientboundChunkCacheDeltaPacket packet) implements Entry {
        @Override
        public String groupKey() {
            return "chunk_cache_delta";
        }
    }

    public record EncodedChunkCacheBatch(
            String algorithmId,
            byte[] bytes,
            List<BatchAlgorithm.EntryInfo> entryInfos,
            int addedMappings,
            int removedMappings,
            int packetCount,
            List<Integer> entryPayloadSizes
    ) {
    }
}
