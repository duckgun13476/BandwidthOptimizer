package com.PinkCats.bandwidthoptimizer.chunk.PeerState;

import com.PinkCats.bandwidthoptimizer.chunk.classify.packet.ChunkPacketCoordinate;
import com.PinkCats.bandwidthoptimizer.chunk.classify.packet.ChunkPacketDescriptor;
import com.PinkCats.bandwidthoptimizer.chunk.snapshot.ChunkSnapshotFingerprint;
import com.PinkCats.bandwidthoptimizer.chunk.store.global.ChunkGlobalStoreObservation;

import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.TimeUnit;
import java.util.function.LongSupplier;

final class ChunkPeerState {

    private static final int DEFAULT_MAX_CHUNK_STATES = 16_384;
    private static final long DEFAULT_CHUNK_STATE_TTL_NANOS = TimeUnit.MINUTES.toNanos(30L);
    private static final long MAX_EXPIRY_SCAN_INTERVAL_NANOS = TimeUnit.MINUTES.toNanos(1L);
    private final String channelId;
    private final int maxChunkStates;
    private final long chunkStateTtlNanos;
    private final LongSupplier nanoTime;
    private final LinkedHashMap<String, TrackedChunkState> chunkStates = new LinkedHashMap<>(256, 0.75F, true);
    private long epoch;
    private long observedPacketCount;
    private long lastObservedAtMillis;
    private String lastObservedPacketClassName = "";
    private String lastObservedHotspotKind = "";
    private String lastObservedLaneKind = "";
    private String lastObservedChunkText = ChunkPacketCoordinate.unknown().logText();
    private int lastObservedEncodedBytes;
    private long nextExpiryScanNanos;
    private long expiryScanCount;

    ChunkPeerState(String channelId) {
        this(channelId, DEFAULT_MAX_CHUNK_STATES, DEFAULT_CHUNK_STATE_TTL_NANOS, System::nanoTime);
    }

    ChunkPeerState(String channelId, int maxChunkStates, long chunkStateTtlNanos, LongSupplier nanoTime) {
        this.channelId = channelId;
        this.maxChunkStates = Math.max(maxChunkStates, 1);
        this.chunkStateTtlNanos = Math.max(chunkStateTtlNanos, 1L);
        this.nanoTime = Objects.requireNonNull(nanoTime);
    }

    synchronized ChunkPeerObservationSnapshot recordObservation(
            ChunkPacketDescriptor descriptor,
            ChunkSnapshotFingerprint snapshotFingerprint,
            ChunkGlobalStoreObservation storeObservation
    ) {
        this.observedPacketCount++;
        this.lastObservedAtMillis = System.currentTimeMillis();
        this.lastObservedPacketClassName = descriptor.packetClassName();
        this.lastObservedHotspotKind = descriptor.hotspotKind().logName();
        this.lastObservedLaneKind = descriptor.laneKind().logName();
        this.lastObservedChunkText = descriptor.coordinate().logText();
        this.lastObservedEncodedBytes = snapshotFingerprint == null ? 0 : Math.max(snapshotFingerprint.encodedBytes(), 0);
        ChunkPeerChunkStateSnapshot chunkSnapshot = updateChunkState(descriptor, snapshotFingerprint);
        return new ChunkPeerObservationSnapshot(snapshot(), chunkSnapshot, snapshotFingerprint, storeObservation);
    }

    synchronized long bumpEpoch() {
        this.epoch++;
        return this.epoch;
    }

    synchronized ChunkPeerStateSnapshot setEpoch(long epoch) {
        this.epoch = Math.max(epoch, 0L);
        return snapshot();
    }

    synchronized boolean shouldLogObservation() {
        return this.observedPacketCount <= 5L || this.observedPacketCount % 100L == 0L;
    }

    synchronized ChunkPeerStateSnapshot snapshot() {
        return new ChunkPeerStateSnapshot(
                this.channelId,
                this.epoch,
                this.observedPacketCount,
                this.lastObservedAtMillis,
                this.lastObservedPacketClassName,
                this.lastObservedHotspotKind,
                this.lastObservedLaneKind,
                this.lastObservedChunkText,
                this.lastObservedEncodedBytes
        );
    }



    synchronized ChunkPeerChunkStateSnapshot snapshotChunk(ChunkPacketCoordinate coordinate) {
        return snapshotChunk(this.epoch, coordinate);
    }

    synchronized ChunkPeerChunkStateSnapshot snapshotChunk(long scopeId, ChunkPacketCoordinate coordinate) {
        if (coordinate == null || !coordinate.present()) {
            return null;
        }

        ChunkPeerChunkState chunkState = getChunkState(scopedChunkKeyText(scopeId, coordinate));
        return chunkState == null ? null : chunkState.snapshotForQuery();
    }

