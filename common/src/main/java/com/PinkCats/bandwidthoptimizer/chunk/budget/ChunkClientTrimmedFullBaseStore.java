package com.PinkCats.bandwidthoptimizer.chunk.budget;

import com.PinkCats.bandwidthoptimizer.chunk.classify.packet.ChunkPacketCoordinate;
import com.PinkCats.bandwidthoptimizer.chunk.protocol.hotspot.ChunkHotspotFrame;
import com.PinkCats.bandwidthoptimizer.chunk.protocol.hotspot.ChunkHotspotFrameOp;

import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;

public final class ChunkClientTrimmedFullBaseStore {

    private static final long TOMBSTONE_TTL_MILLIS = TimeUnit.SECONDS.toMillis(10L);
    private static final int MAX_TOMBSTONES_PER_CHANNEL = 256;
    private static final String WATCH_BOUNDARY_REFRESH_PATCH_REASON = "refresh_patch_after_watch_boundary";
    private static final ConcurrentHashMap<String, ChannelTrimmedFullBaseState> CHANNEL_STATES = new ConcurrentHashMap<>();

    private ChunkClientTrimmedFullBaseStore() {}

    public static void recordTrimmedFullBase(
            String channelId,
            long scopeId,
            ChunkPacketCoordinate coordinate,
            long fullSnapshotVersion,
            String fullSnapshotHash,
            String reason
    ) {
        if (channelId == null
                || channelId.isBlank()
                || coordinate == null
                || !coordinate.present()
                || fullSnapshotVersion <= 0L
                || fullSnapshotHash == null
                || fullSnapshotHash.isBlank()) {
            return;
        }

        CHANNEL_STATES
                .computeIfAbsent(channelId, ignored -> new ChannelTrimmedFullBaseState())
                .put(new TrimmedFullBaseTombstone(
                        Math.max(scopeId, 0L),
                        coordinate,
                        Math.max(fullSnapshotVersion, 0L),
                        fullSnapshotHash,
                        reason == null ? "" : reason,
                        System.currentTimeMillis()
                ));
    }


    public static TrimmedFullBaseTombstone findMatchingTrimmedFullBase(String channelId, ChunkHotspotFrame frame) {
        if (channelId == null || channelId.isBlank() || frame == null) {
            return null;
        }

        String fullBaseHash = resolveFrameFullBaseHash(frame);
        if (fullBaseHash.isBlank()
                || frame.coordinate() == null
                || !frame.coordinate().present()
                || frame.fullSnapshotVersion() <= 0L) {
            return null;
        }

        ChannelTrimmedFullBaseState state = CHANNEL_STATES.get(channelId);
        if (state == null) {
            return null;
        }

        TrimmedFullBaseTombstone tombstone = state.find(
                Math.max(frame.epoch(), 0L),
                frame.coordinate(),
                resolveExpectedBaseFullSnapshotVersion(frame),
                fullBaseHash
        );
        if (tombstone == null && state.isEmpty()) {
            CHANNEL_STATES.remove(channelId, state);
        }
        return tombstone;
    }

    // clear channel prevent transport mistake
    public static void clearChannel(String channelId) {
        if (channelId == null || channelId.isBlank()) {
            return;
        }
        CHANNEL_STATES.remove(channelId);
    }

    private static String resolveFrameFullBaseHash(ChunkHotspotFrame frame) {
        if (frame == null) {
            return "";
        }
        if (frame.baseSnapshotHash() != null && !frame.baseSnapshotHash().isBlank()) {
            return frame.baseSnapshotHash();
        }
        if (frame.operation() == ChunkHotspotFrameOp.PUBLISH_REF
                && frame.payloadHash() != null
                && !frame.payloadHash().isBlank()) {
            return frame.payloadHash();
        }
        return "";
    }

