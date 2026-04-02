package com.PinkCats.bandwidthoptimizer.network.algorithm.zstd;

import com.PinkCats.bandwidthoptimizer.network.algorithm.BatchAlgorithm;
import com.PinkCats.bandwidthoptimizer.network.algorithm.BatchAlgorithmSupport;

import java.util.ArrayList;
import java.util.List;

@Deprecated(forRemoval = false)
public final class ZstdBatchAlgorithm implements BatchAlgorithm {

    @Override
    public String id() {
        return "zstd";
    }

    @Override
    public Session createSession() {
        return new Session() {
            @Override
            public EncodedBatch encode(List<byte[]> payloads) {
                byte[] rawBytes = encodeRawPayloads(payloads);
                List<EntryInfo> entryInfos = new ArrayList<>(payloads.size());
                for (int i = 0; i < payloads.size(); i++) {
                    byte[] payload = payloads.get(i);
                    entryInfos.add(EntryInfo.literal(
                            i,
                            payload.length,
                            BatchAlgorithmSupport.varIntSize(payload.length) + payload.length
                    ));
                }
                byte[] bytes = ZstdAlgorithmSupport.compressOrStore(rawBytes, ZstdBatchAlgorithmModule.level());
                return new EncodedBatch(bytes, List.copyOf(entryInfos), 0, 0);
            }

            @Override
            public List<byte[]> decode(byte[] encodedBytes) {
                return decodeRawPayloads(ZstdAlgorithmSupport.restore(encodedBytes));
            }
        };
    }

    private static byte[] encodeRawPayloads(List<byte[]> payloads) {
        net.minecraft.network.FriendlyByteBuf buffer = new net.minecraft.network.FriendlyByteBuf(io.netty.buffer.Unpooled.buffer());
        try {
            buffer.writeVarInt(payloads.size());
            for (byte[] payload : payloads) {
                buffer.writeVarInt(payload.length);
                buffer.writeBytes(payload);
            }
            byte[] bytes = new byte[buffer.readableBytes()];
            buffer.getBytes(0, bytes);
            return bytes;
        } finally {
            buffer.release();
        }
    }

    private static List<byte[]> decodeRawPayloads(byte[] rawBytes) {
        net.minecraft.network.FriendlyByteBuf buffer = new net.minecraft.network.FriendlyByteBuf(io.netty.buffer.Unpooled.wrappedBuffer(rawBytes));
        try {
            int size = buffer.readVarInt();
            List<byte[]> payloads = new ArrayList<>(size);
            for (int i = 0; i < size; i++) {
                int payloadLength = buffer.readVarInt();
                byte[] payload = new byte[payloadLength];
                buffer.readBytes(payload);
                payloads.add(payload);
            }
            return payloads;
        } finally {
            buffer.release();
        }
    }
}
