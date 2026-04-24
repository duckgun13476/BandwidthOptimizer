package com.PinkCats.bandwidthoptimizer.chunk.store.global;

import com.PinkCats.bandwidthoptimizer.chunk.classify.ChunkPacketDescriptor;
import com.PinkCats.bandwidthoptimizer.chunk.snapshot.ChunkSnapshotFingerprint;

import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.LongAdder;

public final class ChunkGlobalSnapshotStore {

    private static final ConcurrentHashMap<String, ChunkGlobalSnapshotRecord> SNAPSHOT_RECORDS = new ConcurrentHashMap<>();
    private static final ConcurrentHashMap<String, LongAdder> HOTSPOT_DISTINCT_COUNTS = new ConcurrentHashMap<>();

    private ChunkGlobalSnapshotStore() {}

    public static ChunkGlobalStoreObservation observeOutboundSnapshot(
            ChunkPacketDescriptor descriptor,
            ChunkSnapshotFingerprint fingerprint
    ) {
        if (descriptor == null || fingerprint == null) {
            return null;
        }

        String storeKey = buildStoreKey(descriptor, fingerprint);
        ChunkGlobalSnapshotRecord newRecord = new ChunkGlobalSnapshotRecord(storeKey, descriptor, fingerprint);
        ChunkGlobalSnapshotRecord existingRecord = SNAPSHOT_RECORDS.putIfAbsent(storeKey, newRecord);
        boolean existingBeforeObserve = existingRecord != null;
        if (!existingBeforeObserve) {
            HOTSPOT_DISTINCT_COUNTS
                    .computeIfAbsent(descriptor.hotspotKind().logName(), ignored -> new LongAdder())
                    .increment();
        }

        ChunkGlobalSnapshotRecord record = existingBeforeObserve ? existingRecord : newRecord;
        return record.recordObservation(
                descriptor,
                fingerprint,
                existingBeforeObserve,
                readHotspotDistinctCount(descriptor.hotspotKind().logName()),
                SNAPSHOT_RECORDS.size()
        );
    }

    private static String buildStoreKey(ChunkPacketDescriptor descriptor, ChunkSnapshotFingerprint fingerprint) {
        return descriptor.hotspotKind().logName() + ":" + fingerprint.hashHex();
    }

    private static long readHotspotDistinctCount(String hotspotName) {
        LongAdder longAdder = HOTSPOT_DISTINCT_COUNTS.get(hotspotName);
        return longAdder == null ? 0L : longAdder.sum();
    }
}
