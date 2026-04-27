package com.PinkCats.bandwidthoptimizer.chunk.integration.transport;

import com.PinkCats.bandwidthoptimizer.chunk.classify.packet.ChunkPacketCoordinate;
import com.PinkCats.bandwidthoptimizer.chunk.protocol.hotspot.ChunkHotspotFrame;

import java.util.Arrays;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;

public final class ChunkWatchBoundaryReusePendingStore {

    private static final long ENTRY_TTL_MILLIS = TimeUnit.SECONDS.toMillis(15L);
    private static final int MAX_PENDING_PROBES_PER_CHANNEL = 512;
    private static final ConcurrentHashMap<String, ChannelPendingState> CHANNEL_STATES = new ConcurrentHashMap<>();

    private ChunkWatchBoundaryReusePendingStore() {}

    // side chunk plan fix
    public static void rememberProbe(String channelId, ChunkHotspotFrame probeFrame, byte[] originalPacketBytes) {
        if (channelId == null
                || channelId.isBlank()
                || probeFrame == null
                || probeFrame.coordinate() == null
                || !probeFrame.coordinate().present()
                || probeFrame.fullSnapshotVersion() <= 0L
                || probeFrame.payloadHash() == null
                || probeFrame.payloadHash().isBlank()
                || originalPacketBytes == null
                || originalPacketBytes.length == 0) {
            return;
        }

        CHANNEL_STATES
                .computeIfAbsent(channelId, ignored -> new ChannelPendingState())
                .put(new PendingFullReplay(probeFrame, originalPacketBytes, System.currentTimeMillis()));
    }

    public static PendingFullReplay takePendingFull(String channelId, ChunkHotspotFrame responseFrame) {
        if (channelId == null || channelId.isBlank() || responseFrame == null) {
            return null;
        }

        ChannelPendingState state = CHANNEL_STATES.get(channelId);
        if (state == null) {
            return null;
        }

        PendingFullReplay replay = state.remove(responseFrame);
        if (state.isEmpty()) {
            CHANNEL_STATES.remove(channelId, state);
        }
        return replay;
    }

    public static void clearPendingFull(String channelId, ChunkHotspotFrame responseFrame) {
        takePendingFull(channelId, responseFrame);
    }

    public record PendingFullReplay(
            ChunkHotspotFrame probeFrame,
            byte[] originalPacketBytes,
            long recordedAtMillis
    ) {
        public PendingFullReplay {
            originalPacketBytes = originalPacketBytes == null ? new byte[0] : Arrays.copyOf(originalPacketBytes, originalPacketBytes.length);
        }

        public byte[] copyOriginalPacketBytes() {
            return Arrays.copyOf(this.originalPacketBytes, this.originalPacketBytes.length);
        }
    }

    private static final class ChannelPendingState {

        private final LinkedHashMap<String, PendingFullReplay> pendingReplays =
                new LinkedHashMap<>(16, 0.75F, true);

        private synchronized void put(PendingFullReplay replay) {
            pruneExpired(System.currentTimeMillis());
            this.pendingReplays.put(buildKey(replay == null ? null : replay.probeFrame()), replay);
            trimToBudget();
        }

        private synchronized PendingFullReplay remove(ChunkHotspotFrame responseFrame) {
            pruneExpired(System.currentTimeMillis());
            return this.pendingReplays.remove(buildKey(responseFrame));
        }

        private synchronized boolean isEmpty() {
            pruneExpired(System.currentTimeMillis());
            return this.pendingReplays.isEmpty();
        }

        private void pruneExpired(long nowMillis) {
            Iterator<Map.Entry<String, PendingFullReplay>> iterator = this.pendingReplays.entrySet().iterator();
            while (iterator.hasNext()) {
                PendingFullReplay replay = iterator.next().getValue();
                if (replay == null || nowMillis - replay.recordedAtMillis() > ENTRY_TTL_MILLIS) {
                    iterator.remove();
                }
            }
        }

        private void trimToBudget() {
            while (this.pendingReplays.size() > MAX_PENDING_PROBES_PER_CHANNEL) {
                Iterator<Map.Entry<String, PendingFullReplay>> iterator = this.pendingReplays.entrySet().iterator();
                if (!iterator.hasNext()) {
                    return;
                }
                iterator.next();
                iterator.remove();
            }
        }
    }

    private static String buildKey(ChunkHotspotFrame frame) {
        return Math.max(frame == null ? 0L : frame.epoch(), 0L)
                + ":"
                + chunkKeyText(frame == null ? ChunkPacketCoordinate.unknown() : frame.coordinate())
                + ":"
                + Math.max(frame == null ? 0L : frame.fullSnapshotVersion(), 0L)
                + ":"
                + (frame == null || frame.payloadHash() == null ? "" : frame.payloadHash());
    }

    private static String chunkKeyText(ChunkPacketCoordinate coordinate) {
        return coordinate == null || !coordinate.present()
                ? "<unknown>"
                : coordinate.chunkX() + "," + coordinate.chunkZ();
    }
}
