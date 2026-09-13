package com.PinkCats.bandwidthoptimizer.chunk.integration.transport;

import com.PinkCats.bandwidthoptimizer.chunk.classify.packet.ChunkPacketCoordinate;
import com.PinkCats.bandwidthoptimizer.chunk.protocol.hotspot.ChunkHotspotFrame;
import io.netty.channel.Channel;
import io.netty.util.AttributeKey;

import java.util.Arrays;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.TimeUnit;

public final class ChunkWatchBoundaryReusePendingStore {

    private static final long ENTRY_TTL_NANOS = TimeUnit.SECONDS.toNanos(15L);
    private static final int MAX_PENDING_PROBES_PER_CHANNEL = 512;
    private static final long MAX_PENDING_BYTES_PER_CHANNEL = 8L * 1024L * 1024L;
    private static final int MAX_PENDING_PROBES_GLOBAL = 4096;
    private static final long MAX_PENDING_BYTES_GLOBAL = 64L * 1024L * 1024L;
    private static final AttributeKey<ChannelPendingState> CHANNEL_STATE_KEY =
            AttributeKey.valueOf("bandwidthoptimizer:watch_boundary_pending_replays");
    private static final Object BUDGET_LOCK = new Object();
    private static long retainedBytes;
    private static int retainedEntries;

    private ChunkWatchBoundaryReusePendingStore() {}

    public static void rememberProbe(Channel channel, ChunkHotspotFrame probeFrame, byte[] originalPacketBytes) {
        if (channel == null
                || !channel.isOpen()
                || probeFrame == null
                || probeFrame.coordinate() == null
                || !probeFrame.coordinate().present()
                || probeFrame.fullSnapshotVersion() <= 0L
                || probeFrame.payloadHash() == null
                || probeFrame.payloadHash().isBlank()
                || originalPacketBytes == null
                || originalPacketBytes.length == 0
                || originalPacketBytes.length > MAX_PENDING_BYTES_PER_CHANNEL) {
            return;
        }

        ChannelPendingState state = getOrCreateState(channel);
        long nowNanos = System.nanoTime();
        boolean accepted;
        synchronized (BUDGET_LOCK) {
            if (!channel.isOpen() || channel.attr(CHANNEL_STATE_KEY).get() != state) {
                return;
            }
            pruneExpired(state, nowNanos);
            accepted = put(state, probeFrame, originalPacketBytes, nowNanos + ENTRY_TTL_NANOS);
        }
        if (accepted) {
            ensureExpiryScheduled(channel, state);
        }
    }

    public static PendingFullReplay takePendingFull(Channel channel, ChunkHotspotFrame responseFrame) {
        if (channel == null || responseFrame == null) {
            return null;
        }
        ChannelPendingState state = channel.attr(CHANNEL_STATE_KEY).get();
        if (state == null) {
            return null;
        }
        synchronized (BUDGET_LOCK) {
            if (channel.attr(CHANNEL_STATE_KEY).get() != state) {
                return null;
            }
            pruneExpired(state, System.nanoTime());
            PendingFullReplay replay = state.pendingReplays.remove(buildKey(responseFrame));
            release(replay, state);
            return replay;
        }
    }

    public static void clearPendingFull(Channel channel, ChunkHotspotFrame responseFrame) {
        takePendingFull(channel, responseFrame);
    }

    public static void clearChannel(Channel channel) {
        if (channel == null) {
            return;
        }
        synchronized (BUDGET_LOCK) {
            ChannelPendingState state = channel.attr(CHANNEL_STATE_KEY).getAndSet(null);
            if (state == null) {
                return;
            }
            for (PendingFullReplay replay : state.pendingReplays.values()) {
                release(replay, state);
            }
            state.pendingReplays.clear();
            state.expiryScheduled = false;
        }
    }

    static RetainedUsage snapshotUsage() {
        synchronized (BUDGET_LOCK) {
            return new RetainedUsage(retainedEntries, retainedBytes);
        }
    }

    public record PendingFullReplay(
            ChunkHotspotFrame probeFrame,
            byte[] originalPacketBytes,
            long expiresAtNanos
    ) {
        public PendingFullReplay {
            originalPacketBytes = originalPacketBytes == null ? new byte[0] : Arrays.copyOf(originalPacketBytes, originalPacketBytes.length);
        }

        public byte[] copyOriginalPacketBytes() {
            return Arrays.copyOf(this.originalPacketBytes, this.originalPacketBytes.length);
        }
    }

    record RetainedUsage(int entries, long bytes) {}

    private static ChannelPendingState getOrCreateState(Channel channel) {
        ChannelPendingState existing = channel.attr(CHANNEL_STATE_KEY).get();
        if (existing != null) {
            return existing;
        }
        ChannelPendingState created = new ChannelPendingState();
        ChannelPendingState raced = channel.attr(CHANNEL_STATE_KEY).setIfAbsent(created);
        if (raced != null) {
            return raced;
        }
        channel.closeFuture().addListener(ignored -> clearChannel(channel));
        return created;
    }

