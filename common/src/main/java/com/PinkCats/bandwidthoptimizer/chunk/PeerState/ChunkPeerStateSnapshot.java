package com.PinkCats.bandwidthoptimizer.chunk.PeerState;

public record ChunkPeerStateSnapshot(
        String channelId,
        long epoch,
        long observedPacketCount,
        long lastObservedAtMillis,
        String lastObservedPacketClassName,
        String lastObservedHotspotKind,
        String lastObservedLaneKind,
        String lastObservedChunkText,
        int lastObservedEncodedBytes
) {
}
