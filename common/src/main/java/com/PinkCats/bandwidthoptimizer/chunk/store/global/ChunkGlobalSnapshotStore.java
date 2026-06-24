package com.PinkCats.bandwidthoptimizer.chunk.store.global;

import com.PinkCats.bandwidthoptimizer.Bandwidthoptimizer;
import com.PinkCats.bandwidthoptimizer.Config;
import com.PinkCats.bandwidthoptimizer.chunk.classify.ChunkLaneKind;
import com.PinkCats.bandwidthoptimizer.chunk.classify.packet.ChunkPacketCoordinate;
import com.PinkCats.bandwidthoptimizer.chunk.classify.packet.ChunkPacketDescriptor;
import com.PinkCats.bandwidthoptimizer.chunk.snapshot.lane.ChunkLanePacketSnapshot;
import com.PinkCats.bandwidthoptimizer.chunk.snapshot.lane.ChunkLaneSnapshot;
import com.PinkCats.bandwidthoptimizer.chunk.snapshot.shadow.ChunkShadowSnapshot;
import com.PinkCats.bandwidthoptimizer.chunk.snapshot.ChunkSnapshotFingerprint;
import com.PinkCats.bandwidthoptimizer.chunk.store.blob.ChunkBlobHandle;
import com.PinkCats.bandwidthoptimizer.debug.DiagnosticToolRegistry;

import java.util.HashMap;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.Map;

public final class ChunkGlobalSnapshotStore {

    private static final Object LOCK = new Object();
    private static final long DEFAULT_BLOB_BUDGET_BYTES =
            Config.RuntimeProperty.Chunk.DEFAULT_GLOBAL_STORE_BUDGET_BYTES;
    private static final int DEFAULT_MAX_MATERIALIZED_VERSIONS_PER_CHUNK =
            Config.RuntimeProperty.Chunk.DEFAULT_GLOBAL_STORE_MAX_VERSIONS_PER_CHUNK;
    private static final Map<String, ChunkGlobalSnapshotRecord> SNAPSHOT_RECORDS = new HashMap<>();
    private static final Map<String, LinkedHashSet<String>> HOTSPOT_DISTINCT_HASHES = new HashMap<>();
    private static final Map<String, LinkedHashMap<Long, ChunkMaterializedSnapshotRecord>> MATERIALIZED_SNAPSHOTS =
            new HashMap<>();
    private static final long MAX_BLOB_BUDGET_BYTES = readLongProperty(
            Config.RuntimeProperty.Chunk.GLOBAL_STORE_BUDGET_BYTES,
            DEFAULT_BLOB_BUDGET_BYTES
    );
    private static final int MAX_MATERIALIZED_VERSIONS_PER_CHUNK = (int) Math.max(
            readLongProperty(
                    Config.RuntimeProperty.Chunk.GLOBAL_STORE_MAX_VERSIONS_PER_CHUNK,
                    DEFAULT_MAX_MATERIALIZED_VERSIONS_PER_CHUNK
            ),
            1L
    );

    private static long retainedBlobBytes;
    private static long materializedSnapshotCount;
    private static long totalBlobEvictions;
    private static long totalMaterializedSnapshotEvictions;
    private static long materializedUpdateCount;

    private ChunkGlobalSnapshotStore() {}