    private static long resolveExpectedBaseFullSnapshotVersion(ChunkHotspotFrame frame) {
        if (frame != null
                && frame.operation() == ChunkHotspotFrameOp.PUBLISH_PATCH
                && WATCH_BOUNDARY_REFRESH_PATCH_REASON.equals(frame.reason())
                && frame.hotspotKind() == com.PinkCats.bandwidthoptimizer.chunk.classify.ChunkHotspotKind.FULL_CHUNK
                && frame.baseSnapshotHash() != null
                && !frame.baseSnapshotHash().isBlank()
                && frame.payloadHash() != null
                && !frame.payloadHash().isBlank()
                && !frame.baseSnapshotHash().equals(frame.payloadHash())
                && frame.fullSnapshotVersion() > 1L) {
            return frame.fullSnapshotVersion() - 1L;
        }
        return frame == null ? 0L : frame.fullSnapshotVersion();
    }

    private static final class ChannelTrimmedFullBaseState {

        private final LinkedHashMap<String, TrimmedFullBaseTombstone> tombstones =
                new LinkedHashMap<>(16, 0.75F, true);

        private synchronized void put(TrimmedFullBaseTombstone tombstone) {
            pruneExpired(System.currentTimeMillis());
            this.tombstones.put(buildKey(tombstone), tombstone);
            trimToBudget();
        }

        private synchronized TrimmedFullBaseTombstone find(
                long scopeId,
                ChunkPacketCoordinate coordinate,
                long fullSnapshotVersion,
                String fullSnapshotHash
        ) {
            pruneExpired(System.currentTimeMillis());
            return this.tombstones.get(buildKey(scopeId, coordinate, fullSnapshotVersion, fullSnapshotHash));
        }

        private synchronized boolean isEmpty() {
            pruneExpired(System.currentTimeMillis());
            return this.tombstones.isEmpty();
        }

        private void pruneExpired(long nowMillis) {
            Iterator<Map.Entry<String, TrimmedFullBaseTombstone>> iterator = this.tombstones.entrySet().iterator();
            while (iterator.hasNext()) {
                TrimmedFullBaseTombstone tombstone = iterator.next().getValue();
                if (tombstone == null || nowMillis - tombstone.recordedAtMillis() > TOMBSTONE_TTL_MILLIS) {
                    iterator.remove();
                }
            }
        }

        private void trimToBudget() {
            while (this.tombstones.size() > MAX_TOMBSTONES_PER_CHANNEL) {
                Iterator<Map.Entry<String, TrimmedFullBaseTombstone>> iterator = this.tombstones.entrySet().iterator();
                if (!iterator.hasNext()) {
                    return;
                }
                iterator.next();
                iterator.remove();
            }
        }
    }

    public record TrimmedFullBaseTombstone(
            long scopeId,
            ChunkPacketCoordinate coordinate,
            long fullSnapshotVersion,
            String fullSnapshotHash,
            String reason,
            long recordedAtMillis
    ) {
        public TrimmedFullBaseTombstone {
            coordinate = coordinate == null ? ChunkPacketCoordinate.unknown() : coordinate;
            fullSnapshotHash = fullSnapshotHash == null ? "" : fullSnapshotHash;
            reason = reason == null ? "" : reason;
        }
    }

    private static String buildKey(TrimmedFullBaseTombstone tombstone) {
        return buildKey(
                tombstone == null ? 0L : tombstone.scopeId(),
                tombstone == null ? ChunkPacketCoordinate.unknown() : tombstone.coordinate(),
                tombstone == null ? 0L : tombstone.fullSnapshotVersion(),
                tombstone == null ? "" : tombstone.fullSnapshotHash()
        );
    }

    private static String buildKey(
            long scopeId,
            ChunkPacketCoordinate coordinate,
            long fullSnapshotVersion,
            String fullSnapshotHash
    ) {
        return Math.max(scopeId, 0L)
                + ":"
                + chunkKeyText(coordinate)
                + ":"
                + Math.max(fullSnapshotVersion, 0L)
                + ":"
                + (fullSnapshotHash == null ? "" : fullSnapshotHash);
    }

    private static String chunkKeyText(ChunkPacketCoordinate coordinate) {
        return coordinate == null || !coordinate.present()
                ? "<unknown>"
                : coordinate.chunkX() + "," + coordinate.chunkZ();
    }
}
