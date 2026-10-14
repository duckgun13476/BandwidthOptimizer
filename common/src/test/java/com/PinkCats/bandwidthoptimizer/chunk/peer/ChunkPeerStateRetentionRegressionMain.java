package com.PinkCats.bandwidthoptimizer.chunk.peer;

import com.PinkCats.bandwidthoptimizer.chunk.classify.packet.ChunkPacketCoordinate;
import com.PinkCats.bandwidthoptimizer.chunk.classify.packet.ChunkPacketDescriptor;
import com.PinkCats.bandwidthoptimizer.chunk.classify.ChunkHotspotKind;
import com.PinkCats.bandwidthoptimizer.chunk.classify.ChunkLaneKind;
import com.PinkCats.bandwidthoptimizer.chunk.snapshot.ChunkSnapshotFingerprint;

import java.util.UUID;
import java.util.concurrent.atomic.AtomicLong;

public final class ChunkPeerStateRetentionRegressionMain {

    private ChunkPeerStateRetentionRegressionMain() {}

    public static void main(String[] args) {
        verifyPlayerScopeRetention();
        verifyChunkStateLruAndTtl();
        verifyAcrossScopeSelection();
        verifyAcrossScopeExpiryFallback();
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

    private static void verifyAcrossScopeSelection() {
        AtomicLong now = new AtomicLong(1L);
        ChunkPeerState state = new ChunkPeerState("selection", 3, 100L, now::get);
        ChunkPacketCoordinate coordinate = ChunkPacketCoordinate.ofChunk(1, 2);
        publish(state, 1L, coordinate, 'a', 10L);
        publish(state, 2L, coordinate, 'b', 10L);
        state.recordPersistentClientManifest(3L, coordinate, 1L, hash('c'), 1024);
        require(state.latestKnownSnapshotAcrossScopes(null) == null, "null coordinate selected a state");
        require(state.latestKnownSnapshotAcrossScopes(ChunkPacketCoordinate.unknown()) == null,
                "unknown coordinate selected a state");
        require(state.latestKnownSnapshotAcrossScopes(ChunkPacketCoordinate.ofChunk(2, 1)) == null,
                "different coordinates selected a state");
        require(state.snapshotChunk(2L, coordinate).knownSnapshotHash().equals(hash('b')),
                "exact-scope query changed");
        require(state.latestKnownSnapshotAcrossScopes(coordinate).knownSnapshotHash().equals(hash('a')),
                "timestamp tie did not retain first encountered published state");
        // Selecting scope 1 touches its LRU entry; adding another state evicts scope 3.
        state.recordPersistentClientManifest(4L, coordinate, 1L, hash('d'), 1024);
        require(state.snapshotChunk(3L, coordinate) == null, "winner lookup did not preserve LRU touch");
        publish(state, 2L, coordinate, 'b', 20L);
        require(state.latestKnownSnapshotAcrossScopes(coordinate).knownSnapshotHash().equals(hash('b')),
                "newest published state was not selected");
        state.invalidateChunk(2L, coordinate);
        require(state.latestKnownSnapshotAcrossScopes(coordinate).knownSnapshotHash().equals(hash('a')),
                "invalidated winner remained selected");
        now.set(101L);
        require(state.latestKnownSnapshotAcrossScopes(coordinate) == null, "expired state remained selected");
    }

    private static void verifyAcrossScopeExpiryFallback() {
        AtomicLong now = new AtomicLong(1L);
        java.util.concurrent.atomic.AtomicInteger reads = new java.util.concurrent.atomic.AtomicInteger();
        boolean[] advanceDuringQuery = {false};
        ChunkPeerState state = new ChunkPeerState("fallback", 2, 100L, () -> {
            if (advanceDuringQuery[0] && reads.incrementAndGet() == 2) {
                now.set(101L);
            }
            return now.get();
        });
        ChunkPacketCoordinate coordinate = ChunkPacketCoordinate.ofChunk(3, 4);
        publish(state, 1L, coordinate, 'e', 30L);
        advanceDuringQuery[0] = true;
        require(state.latestKnownSnapshotAcrossScopes(coordinate).knownSnapshotHash().equals(hash('e')),
                "second-prune expiry lost the selected snapshot fallback");
        require(state.snapshotChunk(1L, coordinate) == null, "fallback resurrected an expired map entry");
    }

    private static void publish(ChunkPeerState state, long scope, ChunkPacketCoordinate coordinate,
                                char fingerprint, long timestamp) {
        state.setEpoch(scope);
        state.recordObservation(new ChunkPacketDescriptor("PLAY", "full", ChunkHotspotKind.FULL_CHUNK,
                ChunkLaneKind.FULL, coordinate), new ChunkSnapshotFingerprint("test", hash(fingerprint),
                String.valueOf(fingerprint), 1024), null);
        // Deterministic wall-clock ties without adding a production clock or testing counter.
        try {
            var getter = ChunkPeerState.class.getDeclaredMethod("getChunkStateForControl", long.class,
                    ChunkPacketCoordinate.class);
            getter.setAccessible(true);
            Object chunkState = getter.invoke(state, scope, coordinate);
            var field = ChunkPeerChunkState.class.getDeclaredField("lastObservedAtMillis");
            field.setAccessible(true);
            field.setLong(chunkState, timestamp);
        } catch (ReflectiveOperationException exception) {
            throw new IllegalStateException(exception);
        }
    }

    private static void require(boolean condition, String message) {
        if (!condition) {
            throw new IllegalStateException(message);
        }
    }
}