    public static ChunkGlobalStoreObservation observeOutboundSnapshot(
            ChunkPacketDescriptor descriptor,
            ChunkSnapshotFingerprint fingerprint,
            byte[] encodedPacketBytes
    ) {
        if (descriptor == null || fingerprint == null || fingerprint.hashHex() == null || fingerprint.hashHex().isBlank()) {
            return null;
        }

        synchronized (LOCK) {
            String storeKey = fingerprint.hashHex();
            ChunkGlobalSnapshotRecord record = SNAPSHOT_RECORDS.get(storeKey);
            boolean existingBeforeObserve = record != null;
            if (!existingBeforeObserve) {
                record = new ChunkGlobalSnapshotRecord(storeKey, descriptor, fingerprint, encodedPacketBytes);
                SNAPSHOT_RECORDS.put(storeKey, record);
                retainedBlobBytes += Math.max(record.encodedBytes(), 0);
                HOTSPOT_DISTINCT_HASHES
                        .computeIfAbsent(descriptor.hotspotKind().logName(), ignored -> new LinkedHashSet<>())
                        .add(storeKey);
            } else if (record.rememberHotspot(descriptor.hotspotKind().logName())) {
                HOTSPOT_DISTINCT_HASHES
                        .computeIfAbsent(descriptor.hotspotKind().logName(), ignored -> new LinkedHashSet<>())
                        .add(storeKey);
            }

            ChunkGlobalStoreObservation observation = record.recordObservation(
                    descriptor,
                    fingerprint,
                    existingBeforeObserve,
                    readHotspotDistinctCount(descriptor.hotspotKind().logName()),
                    SNAPSHOT_RECORDS.size(),
                    materializedSnapshotCount,
                    retainedBlobBytes,
                    MAX_BLOB_BUDGET_BYTES,
                    totalBlobEvictions
            );
            evictUnreferencedBlobsToBudget("observe");
            return observation;
        }
    }

    public static void observeMaterializedSnapshot(String channelId, ChunkShadowSnapshot snapshot) {
        if (channelId == null
                || channelId.isBlank()
                || snapshot == null
                || snapshot.coordinate() == null
                || !snapshot.coordinate().present()
                || !snapshot.hasFullSnapshot()) {
            return;
        }

        synchronized (LOCK) {
            ChunkMaterializedSnapshotRecord materializedSnapshotRecord = buildMaterializedSnapshotRecord(channelId, snapshot);
            String chunkStoreKey = buildChunkVersionStoreKey(channelId, snapshot.epoch(), snapshot.coordinate());
            LinkedHashMap<Long, ChunkMaterializedSnapshotRecord> versionRecords = MATERIALIZED_SNAPSHOTS.computeIfAbsent(
                    chunkStoreKey,
                    ignored -> new LinkedHashMap<>(16, 0.75F, true)
            );

            ChunkMaterializedSnapshotRecord replacedRecord =
                    versionRecords.put(materializedSnapshotRecord.fullSnapshotVersion(), materializedSnapshotRecord);
            if (replacedRecord != null) {
                releaseMaterializedSnapshotReferences(replacedRecord);
            } else {
                materializedSnapshotCount++;
            }
            retainMaterializedSnapshotReferences(materializedSnapshotRecord);
            materializedUpdateCount++;
            evictOldMaterializedVersions(chunkStoreKey, versionRecords);
            evictUnreferencedBlobsToBudget("materialize");

            if (shouldLogDiagnose() && shouldLogSample(materializedUpdateCount)) {
                Bandwidthoptimizer.LOGGER.info(
                        "[BO:Diag:chunkGlobalSnapshot] event=materialize channel={}, chunk={}, fullVersion={}, fullHash={}, packetCount={}, blobRefs={}, retainedBlobBytes={}/{}, materializedSnapshots={}",
                        channelId,
                        snapshot.coordinate().logText(),
                        snapshot.fullSnapshotVersion(),
                        shortenHash(snapshot.fullSnapshotHash()),
                        materializedSnapshotRecord.materializedPacketCount(),
                        materializedSnapshotRecord.referencedBlobHashes().size(),
                        retainedBlobBytes,
                        MAX_BLOB_BUDGET_BYTES,
                        materializedSnapshotCount
                );
            }
        }
    }

    public static byte[] findMaterializedFullChunkPacket(
            String channelId,
            long scopeId,
            ChunkPacketCoordinate coordinate,
            long expectedFullSnapshotVersion,
            String expectedPayloadHash
    ) {
        if (channelId == null
                || channelId.isBlank()
                || coordinate == null
                || !coordinate.present()
                || expectedFullSnapshotVersion <= 0L) {
            return null;
        }

        synchronized (LOCK) {
            ChunkMaterializedSnapshotRecord exactScopeRecord = findMaterializedSnapshotRecord(
                    buildChunkVersionStoreKey(channelId, scopeId, coordinate),
                    expectedFullSnapshotVersion,
                    expectedPayloadHash
            );
            return readMaterializedBlobBytes(exactScopeRecord);
        }
    }

