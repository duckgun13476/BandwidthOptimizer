package com.PinkCats.bandwidthoptimizer.chunk.PeerState;

import com.PinkCats.bandwidthoptimizer.chunk.snapshot.ChunkSnapshotFingerprint;
import com.PinkCats.bandwidthoptimizer.chunk.store.global.ChunkGlobalStoreObservation;

//shot cut
public record ChunkPeerObservationSnapshot(
        ChunkPeerStateSnapshot channelState,
        ChunkPeerChunkStateSnapshot chunkState,
        ChunkSnapshotFingerprint snapshotFingerprint,
        ChunkGlobalStoreObservation storeObservation
) {
}
