package com.PinkCats.bandwidthoptimizer.chunk.integration.transport;

import com.PinkCats.bandwidthoptimizer.chunk.classify.ChunkPacketCoordinate;
import com.PinkCats.bandwidthoptimizer.chunk.protocol.ChunkHotspotFrame;

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


    public static void storeFullSnapshot(String channelId, ChunkHotspotFrame frame, byte[] originalPacketBytes) {
        if (channelId == null
                || channelId.isBlank()
                || frame == null
                || frame.coordinate() == null
                || !frame.coordinate().present()
                || originalPacketBytes == null) {
            return;
        }

        CHANNEL_CACHES
                .computeIfAbsent(channelId, ignored -> new ChannelReferenceCache())
                .putFullSnapshot(frame.coordinate(), frame.fullSnapshotVersion(), frame.payloadHash(), originalPacketBytes);
    }


    public static byte[] findPacketBytes(String channelId, String payloadHash) {
        if (channelId == null || channelId.isBlank() || payloadHash == null || payloadHash.isBlank()) {
            return null;
        }

        ChannelReferenceCache cache = CHANNEL_CACHES.get(channelId);
        return cache == null ? null : cache.get(payloadHash);
    }


    public static RuntimeFullSnapshot findFullSnapshot(String channelId, ChunkPacketCoordinate coordinate) {
        if (channelId == null || channelId.isBlank() || coordinate == null || !coordinate.present()) {
            return null;
        }

        ChannelReferenceCache cache = CHANNEL_CACHES.get(channelId);
        return cache == null ? null : cache.getFullSnapshot(coordinate);
    }


    public static void invalidateFullSnapshot(String channelId, ChunkPacketCoordinate coordinate) {
        if (channelId == null || channelId.isBlank() || coordinate == null || !coordinate.present()) {
            return;
        }

        ChannelReferenceCache cache = CHANNEL_CACHES.get(channelId);
        if (cache != null) {
            cache.removeFullSnapshot(coordinate);
        }
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

        private final LinkedHashMap<String, RuntimeFullSnapshot> fullSnapshots = new LinkedHashMap<>(16, 0.75F, true) {
            @Override
            protected boolean removeEldestEntry(Map.Entry<String, RuntimeFullSnapshot> eldest) {
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

        synchronized void putFullSnapshot(
                ChunkPacketCoordinate coordinate,
                long fullSnapshotVersion,
                String payloadHash,
                byte[] originalPacketBytes
        ) {
            this.fullSnapshots.put(
                    chunkKeyText(coordinate),
                    new RuntimeFullSnapshot(
                            coordinate,
                            Math.max(fullSnapshotVersion, 0L),
                            payloadHash == null ? "" : payloadHash,
                            originalPacketBytes
                    )
            );
        }

        synchronized RuntimeFullSnapshot getFullSnapshot(ChunkPacketCoordinate coordinate) {
            RuntimeFullSnapshot runtimeFullSnapshot = this.fullSnapshots.get(chunkKeyText(coordinate));
            return runtimeFullSnapshot == null ? null : runtimeFullSnapshot.copy();
        }

        synchronized void removeFullSnapshot(ChunkPacketCoordinate coordinate) {
            this.fullSnapshots.remove(chunkKeyText(coordinate));
        }
    }

    public record RuntimeFullSnapshot(
            ChunkPacketCoordinate coordinate,
            long fullSnapshotVersion,
            String payloadHash,
            byte[] packetBytes
    ) {
        public RuntimeFullSnapshot {
            packetBytes = packetBytes == null ? new byte[0] : Arrays.copyOf(packetBytes, packetBytes.length);
        }

        public RuntimeFullSnapshot copy() {
            return new RuntimeFullSnapshot(this.coordinate, this.fullSnapshotVersion, this.payloadHash, this.packetBytes);
        }
    }

    private static String chunkKeyText(ChunkPacketCoordinate coordinate) {
        return coordinate == null || !coordinate.present()
                ? "<unknown>"
                : coordinate.chunkX() + "," + coordinate.chunkZ();
    }
}
