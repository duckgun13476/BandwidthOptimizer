package com.PinkCats.bandwidthoptimizer.chunk.integration.transport;

import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

public final class ChunkRuntimeReferenceStore {

    private static final int MAX_ENTRIES_PER_CHANNEL = 4096;
    private static final ConcurrentHashMap<String, ChannelReferenceCache> CHANNEL_CACHES = new ConcurrentHashMap<>();

    private ChunkRuntimeReferenceStore() {}


    public static void storePacketBytes(String channelId, String payloadHash, byte[] originalPacketBytes) {
        if (channelId == null || channelId.isBlank() || payloadHash == null || payloadHash.isBlank() || originalPacketBytes == null) {
            return;
        }

        CHANNEL_CACHES
                .computeIfAbsent(channelId, ignored -> new ChannelReferenceCache())
                .put(payloadHash, originalPacketBytes);
    }


    public static byte[] findPacketBytes(String channelId, String payloadHash) {
        if (channelId == null || channelId.isBlank() || payloadHash == null || payloadHash.isBlank()) {
            return null;
        }

        ChannelReferenceCache cache = CHANNEL_CACHES.get(channelId);
        return cache == null ? null : cache.get(payloadHash);
    }

    public static void clearChannel(String channelId) {
        if (channelId == null || channelId.isBlank()) {
            return;
        }
        CHANNEL_CACHES.remove(channelId);
    }

    private static final class ChannelReferenceCache {

        private final LinkedHashMap<String, byte[]> entries = new LinkedHashMap<>(16, 0.75F, true) {
            @Override
            protected boolean removeEldestEntry(Map.Entry<String, byte[]> eldest) {
                return size() > MAX_ENTRIES_PER_CHANNEL;
            }
        };

        synchronized void put(String payloadHash, byte[] originalPacketBytes) {
            this.entries.put(payloadHash, Arrays.copyOf(originalPacketBytes, originalPacketBytes.length));
        }

        synchronized byte[] get(String payloadHash) {
            byte[] originalPacketBytes = this.entries.get(payloadHash);
            return originalPacketBytes == null ? null : Arrays.copyOf(originalPacketBytes, originalPacketBytes.length);
        }
    }
}