    public static byte[] findMaterializedFullChunkPacket(
            String channelId,
            ChunkPacketCoordinate coordinate,
            long expectedFullSnapshotVersion,
            String expectedPayloadHash
    ) {
        return findMaterializedFullChunkPacket(channelId, 0L, coordinate, expectedFullSnapshotVersion, expectedPayloadHash);
    }

    public static ChunkBlobHandle findBlob(String payloadHash) {
        if (payloadHash == null || payloadHash.isBlank()) {
            return null;
        }

        synchronized (LOCK) {
            ChunkGlobalSnapshotRecord record = SNAPSHOT_RECORDS.get(payloadHash);
            return record == null ? null : record.copyBlobHandle();
        }
    }

    public static void invalidateChunk(String channelId, long scopeId, ChunkPacketCoordinate coordinate) {
        if (channelId == null || channelId.isBlank() || coordinate == null || !coordinate.present()) {
            return;
        }

        synchronized (LOCK) {
            releaseChunkVersionStore(buildChunkVersionStoreKey(channelId, scopeId, coordinate), "invalidate_chunk");
            evictUnreferencedBlobsToBudget("invalidate_chunk");
        }
    }

    public static void invalidateChunk(String channelId, ChunkPacketCoordinate coordinate) {
        invalidateChunk(channelId, 0L, coordinate);
    }

    public static void invalidateChunkAcrossScopes(String channelId, ChunkPacketCoordinate coordinate) {
        if (channelId == null || channelId.isBlank() || coordinate == null || !coordinate.present()) {
            return;
        }

        synchronized (LOCK) {
            String channelPrefix = channelId + ":";
            Iterator<Map.Entry<String, LinkedHashMap<Long, ChunkMaterializedSnapshotRecord>>> iterator =
                    MATERIALIZED_SNAPSHOTS.entrySet().iterator();
            while (iterator.hasNext()) {
                Map.Entry<String, LinkedHashMap<Long, ChunkMaterializedSnapshotRecord>> entry = iterator.next();
                if (!entry.getKey().startsWith(channelPrefix)) {
                    continue;
                }
                LinkedHashMap<Long, ChunkMaterializedSnapshotRecord> versionRecords = entry.getValue();
                if (!containsCoordinate(versionRecords, coordinate)) {
                    continue;
                }
                releaseChunkVersionStore(entry.getKey(), "invalidate_chunk_across_scopes", versionRecords, iterator);
            }
            evictUnreferencedBlobsToBudget("invalidate_chunk_across_scopes");
        }
    }


    public static void clearChannel(String channelId) {
        if (channelId == null || channelId.isBlank()) {
            return;
        }

        synchronized (LOCK) {
            String channelPrefix = channelId + ":";
            Iterator<Map.Entry<String, LinkedHashMap<Long, ChunkMaterializedSnapshotRecord>>> iterator =
                    MATERIALIZED_SNAPSHOTS.entrySet().iterator();
            while (iterator.hasNext()) {
                Map.Entry<String, LinkedHashMap<Long, ChunkMaterializedSnapshotRecord>> entry = iterator.next();
                if (!entry.getKey().startsWith(channelPrefix)) {
                    continue;
                }
                releaseChunkVersionStore(entry.getKey(), "clear_channel", entry.getValue(), iterator);
            }
            evictUnreferencedBlobsToBudget("clear_channel");
        }
    }

    private static long readHotspotDistinctCount(String hotspotName) {
        LinkedHashSet<String> distinctHashes = HOTSPOT_DISTINCT_HASHES.get(hotspotName);
        return distinctHashes == null ? 0L : distinctHashes.size();
    }

