package com.PinkCats.bandwidthoptimizer.chunk.persistent;

import com.PinkCats.bandwidthoptimizer.chunk.protocol.hotspot.ChunkHotspotFrame;

import java.util.concurrent.atomic.AtomicLong;

public final class ChunkLocalCacheReuseStats {

    public static final String PERSISTENT_MANIFEST_REF_REASON = "reuse_persistent_client_cache_manifest";
    public static final String PERSISTENT_MANIFEST_PATCH_REASON = "patch_persistent_client_cache_manifest_mismatch";
    public static final String RUNTIME_REF_REUSED_FROM_PERSISTENT_CACHE_REASON =
            "runtime_ref_reused_from_persistent_cache";
    public static final String RUNTIME_PATCH_REUSED_FROM_PERSISTENT_CACHE_REASON =
            "runtime_patch_reused_from_persistent_cache";
    public static final String RUNTIME_REF_REUSED_AFTER_PERSISTENT_MANIFEST_REASON =
            "runtime_ref_reused_after_persistent_manifest";
    public static final String RUNTIME_PATCH_REUSED_AFTER_PERSISTENT_MANIFEST_REASON =
            "runtime_patch_reused_after_persistent_manifest";

    private static final AtomicLong TEMPORARY_REUSE_PACKETS = new AtomicLong();
    private static final AtomicLong TEMPORARY_REUSE_SAVED_BYTES = new AtomicLong();
    private static final AtomicLong OFFLINE_REUSE_PACKETS = new AtomicLong();
    private static final AtomicLong OFFLINE_REUSE_SAVED_BYTES = new AtomicLong();
    private static final AtomicLong SERVER_TEMPORARY_REUSE_PACKETS = new AtomicLong();
    private static final AtomicLong SERVER_TEMPORARY_REUSE_SAVED_BYTES = new AtomicLong();
    private static final AtomicLong SERVER_OFFLINE_REUSE_PACKETS = new AtomicLong();
    private static final AtomicLong SERVER_OFFLINE_REUSE_SAVED_BYTES = new AtomicLong();

    private ChunkLocalCacheReuseStats() {
    }


    public static void reset() {
        TEMPORARY_REUSE_PACKETS.set(0L);
        TEMPORARY_REUSE_SAVED_BYTES.set(0L);
        OFFLINE_REUSE_PACKETS.set(0L);
        OFFLINE_REUSE_SAVED_BYTES.set(0L);
        SERVER_TEMPORARY_REUSE_PACKETS.set(0L);
        SERVER_TEMPORARY_REUSE_SAVED_BYTES.set(0L);
        SERVER_OFFLINE_REUSE_PACKETS.set(0L);
        SERVER_OFFLINE_REUSE_SAVED_BYTES.set(0L);
    }

    public static void recordReferenceReuse(
            ChunkHotspotFrame frame,
            int restoredPacketBytes,
            int wireFrameBytes,
            ReuseSource reuseSource
    ) {
        long savedBytes = savedBytes(restoredPacketBytes, wireFrameBytes);
        if (reuseSource == ReuseSource.OFFLINE_PERSISTENT_CACHE) {
            OFFLINE_REUSE_PACKETS.incrementAndGet();
            OFFLINE_REUSE_SAVED_BYTES.addAndGet(savedBytes);
            return;
        }
        TEMPORARY_REUSE_PACKETS.incrementAndGet();
        TEMPORARY_REUSE_SAVED_BYTES.addAndGet(savedBytes);
    }

    public static void recordPatchReuse(
            ChunkHotspotFrame frame,
            int restoredPacketBytes,
            int wireFrameBytes,
            ReuseSource reuseSource
    ) {
        long savedBytes = savedBytes(restoredPacketBytes, wireFrameBytes);
        if (reuseSource == ReuseSource.OFFLINE_PERSISTENT_CACHE) {
            OFFLINE_REUSE_PACKETS.incrementAndGet();
            OFFLINE_REUSE_SAVED_BYTES.addAndGet(savedBytes);
            return;
        }
        TEMPORARY_REUSE_PACKETS.incrementAndGet();
        TEMPORARY_REUSE_SAVED_BYTES.addAndGet(savedBytes);
    }

    public static void recordServerClassifiedReuse(ChunkHotspotFrame frame, int restoredPacketBytes, int wireFrameBytes) {
        if (frame == null || frame.operation() == null) {
            return;
        }
        long savedBytes = savedBytes(restoredPacketBytes, wireFrameBytes);
        if (isPersistentManifestReuseReason(frame.reason())) {
            SERVER_OFFLINE_REUSE_PACKETS.incrementAndGet();
            SERVER_OFFLINE_REUSE_SAVED_BYTES.addAndGet(savedBytes);
            return;
        }
        SERVER_TEMPORARY_REUSE_PACKETS.incrementAndGet();
        SERVER_TEMPORARY_REUSE_SAVED_BYTES.addAndGet(savedBytes);
    }

    public static boolean isPersistentManifestReuseReason(String reason) {
        return PERSISTENT_MANIFEST_REF_REASON.equals(reason)
                || PERSISTENT_MANIFEST_PATCH_REASON.equals(reason);
    }

    public static boolean isClientOfflineReuseAckReason(String reason) {
        return RUNTIME_REF_REUSED_FROM_PERSISTENT_CACHE_REASON.equals(reason)
                || RUNTIME_PATCH_REUSED_FROM_PERSISTENT_CACHE_REASON.equals(reason)
                || RUNTIME_REF_REUSED_AFTER_PERSISTENT_MANIFEST_REASON.equals(reason)
                || RUNTIME_PATCH_REUSED_AFTER_PERSISTENT_MANIFEST_REASON.equals(reason);
    }

    public static Snapshot snapshot() {
        return new Snapshot(
                TEMPORARY_REUSE_PACKETS.get(),
                TEMPORARY_REUSE_SAVED_BYTES.get(),
                OFFLINE_REUSE_PACKETS.get(),
                OFFLINE_REUSE_SAVED_BYTES.get(),
                SERVER_TEMPORARY_REUSE_PACKETS.get(),
                SERVER_TEMPORARY_REUSE_SAVED_BYTES.get(),
                SERVER_OFFLINE_REUSE_PACKETS.get(),
                SERVER_OFFLINE_REUSE_SAVED_BYTES.get()
        );
    }

    private static long savedBytes(int restoredPacketBytes, int wireFrameBytes) {
        return Math.max((long) Math.max(restoredPacketBytes, 0) - Math.max(wireFrameBytes, 0), 0L);
    }

    public enum ReuseSource {
        TEMPORARY_RUNTIME_CACHE,
        OFFLINE_PERSISTENT_CACHE
    }

    public record Snapshot(
            long temporaryReusePackets,
            long temporaryReuseSavedBytes,
            long offlineReusePackets,
            long offlineReuseSavedBytes,
            long serverTemporaryReusePackets,
            long serverTemporaryReuseSavedBytes,
            long serverOfflineReusePackets,
            long serverOfflineReuseSavedBytes
    ) {
        public static Snapshot empty() {
            return new Snapshot(0L, 0L, 0L, 0L, 0L, 0L, 0L, 0L);
        }
    }
}