    synchronized ChunkPeerChunkStateSnapshot latestKnownSnapshotAcrossScopes(ChunkPacketCoordinate coordinate) {
        if (coordinate == null || !coordinate.present()) {
            return null;
        }

        pruneExpiredChunkStates();
        ChunkPeerChunkStateSnapshot latestSnapshot = null;
        String latestKey = null;
        for (Map.Entry<String, TrackedChunkState> entry : this.chunkStates.entrySet()) {
            ChunkPeerChunkState chunkState = entry.getValue().state();
            if (chunkState == null) {
                continue;
            }
            ChunkPeerChunkStateSnapshot candidateSnapshot = chunkState.snapshotForQuery();
            if (candidateSnapshot == null
                    || candidateSnapshot.chunkKey() == null
                    || candidateSnapshot.chunkKey().chunkX() != coordinate.chunkX()
                    || candidateSnapshot.chunkKey().chunkZ() != coordinate.chunkZ()
                    || !candidateSnapshot.knownSnapshotPublished()) {
                continue;
            }
            if (latestSnapshot == null
                    || candidateSnapshot.lastObservedAtMillis() > latestSnapshot.lastObservedAtMillis()) {
                latestSnapshot = candidateSnapshot;
                latestKey = entry.getKey();
            }
        }
        ChunkPeerChunkState selected = latestKey == null ? null : getChunkState(latestKey);
        return selected == null ? latestSnapshot : selected.snapshotForQuery();
    }

    synchronized ChunkPeerChunkStateSnapshot acknowledgeChunk(
            long scopeId,
            ChunkPacketCoordinate coordinate,
            long fullSnapshotVersion,
            String acknowledgedSnapshotHash
    ) {
        ChunkPeerChunkState chunkState = getChunkStateForControl(scopeId, coordinate);
        return chunkState == null ? null : chunkState.recordAcknowledgement(fullSnapshotVersion, acknowledgedSnapshotHash);
    }

    synchronized ChunkPeerChunkStateSnapshot negativeAcknowledgeChunk(long scopeId, ChunkPacketCoordinate coordinate) {
        ChunkPeerChunkState chunkState = getChunkStateForControl(scopeId, coordinate);
        return chunkState == null ? null : chunkState.recordNegativeAcknowledgement();
    }


    synchronized ChunkPeerChunkStateSnapshot invalidateChunk(long scopeId, ChunkPacketCoordinate coordinate) {
        if (coordinate == null || !coordinate.present()) {
            return null;
        }

        TrackedChunkState tracked = this.chunkStates.remove(scopedChunkKeyText(scopeId, coordinate));
        ChunkPeerChunkState chunkState = tracked == null ? null : tracked.state();
        return chunkState == null ? null : chunkState.recordInvalidate();
    }

    synchronized int invalidateChunkAcrossScopes(ChunkPacketCoordinate coordinate) {
        if (coordinate == null || !coordinate.present()) {
            return 0;
        }

        int removedCount = 0;
        pruneExpiredChunkStates();
        Iterator<Map.Entry<String, TrackedChunkState>> iterator = this.chunkStates.entrySet().iterator();
        while (iterator.hasNext()) {
            Map.Entry<String, TrackedChunkState> entry = iterator.next();
            ChunkPeerChunkState chunkState = entry.getValue().state();
            ChunkPeerChunkStateSnapshot snapshot = chunkState == null ? null : chunkState.snapshotForQuery();
            if (snapshot == null
                    || snapshot.chunkKey() == null
                    || snapshot.chunkKey().chunkX() != coordinate.chunkX()
                    || snapshot.chunkKey().chunkZ() != coordinate.chunkZ()) {
                continue;
            }
            iterator.remove();
            chunkState.recordInvalidate();
            removedCount++;
        }
        return removedCount;
    }

    synchronized ChunkPeerChunkStateSnapshot recordPersistentClientManifest(
            long scopeId,
            ChunkPacketCoordinate coordinate,
            long fullSnapshotVersion,
            String payloadHash,
            int encodedBytes
    ) {
        if (coordinate == null || !coordinate.present()) {
            return null;
        }

        long resolvedScopeId = Math.max(scopeId, 0L);
        ChunkPeerChunkKey chunkKey = ChunkPeerChunkKey.fromCoordinate(coordinate);
        ChunkPeerChunkState chunkState = getOrCreateChunkState(
                scopedChunkKeyText(resolvedScopeId, coordinate),
                chunkKey
        );
        return chunkState.recordPersistentClientManifest(
                resolvedScopeId,
                fullSnapshotVersion,
                payloadHash,
                encodedBytes
        );
    }

