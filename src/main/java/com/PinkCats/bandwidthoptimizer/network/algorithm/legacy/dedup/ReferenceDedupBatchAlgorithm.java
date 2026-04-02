package com.PinkCats.bandwidthoptimizer.network.algorithm.legacy.dedup;

import com.PinkCats.bandwidthoptimizer.network.algorithm.BatchAlgorithm;
import com.PinkCats.bandwidthoptimizer.network.algorithm.BatchAlgorithmSupport;

import io.netty.buffer.Unpooled;
import net.minecraft.network.FriendlyByteBuf;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

@Deprecated(forRemoval = false)
public final class ReferenceDedupBatchAlgorithm implements BatchAlgorithm {

    private static final int ENTRY_LITERAL = 0;
    private static final int ENTRY_REFERENCE = 1;

    @Override
    public String id() {
        return "reference_dedup";
    }

    @Override
    public Session createSession() {
        return new Session() {
            @Override
            public EncodedBatch encode(List<byte[]> payloads) {
                FriendlyByteBuf buffer = new FriendlyByteBuf(Unpooled.buffer());
                try {
                    Map<ByteArrayKey, Integer> firstIndexByPayload = new HashMap<>();
                    List<EntryInfo> entryInfos = new ArrayList<>(payloads.size());

                    buffer.writeVarInt(payloads.size());
                    for (int i = 0; i < payloads.size(); i++) {
                        byte[] payload = payloads.get(i);
                        ByteArrayKey key = new ByteArrayKey(payload);
                        Integer firstIndex = firstIndexByPayload.get(key);
                        if (firstIndex != null) {
                            buffer.writeVarInt(ENTRY_REFERENCE);
                            buffer.writeVarInt(firstIndex);
                            entryInfos.add(EntryInfo.reference(
                                    i,
                                    firstIndex,
                                    payload.length,
                                    BatchAlgorithmSupport.varIntSize(ENTRY_REFERENCE) + BatchAlgorithmSupport.varIntSize(firstIndex)
                            ));
                            continue;
                        }

                        firstIndexByPayload.put(key, i);
                        buffer.writeVarInt(ENTRY_LITERAL);
                        buffer.writeVarInt(payload.length);
                        buffer.writeBytes(payload);
                        entryInfos.add(EntryInfo.literal(
                                i,
                                payload.length,
                                BatchAlgorithmSupport.varIntSize(ENTRY_LITERAL)
                                        + BatchAlgorithmSupport.varIntSize(payload.length)
                                        + payload.length
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
                        int entryType = buffer.readVarInt();
                        if (entryType == ENTRY_REFERENCE) {
                            int referenceIndex = buffer.readVarInt();
                            payloads.add(Arrays.copyOf(payloads.get(referenceIndex), payloads.get(referenceIndex).length));
                            continue;
                        }
                        if (entryType != ENTRY_LITERAL) {
                            throw new IllegalArgumentException("Unknown entry type: " + entryType);
                        }
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

    private record ByteArrayKey(byte[] bytes) {
        @Override
        public boolean equals(Object object) {
            return object instanceof ByteArrayKey other && Arrays.equals(this.bytes, other.bytes);
        }

        @Override
        public int hashCode() {
            return Arrays.hashCode(this.bytes);
        }
    }
}