    private static ChunkMaterializedSnapshotRecord buildMaterializedSnapshotRecord(
            String channelId,
            ChunkShadowSnapshot snapshot
    ) {
        LinkedHashMap<String, String> blobHashesByMaterializedKey = new LinkedHashMap<>();
        LinkedHashSet<String> referencedBlobHashes = new LinkedHashSet<>();
        long totalMaterializedBytes = 0L;
        int materializedPacketCount = 0;

        for (Map.Entry<ChunkLaneKind, ChunkLaneSnapshot> entry : snapshot.laneSnapshots().entrySet()) {
            ChunkLaneSnapshot laneSnapshot = entry.getValue();
            if (laneSnapshot == null) {
                continue;
            }

            for (Map.Entry<String, ChunkLanePacketSnapshot> packetEntry : laneSnapshot.packetSnapshots().entrySet()) {
                ChunkLanePacketSnapshot packetSnapshot = packetEntry.getValue();
                if (packetSnapshot == null || packetSnapshot.payloadHash() == null || packetSnapshot.payloadHash().isBlank()) {
                    continue;
                }

                ChunkGlobalSnapshotRecord record = ensureBlobRecord(packetSnapshot, snapshot.coordinate());
                String materializedKey = buildMaterializedPacketKey(laneSnapshot.laneKind(), packetEntry.getKey());
                blobHashesByMaterializedKey.put(materializedKey, record.snapshotHash());
                referencedBlobHashes.add(record.snapshotHash());
                totalMaterializedBytes += Math.max(packetSnapshot.encodedBytes(), 0);
                materializedPacketCount++;
            }
        }

        return new ChunkMaterializedSnapshotRecord(
                channelId,
                snapshot.epoch(),
                snapshot.coordinate(),
                snapshot.fullSnapshotVersion(),
                snapshot.fullSnapshotHash(),
                snapshot.fullSnapshotShortHash(),
                blobHashesByMaterializedKey,
                referencedBlobHashes,
                totalMaterializedBytes,
                materializedPacketCount,
                System.currentTimeMillis()
        );
    }

    private static ChunkGlobalSnapshotRecord ensureBlobRecord(
            ChunkLanePacketSnapshot packetSnapshot,
            ChunkPacketCoordinate coordinate
    ) {
        ChunkGlobalSnapshotRecord record = SNAPSHOT_RECORDS.get(packetSnapshot.payloadHash());
        if (record != null) {
            if (record.rememberHotspot(packetSnapshot.hotspotKind().logName())) {
                HOTSPOT_DISTINCT_HASHES
                        .computeIfAbsent(packetSnapshot.hotspotKind().logName(), ignored -> new LinkedHashSet<>())
                        .add(packetSnapshot.payloadHash());
            }
            return record;
        }

        ChunkPacketDescriptor syntheticDescriptor = new ChunkPacketDescriptor(
                packetSnapshot.protocolName(),
                packetSnapshot.packetClassName(),
                packetSnapshot.hotspotKind(),
                packetSnapshot.laneKind(),
                coordinate
        );
        ChunkSnapshotFingerprint syntheticFingerprint = new ChunkSnapshotFingerprint(
                "sha256",
                packetSnapshot.payloadHash(),
                packetSnapshot.payloadShortHash(),
                packetSnapshot.encodedBytes()
        );
        ChunkGlobalSnapshotRecord newRecord = new ChunkGlobalSnapshotRecord(
                packetSnapshot.payloadHash(),
                syntheticDescriptor,
                syntheticFingerprint,
                packetSnapshot.copyOriginalPacketBytes()
        );
        SNAPSHOT_RECORDS.put(packetSnapshot.payloadHash(), newRecord);
        retainedBlobBytes += Math.max(newRecord.encodedBytes(), 0);
        HOTSPOT_DISTINCT_HASHES
                .computeIfAbsent(packetSnapshot.hotspotKind().logName(), ignored -> new LinkedHashSet<>())
                .add(packetSnapshot.payloadHash());
        return newRecord;
    }

    private static void retainMaterializedSnapshotReferences(ChunkMaterializedSnapshotRecord record) {
        for (String blobHash : record.referencedBlobHashes()) {
            ChunkGlobalSnapshotRecord blobRecord = SNAPSHOT_RECORDS.get(blobHash);
            if (blobRecord != null) {
                blobRecord.retainReference();
            }
        }
    }

