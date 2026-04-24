package com.PinkCats.bandwidthoptimizer.chunk.state.peer;

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