    private static boolean put(ChannelPendingState state, ChunkHotspotFrame frame, byte[] bytes, long expiresAtNanos) {
        String key = buildKey(frame);
        PendingFullReplay previous = state.pendingReplays.get(key);
        long previousBytes = lengthOf(previous);
        int previousEntries = previous == null ? 0 : 1;

        while (state.pendingReplays.size() - previousEntries + 1 > MAX_PENDING_PROBES_PER_CHANNEL
                || state.retainedBytes - previousBytes + bytes.length > MAX_PENDING_BYTES_PER_CHANNEL) {
            PendingFullReplay removed = removeEldestOtherThan(state, key);
            if (removed == null) {
                return false;
            }
            release(removed, state);
        }

        long projectedGlobalBytes = retainedBytes - previousBytes + bytes.length;
        int projectedGlobalEntries = retainedEntries - previousEntries + 1;
        if (projectedGlobalBytes > MAX_PENDING_BYTES_GLOBAL || projectedGlobalEntries > MAX_PENDING_PROBES_GLOBAL) {
            return false;
        }

        PendingFullReplay replay = new PendingFullReplay(frame, bytes, expiresAtNanos);
        state.pendingReplays.put(key, replay);
        state.retainedBytes = state.retainedBytes - previousBytes + replay.originalPacketBytes().length;
        retainedBytes = projectedGlobalBytes;
        retainedEntries = projectedGlobalEntries;
        return true;
    }

    private static PendingFullReplay removeEldestOtherThan(ChannelPendingState state, String retainedKey) {
        Iterator<Map.Entry<String, PendingFullReplay>> iterator = state.pendingReplays.entrySet().iterator();
        while (iterator.hasNext()) {
            Map.Entry<String, PendingFullReplay> entry = iterator.next();
            if (entry.getKey().equals(retainedKey)) {
                continue;
            }
            iterator.remove();
            return entry.getValue();
        }
        return null;
    }

    private static void pruneExpired(ChannelPendingState state, long nowNanos) {
        Iterator<PendingFullReplay> iterator = state.pendingReplays.values().iterator();
        while (iterator.hasNext()) {
            PendingFullReplay replay = iterator.next();
            if (replay == null || nowNanos >= replay.expiresAtNanos()) {
                iterator.remove();
                release(replay, state);
            }
        }
    }

    private static void release(PendingFullReplay replay, ChannelPendingState state) {
        if (replay == null) {
            return;
        }
        long bytes = lengthOf(replay);
        state.retainedBytes = Math.max(state.retainedBytes - bytes, 0L);
        retainedBytes = Math.max(retainedBytes - bytes, 0L);
        retainedEntries = Math.max(retainedEntries - 1, 0);
    }

    private static long lengthOf(PendingFullReplay replay) {
        return replay == null || replay.originalPacketBytes() == null ? 0L : replay.originalPacketBytes().length;
    }

    private static void ensureExpiryScheduled(Channel channel, ChannelPendingState state) {
        long delayNanos;
        synchronized (BUDGET_LOCK) {
            if (channel.attr(CHANNEL_STATE_KEY).get() != state
                    || state.expiryScheduled
                    || state.pendingReplays.isEmpty()) {
                return;
            }
            state.expiryScheduled = true;
            delayNanos = Math.max(earliestExpiry(state) - System.nanoTime(), 1L);
        }
        channel.eventLoop().schedule(() -> expireAndReschedule(channel, state), delayNanos, TimeUnit.NANOSECONDS);
    }

    private static void expireAndReschedule(Channel channel, ChannelPendingState state) {
        synchronized (BUDGET_LOCK) {
            if (channel.attr(CHANNEL_STATE_KEY).get() != state) {
                return;
            }
            state.expiryScheduled = false;
            pruneExpired(state, System.nanoTime());
        }
        ensureExpiryScheduled(channel, state);
    }

    private static long earliestExpiry(ChannelPendingState state) {
        long earliest = Long.MAX_VALUE;
        for (PendingFullReplay replay : state.pendingReplays.values()) {
            if (replay != null) {
                earliest = Math.min(earliest, replay.expiresAtNanos());
            }
        }
        return earliest;
    }

    private static String buildKey(ChunkHotspotFrame frame) {
        return Math.max(frame == null ? 0L : frame.epoch(), 0L)
                + ":" + chunkKeyText(frame == null ? ChunkPacketCoordinate.unknown() : frame.coordinate())
                + ":" + Math.max(frame == null ? 0L : frame.fullSnapshotVersion(), 0L)
                + ":" + (frame == null || frame.payloadHash() == null ? "" : frame.payloadHash());
    }

    private static String chunkKeyText(ChunkPacketCoordinate coordinate) {
        return coordinate == null || !coordinate.present()
                ? "<unknown>"
                : coordinate.chunkX() + "," + coordinate.chunkZ();
    }

    private static final class ChannelPendingState {
        private final LinkedHashMap<String, PendingFullReplay> pendingReplays =
                new LinkedHashMap<>(16, 0.75F, true);
        private long retainedBytes;
        private boolean expiryScheduled;
    }
}