    private static void releaseMaterializedSnapshotReferences(ChunkMaterializedSnapshotRecord record) {
        for (String blobHash : record.referencedBlobHashes()) {
            ChunkGlobalSnapshotRecord blobRecord = SNAPSHOT_RECORDS.get(blobHash);
            if (blobRecord != null) {
                blobRecord.releaseReference();
            }
        }
    }

    private static void evictOldMaterializedVersions(
            String chunkStoreKey,
            LinkedHashMap<Long, ChunkMaterializedSnapshotRecord> versionRecords
    ) {
        while (versionRecords.size() > MAX_MATERIALIZED_VERSIONS_PER_CHUNK) {
            Map.Entry<Long, ChunkMaterializedSnapshotRecord> eldestEntry = versionRecords.entrySet().iterator().next();
            versionRecords.remove(eldestEntry.getKey());
            materializedSnapshotCount = Math.max(materializedSnapshotCount - 1L, 0L);
            totalMaterializedSnapshotEvictions++;
            releaseMaterializedSnapshotReferences(eldestEntry.getValue());
            if (shouldLogDiagnose()) {
                Bandwidthoptimizer.LOGGER.info(
                        "[BO:Diag:chunkGlobalSnapshot] event=evict scope=materialized_snapshot, reason=version_limit, chunk={}, fullVersion={}, fullHash={}, retainedVersions={}, limit={}",
                        eldestEntry.getValue().coordinate().logText(),
                        eldestEntry.getValue().fullSnapshotVersion(),
                        shortenHash(eldestEntry.getValue().fullSnapshotHash()),
                        versionRecords.size(),
                        MAX_MATERIALIZED_VERSIONS_PER_CHUNK
                );
            }
        }

        if (versionRecords.isEmpty()) {
            MATERIALIZED_SNAPSHOTS.remove(chunkStoreKey);
        }
    }

    // release blob & Materialized fix memory leak.
    private static void evictUnreferencedBlobsToBudget(String reason) {
        while (retainedBlobBytes > MAX_BLOB_BUDGET_BYTES) {
            Map.Entry<String, ChunkGlobalSnapshotRecord> candidateEntry = findEvictionCandidate();
            if (candidateEntry == null) {
                if (!releaseOldestMaterializedSnapshot("blob_budget_" + reason)) {
                    break;
                }
                continue;
            }

            ChunkGlobalSnapshotRecord removedRecord = SNAPSHOT_RECORDS.remove(candidateEntry.getKey());
            if (removedRecord == null) {
                continue;
            }

            retainedBlobBytes = Math.max(retainedBlobBytes - removedRecord.encodedBytes(), 0L);
            totalBlobEvictions++;
            removeHotspotDistinctHashes(removedRecord);
            if (shouldLogDiagnose()) {
                Bandwidthoptimizer.LOGGER.info(
                        "[BO:Diag:chunkGlobalSnapshot] event=evict scope=blob, reason={}, hash={}, encodedBytes={}, retainedBlobBytes={}/{}",
                        reason,
                        removedRecord.snapshotShortHash(),
                        removedRecord.encodedBytes(),
                        retainedBlobBytes,
                        MAX_BLOB_BUDGET_BYTES
                );
            }
        }
    }

    // release blob fix memory leak.
    private static boolean releaseOldestMaterializedSnapshot(String reason) {
        MaterializedSnapshotEvictionCandidate candidate = findOldestMaterializedSnapshotCandidate();
        if (candidate == null) {
            return false;
        }

        LinkedHashMap<Long, ChunkMaterializedSnapshotRecord> versionRecords =
                MATERIALIZED_SNAPSHOTS.get(candidate.chunkStoreKey());
        if (versionRecords == null) {
            return false;
        }

        ChunkMaterializedSnapshotRecord removedRecord = versionRecords.remove(candidate.fullSnapshotVersion());
        if (removedRecord == null) {
            return false;
        }

        releaseMaterializedSnapshotReferences(removedRecord);
        materializedSnapshotCount = Math.max(materializedSnapshotCount - 1L, 0L);
        totalMaterializedSnapshotEvictions++;
        if (versionRecords.isEmpty()) {
            MATERIALIZED_SNAPSHOTS.remove(candidate.chunkStoreKey());
        }
        if (shouldLogDiagnose()) {
            Bandwidthoptimizer.LOGGER.info(
                    "[BO:Diag:chunkGlobalSnapshot] event=evict scope=materialized_snapshot, reason={}, chunkStoreKey={}, chunk={}, fullVersion={}, fullHash={}, retainedBlobBytes={}/{}",
                    reason,
                    candidate.chunkStoreKey(),
                    removedRecord.coordinate().logText(),
                    removedRecord.fullSnapshotVersion(),
                    shortenHash(removedRecord.fullSnapshotHash()),
                    retainedBlobBytes,
                    MAX_BLOB_BUDGET_BYTES
            );
        }
        return true;
    }

