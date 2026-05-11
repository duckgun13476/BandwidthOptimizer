package com.PinkCats.bandwidthoptimizer.chunk.integration;

import com.PinkCats.bandwidthoptimizer.chunk.classify.packet.ChunkPacketCoordinate;
import com.PinkCats.bandwidthoptimizer.chunk.protocol.hotspot.ChunkHotspotFrame;

import java.util.Arrays;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
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


    public static void storeFullSnapshot(String channelId, ChunkHotspotFrame frame) {
        if (channelId == null
                || channelId.isBlank()
                || frame == null
                || frame.coordinate() == null
                || !frame.coordinate().present()) {
            return;
        }

        CHANNEL_CACHES
                .computeIfAbsent(channelId, ignored -> new ChannelReferenceCache())
                .putFullSnapshot(frame.epoch(), frame.coordinate(), frame.fullSnapshotVersion(), frame.payloadHash());
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

    public static void invalidateFullSnapshotAcrossScopes(String channelId, ChunkPacketCoordinate coordinate) {
        if (channelId == null || channelId.isBlank() || coordinate == null || !coordinate.present()) {
            return;
        }

        ChannelReferenceCache cache = CHANNEL_CACHES.get(channelId);
        if (cache != null) {
            cache.removeFullSnapshotsByCoordinate(coordinate);
        }
    }

    public static void clearChannel(String channelId) {
        if (channelId == null || channelId.isBlank()) {
            return;
        }
        CHANNEL_CACHES.remove(channelId);
    }

    public static TrimResult trimToTotalBytes(long targetBytes) {
        long safeTargetBytes = Math.max(targetBytes, 0L);
        long currentTotalBytes = snapshot().totalBytes();
        long releasedBytes = 0L;
        ArrayList<EvictedFullSnapshot> evictedFullSnapshots = new ArrayList<>();
        if (currentTotalBytes <= safeTargetBytes) {
            return new TrimResult(0L, List.of());
        }

        while (currentTotalBytes > safeTargetBytes) {
            PacketEntryEvictionSelection packetEntryEviction = evictOneOldestPacketEntry();
            if (packetEntryEviction.releasedBytes() <= 0L) {
                break;
            }
            releasedBytes += packetEntryEviction.releasedBytes();
            currentTotalBytes = Math.max(currentTotalBytes - packetEntryEviction.releasedBytes(), 0L);
            evictedFullSnapshots.addAll(packetEntryEviction.evictedFullSnapshots());
        }

        return new TrimResult(releasedBytes, List.copyOf(evictedFullSnapshots));
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


    private static PacketEntryEvictionSelection evictOneOldestPacketEntry() {
        for (Map.Entry<String, ChannelReferenceCache> channelEntry : CHANNEL_CACHES.entrySet()) {
            ChannelReferenceCache cache = channelEntry.getValue();
            if (cache == null) {
                continue;
            }
            ChannelReferenceCache.PacketEntryEvictionResult evictionResult = cache.evictOldestPacketEntry();
            if (evictionResult.releasedBytes() > 0L) {
                ArrayList<EvictedFullSnapshot> evictedFullSnapshots = new ArrayList<>();
                for (RuntimeFullSnapshot runtimeFullSnapshot : evictionResult.evictedFullSnapshots()) {
                    evictedFullSnapshots.add(new EvictedFullSnapshot(
                            channelEntry.getKey(),
                            runtimeFullSnapshot.scopeId(),
                            runtimeFullSnapshot.coordinate(),
                            runtimeFullSnapshot.fullSnapshotVersion(),
                            runtimeFullSnapshot.payloadHash()
                    ));
                }
                return new PacketEntryEvictionSelection(evictionResult.releasedBytes(), List.copyOf(evictedFullSnapshots));
            }
        }
        return PacketEntryEvictionSelection.empty();
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
                String payloadHash
        ) {
            RuntimeFullSnapshot replacedSnapshot = this.fullSnapshots.put(
                    scopedChunkKeyText(scopeId, coordinate),
                    new RuntimeFullSnapshot(
                            Math.max(scopeId, 0L),
                            coordinate,
                            Math.max(fullSnapshotVersion, 0L),
                            payloadHash == null ? "" : payloadHash
                    )
            );
            if (replacedSnapshot != null) {
                releaseOrphanedPacketEntry(replacedSnapshot.payloadHash());
            }
        }

        synchronized RuntimeFullSnapshot getFullSnapshot(long scopeId, ChunkPacketCoordinate coordinate) {
            RuntimeFullSnapshot scopedSnapshot = this.fullSnapshots.get(scopedChunkKeyText(scopeId, coordinate));
            return scopedSnapshot == null ? null : scopedSnapshot.copy();
        }

        synchronized void removeFullSnapshot(long scopeId, ChunkPacketCoordinate coordinate) {
            RuntimeFullSnapshot removedSnapshot = this.fullSnapshots.remove(scopedChunkKeyText(scopeId, coordinate));
            if (removedSnapshot != null) {
                releaseOrphanedPacketEntry(removedSnapshot.payloadHash());
            }
        }

        synchronized void removeFullSnapshotsByCoordinate(ChunkPacketCoordinate coordinate) {
            if (coordinate == null || !coordinate.present()) {
                return;
            }

            ArrayList<String> removedPayloadHashes = new ArrayList<>();
            this.fullSnapshots.entrySet().removeIf(entry -> {
                RuntimeFullSnapshot snapshot = entry.getValue();
                if (snapshot == null
                        || snapshot.coordinate() == null
                        || !snapshot.coordinate().present()
                        || snapshot.coordinate().chunkX() != coordinate.chunkX()
                        || snapshot.coordinate().chunkZ() != coordinate.chunkZ()) {
                    return false;
                }
                removedPayloadHashes.add(snapshot.payloadHash());
                return true;
            });
            for (String payloadHash : removedPayloadHashes) {
                releaseOrphanedPacketEntry(payloadHash);
            }
        }


        synchronized PacketEntryEvictionResult evictOldestPacketEntry() {
            if (this.entries.isEmpty()) {
                return PacketEntryEvictionResult.empty();
            }

            Map.Entry<String, byte[]> eldestEntry = this.entries.entrySet().iterator().next();
            byte[] removedBytes = this.entries.remove(eldestEntry.getKey());
            List<RuntimeFullSnapshot> evictedFullSnapshots = removeFullSnapshotsByPayloadHash(eldestEntry.getKey());
            return new PacketEntryEvictionResult(
                    removedBytes == null ? 0L : removedBytes.length,
                    evictedFullSnapshots
            );
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
            return 0L;
        }

        private List<RuntimeFullSnapshot> removeFullSnapshotsByPayloadHash(String payloadHash) {
            ArrayList<RuntimeFullSnapshot> removedSnapshots = new ArrayList<>();
            if (payloadHash == null || payloadHash.isBlank()) {
                return List.of();
            }

            this.fullSnapshots.entrySet().removeIf(entry -> {
                RuntimeFullSnapshot snapshot = entry.getValue();
                if (snapshot == null || !payloadHash.equals(snapshot.payloadHash())) {
                    return false;
                }
                removedSnapshots.add(snapshot);
                return true;
            });
            return List.copyOf(removedSnapshots);
        }


        private void releaseOrphanedPacketEntry(String payloadHash) {
            if (payloadHash == null || payloadHash.isBlank() || isPayloadHashReferencedByFullSnapshot(payloadHash)) {
                return;
            }
            this.entries.remove(payloadHash);
        }

        private boolean isPayloadHashReferencedByFullSnapshot(String payloadHash) {
            if (payloadHash == null || payloadHash.isBlank()) {
                return false;
            }
            for (RuntimeFullSnapshot runtimeFullSnapshot : this.fullSnapshots.values()) {
                if (runtimeFullSnapshot != null && payloadHash.equals(runtimeFullSnapshot.payloadHash())) {
                    return true;
                }
            }
            return false;
        }

        private record PacketEntryEvictionResult(
                long releasedBytes,
                List<RuntimeFullSnapshot> evictedFullSnapshots
        ) {
            private PacketEntryEvictionResult {
                releasedBytes = Math.max(releasedBytes, 0L);
                evictedFullSnapshots = evictedFullSnapshots == null ? List.of() : List.copyOf(evictedFullSnapshots);
            }

            private static PacketEntryEvictionResult empty() {
                return new PacketEntryEvictionResult(0L, List.of());
            }
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

    public record TrimResult(
            long releasedBytes,
            List<EvictedFullSnapshot> evictedFullSnapshots
    ) {
        public TrimResult {
            releasedBytes = Math.max(releasedBytes, 0L);
            evictedFullSnapshots = evictedFullSnapshots == null ? List.of() : List.copyOf(evictedFullSnapshots);
        }
    }

    public record EvictedFullSnapshot(
            String channelId,
            long scopeId,
            ChunkPacketCoordinate coordinate,
            long fullSnapshotVersion,
            String payloadHash
    ) {
        public EvictedFullSnapshot {
            channelId = channelId == null ? "" : channelId;
            coordinate = coordinate == null ? ChunkPacketCoordinate.unknown() : coordinate;
            payloadHash = payloadHash == null ? "" : payloadHash;
        }
    }

    public record RuntimeFullSnapshot(
            long scopeId,
            ChunkPacketCoordinate coordinate,
            long fullSnapshotVersion,
            String payloadHash
    ) {
        public RuntimeFullSnapshot {
            coordinate = coordinate == null ? ChunkPacketCoordinate.unknown() : coordinate;
            payloadHash = payloadHash == null ? "" : payloadHash;
        }

        public RuntimeFullSnapshot copy() {
            return new RuntimeFullSnapshot(this.scopeId, this.coordinate, this.fullSnapshotVersion, this.payloadHash);
        }
    }

    private record PacketEntryEvictionSelection(
            long releasedBytes,
            List<EvictedFullSnapshot> evictedFullSnapshots
    ) {
        private PacketEntryEvictionSelection {
            releasedBytes = Math.max(releasedBytes, 0L);
            evictedFullSnapshots = evictedFullSnapshots == null ? List.of() : List.copyOf(evictedFullSnapshots);
        }

        private static PacketEntryEvictionSelection empty() {
            return new PacketEntryEvictionSelection(0L, List.of());
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
