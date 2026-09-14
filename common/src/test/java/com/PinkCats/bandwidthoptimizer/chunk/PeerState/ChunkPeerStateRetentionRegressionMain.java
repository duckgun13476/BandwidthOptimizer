package com.PinkCats.bandwidthoptimizer.chunk.PeerState;

import com.PinkCats.bandwidthoptimizer.chunk.classify.packet.ChunkPacketCoordinate;

import java.util.UUID;
import java.util.concurrent.atomic.AtomicLong;

public final class ChunkPeerStateRetentionRegressionMain {

    private ChunkPeerStateRetentionRegressionMain() {}

    public static void main(String[] args) {
        verifyPlayerScopeRetention();
        verifyChunkStateLruAndTtl();
        System.out.println("Chunk peer state retention regression passed");
    }

    private static void verifyPlayerScopeRetention() {
        AtomicLong now = new AtomicLong(1L);
        RetainedStateRegistry<UUID, Object> registry = new RetainedStateRegistry<>(2, 100L, now::get);
        UUID first = UUID.randomUUID();
        UUID second = UUID.randomUUID();
        UUID third = UUID.randomUUID();
        Object firstState = registry.activate(first, Object::new);
        registry.release(first, true);
        require(registry.activate(first, Object::new) == firstState, "short reconnect did not retain player scope");
        registry.release(first, true);
        registry.activate(second, Object::new);
        registry.release(second, true);
        registry.activate(third, Object::new);
        registry.release(third, true);
        require(registry.retainedSize() == 2, "retained player scope pool exceeded its bound");
        require(registry.activate(first, Object::new) != firstState, "LRU player scope was not evicted");
        registry.release(first, true);
        now.addAndGet(100L);
        require(registry.retainedSize() == 0, "expired player scopes were not removed");

        Object active = registry.activate(first, Object::new);
        now.addAndGet(1_000L);
        require(registry.activate(first, Object::new) == active, "active player scope expired");
        require(registry.activeSize() == 1, "active player scope accounting changed");
        registry.release(first, false);
        require(registry.activeSize() == 0 && registry.retainedSize() == 0,
                "non-retained player scope survived release");
    }

    private static void verifyChunkStateLruAndTtl() {
        AtomicLong now = new AtomicLong(1L);
        ChunkPeerState state = new ChunkPeerState("test", 2, 100L, now::get);
        ChunkPacketCoordinate first = ChunkPacketCoordinate.ofChunk(1, 1);
        ChunkPacketCoordinate second = ChunkPacketCoordinate.ofChunk(2, 2);
        ChunkPacketCoordinate third = ChunkPacketCoordinate.ofChunk(3, 3);
        state.recordPersistentClientManifest(1L, first, 1L, hash('a'), 1024);
        state.recordPersistentClientManifest(1L, second, 1L, hash('b'), 1024);
        require(state.snapshotChunk(1L, first) != null, "chunk LRU access lost a live state");
        state.recordPersistentClientManifest(1L, third, 1L, hash('c'), 1024);
        require(state.chunkStateCountForTesting() == 2, "chunk state map exceeded its hard bound");
        require(state.snapshotChunk(1L, second) == null, "least-recent chunk state was not evicted");
        require(state.snapshotChunk(1L, first) != null, "recent chunk state was evicted first");
        require(state.snapshotChunk(1L, third) != null, "new chunk state was not retained");

        long scansBeforeHotLoop = state.expiryScanCountForTesting();
        for (int iteration = 0; iteration < 100_000; iteration++) {
            require(state.snapshotChunk(1L, first) != null, "hot-path query lost retained chunk state");
        }
        require(state.expiryScanCountForTesting() == scansBeforeHotLoop,
                "hot-path queries repeatedly scanned the chunk-state map");

        now.addAndGet(100L);
        require(state.chunkStateCountForTesting() == 0, "expired chunk states were not removed");
        require(state.snapshotChunk(1L, first) == null, "expired chunk state remained queryable");
    }

    private static String hash(char value) {
        return String.valueOf(value).repeat(64);
    }

    private static void require(boolean condition, String message) {
        if (!condition) {
            throw new IllegalStateException(message);
        }
    }
}