    // final snapshot
    private static MaterializedSnapshotEvictionCandidate findOldestMaterializedSnapshotCandidate() {
        MaterializedSnapshotEvictionCandidate candidate = null;
        long oldestUpdatedAtMillis = Long.MAX_VALUE;
        for (Map.Entry<String, LinkedHashMap<Long, ChunkMaterializedSnapshotRecord>> chunkEntry :
                MATERIALIZED_SNAPSHOTS.entrySet()) {
            LinkedHashMap<Long, ChunkMaterializedSnapshotRecord> versionRecords = chunkEntry.getValue();
            if (versionRecords == null || versionRecords.isEmpty()) {
                continue;
            }

            for (Map.Entry<Long, ChunkMaterializedSnapshotRecord> versionEntry : versionRecords.entrySet()) {
                ChunkMaterializedSnapshotRecord record = versionEntry.getValue();
                if (record == null) {
                    continue;
                }
                long updatedAtMillis = record.updatedAtMillis();
                if (candidate == null || updatedAtMillis < oldestUpdatedAtMillis) {
                    candidate = new MaterializedSnapshotEvictionCandidate(
                            chunkEntry.getKey(),
                            versionEntry.getKey()
                    );
                    oldestUpdatedAtMillis = updatedAtMillis;
                }
            }
        }
        return candidate;
    }

    // final blob。
    private static Map.Entry<String, ChunkGlobalSnapshotRecord> findEvictionCandidate() {
        Map.Entry<String, ChunkGlobalSnapshotRecord> candidateEntry = null;
        long oldestAccessAtMillis = Long.MAX_VALUE;
        for (Map.Entry<String, ChunkGlobalSnapshotRecord> entry : SNAPSHOT_RECORDS.entrySet()) {
            ChunkGlobalSnapshotRecord record = entry.getValue();
            if (record == null || !record.evictable()) {
                continue;
            }

            long lastAccessAtMillis = record.lastAccessAtMillis();
            if (candidateEntry == null || lastAccessAtMillis < oldestAccessAtMillis) {
                candidateEntry = entry;
                oldestAccessAtMillis = lastAccessAtMillis;
            }
        }
        return candidateEntry;
    }

    private record MaterializedSnapshotEvictionCandidate(
            String chunkStoreKey,
            long fullSnapshotVersion
    ) {
    }

    private static void removeHotspotDistinctHashes(ChunkGlobalSnapshotRecord record) {
        for (String hotspotName : record.observedHotspotNames()) {
            LinkedHashSet<String> distinctHashes = HOTSPOT_DISTINCT_HASHES.get(hotspotName);
            if (distinctHashes == null) {
                continue;
            }
            distinctHashes.remove(record.snapshotHash());
            if (distinctHashes.isEmpty()) {
                HOTSPOT_DISTINCT_HASHES.remove(hotspotName);
            }
        }
    }

    private static void releaseChunkVersionStore(String chunkStoreKey, String reason) {
        releaseChunkVersionStore(chunkStoreKey, reason, MATERIALIZED_SNAPSHOTS.get(chunkStoreKey), null);
    }

