package com.PinkCats.bandwidthoptimizer.Old.network.algorithm;

import io.netty.buffer.Unpooled;
import net.minecraft.network.FriendlyByteBuf;

import java.util.ArrayList;
import java.util.List;

public final class PassthroughBatchAlgorithm implements BatchAlgorithm {

    @Override
    public String id() {
        return "passthrough";
    }

    @Override
    public Session createSession() {
        return new Session() {
            @Override
            public EncodedBatch encode(List<byte[]> payloads) {
                FriendlyByteBuf buffer = new FriendlyByteBuf(Unpooled.buffer());
                try {
                    List<EntryInfo> entryInfos = new ArrayList<>(payloads.size());
                    buffer.writeVarInt(payloads.size());
                    for (int i = 0; i < payloads.size(); i++) {
                        byte[] payload = payloads.get(i);
                        buffer.writeVarInt(payload.length);
                        buffer.writeBytes(payload);
                        entryInfos.add(EntryInfo.literal(
                                i,
                                payload.length,
                                BatchAlgorithmSupport.varIntSize(payload.length) + payload.length
                        ));
                    }
                    byte[] bytes = new byte[buffer.readableBytes()];
                    buffer.getBytes(0, bytes);
                    return new EncodedBatch(bytes, List.copyOf(entryInfos), 0, 0);
                } finally {
                    buffer.release();
                }
            }

            @Override
            public List<byte[]> decode(byte[] encodedBytes) {
                FriendlyByteBuf buffer = new FriendlyByteBuf(Unpooled.wrappedBuffer(encodedBytes));
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
        };
    }
}
