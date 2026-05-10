package com.PinkCats.bandwidthoptimizer.chunk.verify;

import com.PinkCats.bandwidthoptimizer.chunk.classify.packet.ChunkPacketCoordinate;
import com.PinkCats.bandwidthoptimizer.chunk.persistent.ChunkLocalCacheReuseStats;
import com.PinkCats.bandwidthoptimizer.chunk.protocol.hotspot.ChunkHotspotFrame;
import com.PinkCats.bandwidthoptimizer.chunk.protocol.hotspot.ChunkHotspotFrameOp;

import java.util.HashMap;
import java.util.Iterator;
import java.util.Map;

public final class ChunkServerOfflineReuseStats {

    private static final Object LOCK = new Object();
    private static final HashMap<String, PendingOfflineReuse> PENDING_REUSE = new HashMap<>();

    private static MutableTotals sentTotals = new MutableTotals();
    private static MutableTotals confirmedTotals = new MutableTotals();
    private static MutableTotals rejectedTotals = new MutableTotals();

    private ChunkServerOfflineReuseStats() {}

    public static void reset() {
        synchronized (LOCK) {
            PENDING_REUSE.clear();
            sentTotals = new MutableTotals();
            confirmedTotals = new MutableTotals();
            rejectedTotals = new MutableTotals();
        }
    }

    public static void recordSent(String channelId, ChunkHotspotFrame frame, int logicalPacketBytes, int wireFrameBytes) {
        if (!isReuseCandidateFrame(frame)) {
            return;
        }
        long safeLogicalPacketBytes = Math.max(logicalPacketBytes, 0);
        long safeWireFrameBytes = Math.max(wireFrameBytes, 0);
        boolean serverPlannedOffline = isServerOfflineReuseFrame(frame);
        synchronized (LOCK) {
            PendingOfflineReuse pending = new PendingOfflineReuse(
                    safeLogicalPacketBytes,
                    safeWireFrameBytes,
                    serverPlannedOffline
            );
            PENDING_REUSE.put(pendingKey(channelId, frame), pending);
            if (serverPlannedOffline) {
                sentTotals.record(safeLogicalPacketBytes, safeWireFrameBytes);
            }
        }
    }

    public static void recordConfirmed(String channelId, ChunkHotspotFrame frame) {
        if (frame == null) {
            return;
        }
        synchronized (LOCK) {
            PendingOfflineReuse pending = PENDING_REUSE.remove(pendingKey(channelId, frame));
            if (pending != null && ChunkLocalCacheReuseStats.isClientOfflineReuseAckReason(frame.reason())) {
                confirmedTotals.record(pending.logicalPacketBytes(), pending.wireFrameBytes());
            }
        }
    }

    public static void recordRejected(String channelId, ChunkHotspotFrame frame) {
        if (frame == null) {
            return;
        }
        synchronized (LOCK) {
            PendingOfflineReuse pending = PENDING_REUSE.remove(pendingKey(channelId, frame));
            if (pending != null && pending.serverPlannedOffline()) {
                rejectedTotals.record(pending.logicalPacketBytes(), pending.wireFrameBytes());
            }
        }
    }

    public static void clearChannel(String channelId) {
        String safeChannelId = safeChannelId(channelId);
        synchronized (LOCK) {
            Iterator<Map.Entry<String, PendingOfflineReuse>> iterator = PENDING_REUSE.entrySet().iterator();
            while (iterator.hasNext()) {
                Map.Entry<String, PendingOfflineReuse> entry = iterator.next();
                if (entry.getKey().startsWith(safeChannelId + "|")) {
                    iterator.remove();
                }
            }
        }
    }

    public static String toPropertiesText() {
        Snapshot snapshot = snapshot();
        StringBuilder builder = new StringBuilder(512);
        appendTotals(builder, "server_offline_reuse_sent", snapshot.sentTotals());
        appendTotals(builder, "server_offline_reuse_confirmed", snapshot.confirmedTotals());
        appendTotals(builder, "server_offline_reuse_rejected", snapshot.rejectedTotals());
        builder.append("server_offline_reuse_pending_frames=").append(snapshot.pendingFrames()).append('\n');
        return builder.toString();
    }

    public static Snapshot snapshot() {
        synchronized (LOCK) {
            return new Snapshot(
                    sentTotals.snapshot(),
                    confirmedTotals.snapshot(),
                    rejectedTotals.snapshot(),
                    PENDING_REUSE.size()
            );
        }
    }

    private static boolean isReuseCandidateFrame(ChunkHotspotFrame frame) {
        return frame != null
                && (frame.operation() == ChunkHotspotFrameOp.PUBLISH_REF
                || frame.operation() == ChunkHotspotFrameOp.PUBLISH_PATCH);
    }

    private static boolean isServerOfflineReuseFrame(ChunkHotspotFrame frame) {
        return frame != null
                && frame.reason() != null
                && ChunkLocalCacheReuseStats.isPersistentManifestReuseReason(frame.reason());
    }

    private static void appendTotals(StringBuilder builder, String prefix, Totals totals) {
        Totals safeTotals = totals == null ? Totals.empty() : totals;
        builder.append(prefix).append("_frames=").append(safeTotals.frames()).append('\n');
        builder.append(prefix).append("_logical_packet_bytes=").append(safeTotals.logicalPacketBytes()).append('\n');
        builder.append(prefix).append("_wire_frame_bytes=").append(safeTotals.wireFrameBytes()).append('\n');
        builder.append(prefix).append("_saved_vs_logical_bytes=").append(safeTotals.savedVsLogicalBytes()).append('\n');
    }

    private static String pendingKey(String channelId, ChunkHotspotFrame frame) {
        ChunkPacketCoordinate coordinate = frame == null ? null : frame.coordinate();
        return safeChannelId(channelId)
                + "|epoch=" + (frame == null ? 0L : frame.epoch())
                + "|observed=" + (frame == null ? 0L : frame.observedPacketCount())
                + "|chunk=" + (coordinate == null || !coordinate.present()
                ? "unknown"
                : coordinate.chunkX() + "," + coordinate.chunkZ())
                + "|full=" + (frame == null ? 0L : frame.fullSnapshotVersion())
                + "|payload=" + safeHash(frame == null ? "" : frame.payloadHash());
    }

    private static String safeChannelId(String channelId) {
        return channelId == null || channelId.isBlank() ? "<unknown>" : channelId;
    }

    private static String safeHash(String hash) {
        return hash == null ? "" : hash;
    }

    private record PendingOfflineReuse(long logicalPacketBytes, long wireFrameBytes, boolean serverPlannedOffline) {
    }

    public record Snapshot(
            Totals sentTotals,
            Totals confirmedTotals,
            Totals rejectedTotals,
            int pendingFrames
    ) {
    }

    public record Totals(
            long frames,
            long logicalPacketBytes,
            long wireFrameBytes
    ) {
        public long savedVsLogicalBytes() {
            return Math.max(this.logicalPacketBytes - this.wireFrameBytes, 0L);
        }

        public static Totals empty() {
            return new Totals(0L, 0L, 0L);
        }
    }

    private static final class MutableTotals {

        private long frames;
        private long logicalPacketBytes;
        private long wireFrameBytes;

        private void record(long logicalPacketBytes, long wireFrameBytes) {
            this.frames++;
            this.logicalPacketBytes += logicalPacketBytes;
            this.wireFrameBytes += wireFrameBytes;
        }

        private Totals snapshot() {
            return new Totals(this.frames, this.logicalPacketBytes, this.wireFrameBytes);
        }
    }
}