    private static void releaseChunkVersionStore(
            String chunkStoreKey,
            String reason,
            LinkedHashMap<Long, ChunkMaterializedSnapshotRecord> versionRecords,
            Iterator<Map.Entry<String, LinkedHashMap<Long, ChunkMaterializedSnapshotRecord>>> outerIterator
    ) {
        if (versionRecords == null) {
            return;
        }

        for (ChunkMaterializedSnapshotRecord record : versionRecords.values()) {
            releaseMaterializedSnapshotReferences(record);
            materializedSnapshotCount = Math.max(materializedSnapshotCount - 1L, 0L);
        }
        totalMaterializedSnapshotEvictions += versionRecords.size();
        if (outerIterator != null) {
            outerIterator.remove();
        } else {
            MATERIALIZED_SNAPSHOTS.remove(chunkStoreKey);
        }
        if (shouldLogDiagnose()) {
            Bandwidthoptimizer.LOGGER.info(
                    "[BO:Diag:chunkGlobalSnapshot] event=release reason={}, chunkStoreKey={}, releasedVersions={}, retainedBlobBytes={}/{}",
                    reason,
                    chunkStoreKey,
                    versionRecords.size(),
                    retainedBlobBytes,
                    MAX_BLOB_BUDGET_BYTES
            );
        }
    }

    private static String buildChunkVersionStoreKey(String channelId, long scopeId, ChunkPacketCoordinate coordinate) {
        return channelId + ":" + Math.max(scopeId, 0L) + ":" + chunkKeyText(coordinate);
    }

    private static String buildMaterializedPacketKey(ChunkLaneKind laneKind, String semanticKey) {
        String safeSemanticKey = semanticKey == null || semanticKey.isBlank() ? "default" : semanticKey;
        String laneName = laneKind == null ? "<unknown>" : laneKind.logName();
        return laneName + ":" + safeSemanticKey;
    }

    private static String chunkKeyText(ChunkPacketCoordinate coordinate) {
        return coordinate == null || !coordinate.present()
                ? "<unknown>"
                : coordinate.chunkX() + "," + coordinate.chunkZ();
    }

    private static long readLongProperty(String propertyName, long defaultValue) {
        String rawValue = System.getProperty(propertyName);
        if (rawValue == null || rawValue.isBlank()) {
            return defaultValue;
        }

        try {
            return Math.max(Long.parseLong(rawValue.trim()), 1L);
        } catch (NumberFormatException ignored) {
            return defaultValue;
        }
    }

    private static boolean shouldLogSample(long counter) {
        return counter <= 5L || counter % 50L == 0L;
    }

    private static boolean shouldLogDiagnose() {
        return BO_Diag_chunkGlobalSnapshot();
    }

    private static boolean BO_Diag_chunkGlobalSnapshot() {
        return DiagnosticToolRegistry.isEnabled(DiagnosticToolRegistry.Tool.CHUNK_GLOBAL_SNAPSHOT);
    }

    private static String shortenHash(String hashHex) {
        if (hashHex == null || hashHex.isBlank()) {
            return "<none>";
        }
        return hashHex.length() <= 12 ? hashHex : hashHex.substring(0, 12);
    }

    private static ChunkMaterializedSnapshotRecord findMaterializedSnapshotRecord(
            String chunkStoreKey,
            long expectedFullSnapshotVersion,
            String expectedPayloadHash
    ) {
        LinkedHashMap<Long, ChunkMaterializedSnapshotRecord> versionRecords = MATERIALIZED_SNAPSHOTS.get(chunkStoreKey);
        if (versionRecords == null) {
            return null;
        }
        return findMaterializedSnapshotRecord(versionRecords, null, expectedFullSnapshotVersion, expectedPayloadHash);
    }

    private static ChunkMaterializedSnapshotRecord findMaterializedSnapshotRecord(
            LinkedHashMap<Long, ChunkMaterializedSnapshotRecord> versionRecords,
            ChunkPacketCoordinate expectedCoordinate,
            long expectedFullSnapshotVersion,
            String expectedPayloadHash
    ) {
        if (versionRecords == null) {
            return null;
        }
        ChunkMaterializedSnapshotRecord materializedSnapshotRecord = versionRecords.get(expectedFullSnapshotVersion);
        if (materializedSnapshotRecord == null) {
            return null;
        }
        if (expectedCoordinate != null && !sameCoordinate(materializedSnapshotRecord.coordinate(), expectedCoordinate)) {
            return null;
        }
        return materializedSnapshotRecord.matchesFullSnapshotHash(expectedPayloadHash) ? materializedSnapshotRecord : null;
    }

