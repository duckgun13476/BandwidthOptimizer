package com.PinkCats.bandwidthoptimizer.chunk.integration;

import com.PinkCats.bandwidthoptimizer.chunk.classify.packet.ChunkPacketCoordinate;
import com.PinkCats.bandwidthoptimizer.chunk.protocol.hotspot.ChunkHotspotFrame;

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
                .putFullSnapshot(frame.epoch(), frame.coordinate(), frame.fullSnapshotVersion(), frame.payloadHash(), originalPacketBytes);
    }


    public static byte[] findPacketBytes(String channelId, String payloadHash) {
        if (channelId == null || channelId.isBlank() || payloadHash == null || payloadHash.isBlank()) {
            return null;
        }

        ChannelReferenceCache cache = CHANNEL_CACHES.get(channelId);
        return cache == null ? null : cache.get(payloadHash);
    }


    public static RuntimeFullSnapshot findFullSnapshot(String channelId, long scopeId, ChunkPacketCoordinate coordinate) {
        if (channelId == null || channelId.isBlank() || coordinate == null || !coordinate.present()) {
            return null;
        }

        ChannelReferenceCache cache = CHANNEL_CACHES.get(channelId);
        return cache == null ? null : cache.getFullSnapshot(scopeId, coordinate);
    }

    public static RuntimeFullSnapshot findFullSnapshot(String channelId, ChunkPacketCoordinate coordinate) {
        return findFullSnapshot(channelId, 0L, coordinate);
    }

    public static void invalidateFullSnapshot(String channelId, long scopeId, ChunkPacketCoordinate coordinate) {
        if (channelId == null || channelId.isBlank() || coordinate == null || !coordinate.present()) {
            return;
        }

        ChannelReferenceCache cache = CHANNEL_CACHES.get(channelId);
        if (cache != null) {
            cache.removeFullSnapshot(scopeId, coordinate);
        }
    }

    public static void invalidateFullSnapshot(String channelId, ChunkPacketCoordinate coordinate) {
        invalidateFullSnapshot(channelId, 0L, coordinate);
    }

    public static void clearChannel(String channelId) {
        if (channelId == null || channelId.isBlank()) {
            return;
        }
        CHANNEL_CACHES.remove(channelId);
    }


    public static Snapshot snapshot() {
        long channelCount = 0L;
        long packetEntryCount = 0L;
        long packetEntryBytes = 0L;
        long fullSnapshotCount = 0L;
        long fullSnapshotBytes = 0L;

        for (ChannelReferenceCache channelReferenceCache : CHANNEL_CACHES.values()) {
            if (channelReferenceCache == null) {
                continue;
            }

            channelCount++;
            packetEntryCount += channelReferenceCache.packetEntryCount();
            packetEntryBytes += channelReferenceCache.packetEntryBytes();
            fullSnapshotCount += channelReferenceCache.fullSnapshotCount();
            fullSnapshotBytes += channelReferenceCache.fullSnapshotBytes();
        }

        return new Snapshot(
                channelCount,
                packetEntryCount,
                packetEntryBytes,
                fullSnapshotCount,
                fullSnapshotBytes
        );
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
                long scopeId,
                ChunkPacketCoordinate coordinate,
                long fullSnapshotVersion,
                String payloadHash,
                byte[] originalPacketBytes
        ) {
            this.fullSnapshots.put(
                    scopedChunkKeyText(scopeId, coordinate),
                    new RuntimeFullSnapshot(
                            Math.max(scopeId, 0L),
                            coordinate,
                            Math.max(fullSnapshotVersion, 0L),
                            payloadHash == null ? "" : payloadHash,
                            originalPacketBytes
                    )
            );
        }

        synchronized RuntimeFullSnapshot getFullSnapshot(long scopeId, ChunkPacketCoordinate coordinate) {
            RuntimeFullSnapshot scopedSnapshot = this.fullSnapshots.get(scopedChunkKeyText(scopeId, coordinate));
            if (scopedSnapshot != null) {
                return scopedSnapshot.copy();
            }

            for (RuntimeFullSnapshot runtimeFullSnapshot : this.fullSnapshots.values()) {
                if (!matchesCoordinate(runtimeFullSnapshot, coordinate)) {
                    continue;
                }
                return runtimeFullSnapshot.copy();
            }
            return null;
        }

        synchronized void removeFullSnapshot(long scopeId, ChunkPacketCoordinate coordinate) {
            this.fullSnapshots.remove(scopedChunkKeyText(scopeId, coordinate));
        }


        synchronized long packetEntryCount() {
            return this.entries.size();
        }

        synchronized long packetEntryBytes() {
            long totalBytes = 0L;
            for (byte[] packetBytes : this.entries.values()) {
                totalBytes += packetBytes == null ? 0L : packetBytes.length;
            }
            return totalBytes;
        }


        synchronized long fullSnapshotCount() {
            return this.fullSnapshots.size();
        }

        synchronized long fullSnapshotBytes() {
            long totalBytes = 0L;
            for (RuntimeFullSnapshot runtimeFullSnapshot : this.fullSnapshots.values()) {
                totalBytes += runtimeFullSnapshot == null ? 0L : runtimeFullSnapshot.packetBytes().length;
            }
            return totalBytes;
        }

        private static boolean matchesCoordinate(RuntimeFullSnapshot runtimeFullSnapshot, ChunkPacketCoordinate coordinate) {
            return runtimeFullSnapshot != null
                    && coordinate != null
                    && coordinate.present()
                    && runtimeFullSnapshot.coordinate() != null
                    && runtimeFullSnapshot.coordinate().present()
                    && runtimeFullSnapshot.coordinate().chunkX() == coordinate.chunkX()
                    && runtimeFullSnapshot.coordinate().chunkZ() == coordinate.chunkZ();
        }
    }

    public record Snapshot(
            long channelCount,
            long packetEntryCount,
            long packetEntryBytes,
            long fullSnapshotCount,
            long fullSnapshotBytes
    ) {
        public long totalBytes() {
            return this.packetEntryBytes + this.fullSnapshotBytes;
        }
    }

    public record RuntimeFullSnapshot(
            long scopeId,
            ChunkPacketCoordinate coordinate,
            long fullSnapshotVersion,
            String payloadHash,
            byte[] packetBytes
    ) {
        public RuntimeFullSnapshot {
            packetBytes = packetBytes == null ? new byte[0] : Arrays.copyOf(packetBytes, packetBytes.length);
        }

        public RuntimeFullSnapshot copy() {
            return new RuntimeFullSnapshot(this.scopeId, this.coordinate, this.fullSnapshotVersion, this.payloadHash, this.packetBytes);
        }
    }

    private static String chunkKeyText(ChunkPacketCoordinate coordinate) {
        return coordinate == null || !coordinate.present()
                ? "<unknown>"
                : coordinate.chunkX() + "," + coordinate.chunkZ();
    }

    private static String scopedChunkKeyText(long scopeId, ChunkPacketCoordinate coordinate) {
        return Math.max(scopeId, 0L) + ":" + chunkKeyText(coordinate);
    }
}
