package com.PinkCats.bandwidthoptimizer.chunk.PeerState;

import com.PinkCats.bandwidthoptimizer.chunk.classify.packet.ChunkPacketCoordinate;
import com.PinkCats.bandwidthoptimizer.chunk.classify.packet.ChunkPacketDescriptor;
import com.PinkCats.bandwidthoptimizer.chunk.snapshot.ChunkSnapshotFingerprint;
import com.PinkCats.bandwidthoptimizer.chunk.store.global.ChunkGlobalStoreObservation;

import java.util.HashMap;
import java.util.Iterator;
import java.util.Map;

final class ChunkPeerState {

    private final String channelId;
    private final Map<String, ChunkPeerChunkState> chunkStates = new HashMap<>();
    private long epoch;
    private long observedPacketCount;
    private long lastObservedAtMillis;
    private String lastObservedPacketClassName = "";
    private String lastObservedHotspotKind = "";
    private String lastObservedLaneKind = "";
    private String lastObservedChunkText = ChunkPacketCoordinate.unknown().logText();
    private int lastObservedEncodedBytes;

    ChunkPeerState(String channelId) {
        this.channelId = channelId;
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

        ChunkPeerChunkState chunkState = this.chunkStates.get(scopedChunkKeyText(scopeId, coordinate));
        return chunkState == null ? null : chunkState.snapshotForQuery();
    }

    synchronized ChunkPeerChunkStateSnapshot latestKnownSnapshotAcrossScopes(ChunkPacketCoordinate coordinate) {
        if (coordinate == null || !coordinate.present()) {
            return null;
        }

        ChunkPeerChunkStateSnapshot latestSnapshot = null;
        for (ChunkPeerChunkState chunkState : this.chunkStates.values()) {
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
            }
        }
        return latestSnapshot;
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

        ChunkPeerChunkState chunkState = this.chunkStates.remove(scopedChunkKeyText(scopeId, coordinate));
        return chunkState == null ? null : chunkState.recordInvalidate();
    }

    synchronized int invalidateChunkAcrossScopes(ChunkPacketCoordinate coordinate) {
        if (coordinate == null || !coordinate.present()) {
            return 0;
        }

        int removedCount = 0;
        Iterator<Map.Entry<String, ChunkPeerChunkState>> iterator = this.chunkStates.entrySet().iterator();
        while (iterator.hasNext()) {
            Map.Entry<String, ChunkPeerChunkState> entry = iterator.next();
            ChunkPeerChunkState chunkState = entry.getValue();
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
        ChunkPeerChunkState chunkState = this.chunkStates.computeIfAbsent(
                scopedChunkKeyText(resolvedScopeId, coordinate),
                ignored -> new ChunkPeerChunkState(chunkKey)
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
        ChunkPeerChunkState chunkState = this.chunkStates.computeIfAbsent(
                scopedChunkKeyText(this.epoch, descriptor.coordinate()),
                ignored -> new ChunkPeerChunkState(chunkKey)
        );
        return chunkState.recordObservation(descriptor, snapshotFingerprint, this.epoch, this.observedPacketCount);
    }

    private ChunkPeerChunkState getChunkStateForControl(long scopeId, ChunkPacketCoordinate coordinate) {
        if (coordinate == null || !coordinate.present()) {
            return null;
        }
        return this.chunkStates.get(scopedChunkKeyText(scopeId, coordinate));
    }

    private static String scopedChunkKeyText(long scopeId, ChunkPacketCoordinate coordinate) {
        ChunkPeerChunkKey chunkKey = ChunkPeerChunkKey.fromCoordinate(coordinate);
        return Math.max(scopeId, 0L) + ":" + chunkKey.chunkX() + "," + chunkKey.chunkZ();
    }
}
