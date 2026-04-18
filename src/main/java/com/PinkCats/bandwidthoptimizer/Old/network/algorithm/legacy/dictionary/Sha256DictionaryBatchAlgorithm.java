package com.PinkCats.bandwidthoptimizer.Old.network.algorithm.legacy.dictionary;

import com.PinkCats.bandwidthoptimizer.Old.network.algorithm.BatchAlgorithm;
import com.PinkCats.bandwidthoptimizer.Old.network.algorithm.BatchAlgorithmSupport;

import io.netty.buffer.Unpooled;
import net.minecraft.network.FriendlyByteBuf;

import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;

@Deprecated(forRemoval = false)
public final class Sha256DictionaryBatchAlgorithm implements BatchAlgorithm {

    private static final int ENTRY_LITERAL = 0;
    private static final int ENTRY_MAPPED = 1;
    private static final int SHA256_BYTES = 32;

    @Override
    public String id() {
        return "sha256_dictionary";
    }

    @Override
    public Session createSession() {
        return new DictionarySession();
    }

    private static final class DictionarySession implements Session {
        private final Map<HashKey, Integer> idByHash = new HashMap<>();
        private final Map<Integer, DictionaryEntry> entriesById = new HashMap<>();
        private final List<Integer> pendingRemovals = new ArrayList<>();
        private int nextId;
        private long touchCounter;
        private int totalPayloadBytes;

        @Override
        public EncodedBatch encode(List<byte[]> payloads) {
            FriendlyByteBuf buffer = new FriendlyByteBuf(Unpooled.buffer());
            try {
                List<Integer> removalsToSend = List.copyOf(this.pendingRemovals);
                this.pendingRemovals.clear();
                List<PendingAddition> additions = new ArrayList<>();
                List<PendingEntry> entries = new ArrayList<>(payloads.size());
                List<EntryInfo> entryInfos = new ArrayList<>(payloads.size());
                LinkedHashSet<Integer> protectedIds = new LinkedHashSet<>();

                for (int i = 0; i < payloads.size(); i++) {
                    byte[] payload = payloads.get(i);
                    if (payload.length >= Sha256DictionaryBatchAlgorithmModule.maxPacketBytes()) {
                        entries.add(PendingEntry.literal(payload));
                        entryInfos.add(EntryInfo.literal(
                                i,
                                payload.length,
                                BatchAlgorithmSupport.varIntSize(ENTRY_LITERAL)
                                        + BatchAlgorithmSupport.varIntSize(payload.length)
                                        + payload.length
                        ));
                        continue;
                    }

                    byte[] hash = sha256(payload);
                    HashKey key = new HashKey(hash);
                    Integer mappingId = this.idByHash.get(key);
                    if (mappingId == null) {
                        mappingId = this.nextId++;
                        this.idByHash.put(key, mappingId);
                        byte[] storedPayload = Arrays.copyOf(payload, payload.length);
                        this.entriesById.put(mappingId, new DictionaryEntry(hash, storedPayload, nextTouch()));
                        this.totalPayloadBytes += storedPayload.length;
                        additions.add(new PendingAddition(mappingId, hash, payload));
                    } else {
                        touch(mappingId);
                    }

                    protectedIds.add(mappingId);
                    entries.add(PendingEntry.mapped(mappingId));
                    entryInfos.add(EntryInfo.reference(
                            i,
                            mappingId,
                            payload.length,
                            BatchAlgorithmSupport.varIntSize(ENTRY_MAPPED) + BatchAlgorithmSupport.varIntSize(mappingId)
                        ));
                }

                evictExpiredMappings(protectedIds);

                buffer.writeVarInt(additions.size());
                for (PendingAddition addition : additions) {
                    buffer.writeVarInt(addition.mappingId());
                    buffer.writeBytes(addition.hash());
                    buffer.writeVarInt(addition.payload().length);
                    buffer.writeBytes(addition.payload());
                }

                buffer.writeVarInt(removalsToSend.size());
                for (int mappingId : removalsToSend) {
                    buffer.writeVarInt(mappingId);
                }

                buffer.writeVarInt(entries.size());
                for (PendingEntry entry : entries) {
                    buffer.writeVarInt(entry.type());
                    if (entry.type() == ENTRY_MAPPED) {
                        buffer.writeVarInt(entry.mappingId());
                        continue;
                    }
                    buffer.writeVarInt(entry.payload().length);
                    buffer.writeBytes(entry.payload());
                }

                byte[] bytes = new byte[buffer.readableBytes()];
                buffer.getBytes(0, bytes);
                return new EncodedBatch(bytes, List.copyOf(entryInfos), additions.size(), removalsToSend.size());
            } finally {
                buffer.release();
            }
        }