    synchronized ChunkPeerChunkStateSnapshot markChunkAwaitingFullReplay(long scopeId, ChunkPacketCoordinate coordinate) {
        ChunkPeerChunkState chunkState = getChunkStateForControl(scopeId, coordinate);
        return chunkState == null ? null : chunkState.recordWatchBoundaryRetainCache();
    }


    private ChunkPeerChunkStateSnapshot updateChunkState(
            ChunkPacketDescriptor descriptor,
            ChunkSnapshotFingerprint snapshotFingerprint
    ) {
        if (descriptor == null || !descriptor.hasChunkCoordinate())
            return null;

        ChunkPeerChunkKey chunkKey = ChunkPeerChunkKey.fromCoordinate(descriptor.coordinate());
        ChunkPeerChunkState chunkState = getOrCreateChunkState(
                scopedChunkKeyText(this.epoch, descriptor.coordinate()),
                chunkKey
        );
        return chunkState.recordObservation(descriptor, snapshotFingerprint, this.epoch, this.observedPacketCount);
    }

    private ChunkPeerChunkState getChunkStateForControl(long scopeId, ChunkPacketCoordinate coordinate) {
        if (coordinate == null || !coordinate.present()) {
            return null;
        }
        return getChunkState(scopedChunkKeyText(scopeId, coordinate));
    }

    synchronized int chunkStateCountForTesting() {
        pruneExpiredChunkStates();
        return this.chunkStates.size();
    }

    synchronized long expiryScanCountForTesting() {
        return this.expiryScanCount;
    }

    private ChunkPeerChunkState getChunkState(String key) {
        long now = this.nanoTime.getAsLong();
        pruneExpiredChunkStates(now);
        TrackedChunkState tracked = this.chunkStates.get(key);
        if (tracked == null) {
            return null;
        }
        tracked.touch(now);
        return tracked.state();
    }

    private ChunkPeerChunkState getOrCreateChunkState(String key, ChunkPeerChunkKey chunkKey) {
        long now = this.nanoTime.getAsLong();
        pruneExpiredChunkStates(now);
        TrackedChunkState tracked = this.chunkStates.get(key);
        if (tracked != null) {
            tracked.touch(now);
            return tracked.state();
        }
        trimForNewChunkState();
        ChunkPeerChunkState state = new ChunkPeerChunkState(chunkKey);
        this.chunkStates.put(key, new TrackedChunkState(state, now));
        return state;
    }

    private void pruneExpiredChunkStates() {
        pruneExpiredChunkStates(this.nanoTime.getAsLong());
    }

    private void pruneExpiredChunkStates(long now) {
        if (this.nextExpiryScanNanos != 0L && now - this.nextExpiryScanNanos < 0L) {
            return;
        }
        this.nextExpiryScanNanos = now + Math.min(this.chunkStateTtlNanos, MAX_EXPIRY_SCAN_INTERVAL_NANOS);
        this.expiryScanCount++;
        Iterator<Map.Entry<String, TrackedChunkState>> iterator = this.chunkStates.entrySet().iterator();
        while (iterator.hasNext()) {
            TrackedChunkState tracked = iterator.next().getValue();
            long elapsed = now - tracked.lastAccessNanos();
            if (elapsed < 0L || elapsed >= this.chunkStateTtlNanos) {
                iterator.remove();
            } else {
                break;
            }
        }
    }

    private void trimForNewChunkState() {
        Iterator<String> iterator = this.chunkStates.keySet().iterator();
        while (this.chunkStates.size() >= this.maxChunkStates && iterator.hasNext()) {
            iterator.next();
            iterator.remove();
        }
    }

    private static String scopedChunkKeyText(long scopeId, ChunkPacketCoordinate coordinate) {
        ChunkPeerChunkKey chunkKey = ChunkPeerChunkKey.fromCoordinate(coordinate);
        return Math.max(scopeId, 0L) + ":" + chunkKey.chunkX() + "," + chunkKey.chunkZ();
    }

    private static final class TrackedChunkState {

        private final ChunkPeerChunkState state;
        private long lastAccessNanos;

        private TrackedChunkState(ChunkPeerChunkState state, long lastAccessNanos) {
            this.state = state;
            this.lastAccessNanos = lastAccessNanos;
        }

        private ChunkPeerChunkState state() {
            return this.state;
        }

        private long lastAccessNanos() {
            return this.lastAccessNanos;
        }

        private void touch(long now) {
            this.lastAccessNanos = now;
        }
    }
}
