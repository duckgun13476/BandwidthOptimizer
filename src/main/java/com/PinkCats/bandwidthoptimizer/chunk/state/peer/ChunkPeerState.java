package com.PinkCats.bandwidthoptimizer.chunk.state.peer;

import com.PinkCats.bandwidthoptimizer.chunk.classify.ChunkPacketCoordinate;
import com.PinkCats.bandwidthoptimizer.chunk.classify.ChunkPacketDescriptor;
import com.PinkCats.bandwidthoptimizer.chunk.snapshot.ChunkSnapshotFingerprint;
import com.PinkCats.bandwidthoptimizer.chunk.store.global.ChunkGlobalStoreObservation;

import java.util.HashMap;
import java.util.Map;

final class ChunkPeerState {

    private final String channelId;
    private final Map<ChunkPeerChunkKey, ChunkPeerChunkState> chunkStates = new HashMap<>();
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
        this.chunkStates.clear();
        return this.epoch;
    }

    synchronized ChunkPeerStateSnapshot setEpoch(long epoch) {
        this.epoch = Math.max(epoch, 0L);
        this.chunkStates.clear();
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

    private ChunkPeerChunkStateSnapshot updateChunkState(
            ChunkPacketDescriptor descriptor,
            ChunkSnapshotFingerprint snapshotFingerprint
    ) {
        if (descriptor == null || !descriptor.hasChunkCoordinate())
            return null;

        ChunkPeerChunkKey chunkKey = ChunkPeerChunkKey.fromCoordinate(descriptor.coordinate());
        ChunkPeerChunkState chunkState = this.chunkStates.computeIfAbsent(chunkKey, ChunkPeerChunkState::new);
        return chunkState.recordObservation(descriptor, snapshotFingerprint, this.epoch, this.observedPacketCount);
    }
}