        @Override
        public List<byte[]> decode(byte[] encodedBytes) {
            FriendlyByteBuf buffer = new FriendlyByteBuf(Unpooled.wrappedBuffer(encodedBytes));
            try {
                int addedCount = buffer.readVarInt();
                for (int i = 0; i < addedCount; i++) {
                    int mappingId = buffer.readVarInt();
                    byte[] hash = new byte[SHA256_BYTES];
                    buffer.readBytes(hash);
                    int payloadLength = buffer.readVarInt();
                    byte[] payload = new byte[payloadLength];
                    buffer.readBytes(payload);
                    this.idByHash.put(new HashKey(hash), mappingId);
                    this.entriesById.put(mappingId, new DictionaryEntry(hash, payload, nextTouch()));
                    this.totalPayloadBytes += payload.length;
                    this.nextId = Math.max(this.nextId, mappingId + 1);
                }

                int removedCount = buffer.readVarInt();
                for (int i = 0; i < removedCount; i++) {
                    int mappingId = buffer.readVarInt();
                    DictionaryEntry removedEntry = this.entriesById.remove(mappingId);
                    if (removedEntry != null) {
                        this.idByHash.remove(new HashKey(removedEntry.hash()));
                        this.totalPayloadBytes -= removedEntry.payload().length;
                    }
                }

                int entryCount = buffer.readVarInt();
                List<byte[]> payloads = new ArrayList<>(entryCount);
                for (int i = 0; i < entryCount; i++) {
                    int entryType = buffer.readVarInt();
                    if (entryType == ENTRY_MAPPED) {
                        int mappingId = buffer.readVarInt();
                        DictionaryEntry entry = this.entriesById.get(mappingId);
                        if (entry == null) {
                            throw new IllegalStateException("Missing dictionary payload for mapping id " + mappingId);
                        }
                        this.entriesById.put(mappingId, entry.touch(nextTouch()));
                        payloads.add(Arrays.copyOf(entry.payload(), entry.payload().length));
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

        @Override
        public void reset() {
            this.idByHash.clear();
            this.entriesById.clear();
            this.pendingRemovals.clear();
            this.nextId = 0;
            this.touchCounter = 0L;
            this.totalPayloadBytes = 0;
        }

        private void evictExpiredMappings(LinkedHashSet<Integer> protectedIds) {
            int maxEntries = Sha256DictionaryBatchAlgorithmModule.maxEntries();
            int maxPayloadBytes = Sha256DictionaryBatchAlgorithmModule.maxPayloadBytes();
            if (this.entriesById.size() <= maxEntries && this.totalPayloadBytes <= maxPayloadBytes) {
                return;
            }

            List<Map.Entry<Integer, DictionaryEntry>> candidates = this.entriesById.entrySet().stream()
                    .filter(entry -> !protectedIds.contains(entry.getKey()))
                    .sorted(Comparator.comparingLong(entry -> entry.getValue().lastTouched()))
                    .toList();

            for (Map.Entry<Integer, DictionaryEntry> candidate : candidates) {
                if (this.entriesById.size() <= maxEntries && this.totalPayloadBytes <= maxPayloadBytes) {
                    break;
                }
                int mappingId = candidate.getKey();
                DictionaryEntry removedEntry = this.entriesById.remove(mappingId);
                if (removedEntry == null) {
                    continue;
                }
                this.idByHash.remove(new HashKey(removedEntry.hash()));
                this.totalPayloadBytes -= removedEntry.payload().length;
                this.pendingRemovals.add(mappingId);
            }
        }

        private void touch(int mappingId) {
            DictionaryEntry entry = this.entriesById.get(mappingId);
            if (entry != null) {
                this.entriesById.put(mappingId, entry.touch(nextTouch()));
            }
        }

        private long nextTouch() {
            return ++this.touchCounter;
        }

        private static byte[] sha256(byte[] payload) {
            try {
                return MessageDigest.getInstance("SHA-256").digest(payload);
            } catch (NoSuchAlgorithmException exception) {
                throw new IllegalStateException("SHA-256 is not available", exception);
            }
        }
    }

    private record PendingAddition(int mappingId, byte[] hash, byte[] payload) {
    }

    private record DictionaryEntry(byte[] hash, byte[] payload, long lastTouched) {
        private DictionaryEntry touch(long touch) {
            return new DictionaryEntry(this.hash, this.payload, touch);
        }
    }

    private record PendingEntry(int type, int mappingId, byte[] payload) {
        private static PendingEntry mapped(int mappingId) {
            return new PendingEntry(ENTRY_MAPPED, mappingId, null);
        }

        private static PendingEntry literal(byte[] payload) {
            return new PendingEntry(ENTRY_LITERAL, -1, payload);
        }
    }

    private record HashKey(byte[] bytes) {
        @Override
        public boolean equals(Object object) {
            return object instanceof HashKey other && Arrays.equals(this.bytes, other.bytes);
        }

        @Override
        public int hashCode() {
            return Arrays.hashCode(this.bytes);
        }
    }
}