    private static byte[] readMaterializedBlobBytes(ChunkMaterializedSnapshotRecord materializedSnapshotRecord) {
        if (materializedSnapshotRecord == null) {
            return null;
        }
        ChunkGlobalSnapshotRecord blobRecord = SNAPSHOT_RECORDS.get(materializedSnapshotRecord.fullSnapshotHash());
        return blobRecord == null ? null : blobRecord.copyBlobHandle().copyBlobBytes();
    }

    private static boolean containsCoordinate(
            LinkedHashMap<Long, ChunkMaterializedSnapshotRecord> versionRecords,
            ChunkPacketCoordinate coordinate
    ) {
        if (versionRecords == null || coordinate == null || !coordinate.present()) {
            return false;
        }
        for (ChunkMaterializedSnapshotRecord record : versionRecords.values()) {
            if (sameCoordinate(record.coordinate(), coordinate)) {
                return true;
            }
        }
        return false;
    }

    private static boolean sameCoordinate(ChunkPacketCoordinate left, ChunkPacketCoordinate right) {
        return left != null
                && right != null
                && left.present()
                && right.present()
                && left.chunkX() == right.chunkX()
                && left.chunkZ() == right.chunkZ();
    }

    private static final class ChunkMaterializedSnapshotRecord {

        private final String channelId;
        private final long scopeId;
        private final ChunkPacketCoordinate coordinate;
        private final long fullSnapshotVersion;
        private final String fullSnapshotHash;
        private final String fullSnapshotShortHash;
        private final LinkedHashMap<String, String> blobHashesByMaterializedKey;
        private final LinkedHashSet<String> referencedBlobHashes;
        private final long totalMaterializedBytes;
        private final int materializedPacketCount;
        private final long updatedAtMillis;

        private ChunkMaterializedSnapshotRecord(
                String channelId,
                long scopeId,
                ChunkPacketCoordinate coordinate,
                long fullSnapshotVersion,
                String fullSnapshotHash,
                String fullSnapshotShortHash,
                LinkedHashMap<String, String> blobHashesByMaterializedKey,
                LinkedHashSet<String> referencedBlobHashes,
                long totalMaterializedBytes,
                int materializedPacketCount,
                long updatedAtMillis
        ) {
            this.channelId = channelId == null ? "" : channelId;
            this.scopeId = Math.max(scopeId, 0L);
            this.coordinate = coordinate == null ? ChunkPacketCoordinate.unknown() : coordinate;
            this.fullSnapshotVersion = Math.max(fullSnapshotVersion, 0L);
            this.fullSnapshotHash = fullSnapshotHash == null ? "" : fullSnapshotHash;
            this.fullSnapshotShortHash = fullSnapshotShortHash == null ? "" : fullSnapshotShortHash;
            this.blobHashesByMaterializedKey = new LinkedHashMap<>(blobHashesByMaterializedKey);
            this.referencedBlobHashes = new LinkedHashSet<>(referencedBlobHashes);
            this.totalMaterializedBytes = Math.max(totalMaterializedBytes, 0L);
            this.materializedPacketCount = Math.max(materializedPacketCount, 0);
            this.updatedAtMillis = Math.max(updatedAtMillis, 0L);
        }

        private ChunkPacketCoordinate coordinate() {
            return this.coordinate;
        }

        private long fullSnapshotVersion() {
            return this.fullSnapshotVersion;
        }

        private String fullSnapshotHash() {
            return this.fullSnapshotHash;
        }

        private boolean matchesFullSnapshotHash(String expectedPayloadHash) {
            return expectedPayloadHash == null
                    || expectedPayloadHash.isBlank()
                    || expectedPayloadHash.equals(this.fullSnapshotHash);
        }

        private int materializedPacketCount() {
            return this.materializedPacketCount;
        }

        private LinkedHashSet<String> referencedBlobHashes() {
            return new LinkedHashSet<>(this.referencedBlobHashes);
        }

        private long updatedAtMillis() {
            return this.updatedAtMillis;
        }
    }
}
