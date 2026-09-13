package com.PinkCats.bandwidthoptimizer.chunk.persistent;

import java.util.List;

public final class ChunkPersistentManifestGateStateRegressionMain {

    private ChunkPersistentManifestGateStateRegressionMain() {}

    public static void main(String[] args) {
        verifiesRearmPreservesQueueAndRejectsStaleRelease();
        verifiesBudgetReleasePreservesFifo();
        verifiesCloseDropsRetainedState();
        System.out.println("Chunk persistent manifest gate state regression passed.");
    }

    private static void verifiesRearmPreservesQueueAndRejectsStaleRelease() {
        ChunkPersistentManifestGate.ManifestGateState<String> state =
                new ChunkPersistentManifestGate.ManifestGateState<>();
        long first = state.arm(100L);
        require(state.queue("first", 10, 4, 100).consumed(), "first packet was not queued");

        long second = state.arm(200L);
        require(second != first, "re-arm did not advance generation");
        require(state.queuedCount() == 1, "re-arm discarded queued packets");
        require(!state.expired(101L, first), "stale timeout matched the new generation");
        require(!state.release(first).released(), "stale completion released the new generation");

        require(state.queue("second", 20, 4, 100).consumed(), "second packet was not queued");
        ChunkPersistentManifestGate.ReleaseResult<String> release = state.release(second);
        require(release.released(), "current completion did not release the gate");
        require(release.items().equals(List.of("first", "second")), "completion changed FIFO order");
    }

    private static void verifiesBudgetReleasePreservesFifo() {
        ChunkPersistentManifestGate.ManifestGateState<String> state =
                new ChunkPersistentManifestGate.ManifestGateState<>();
        state.arm(100L);
        state.queue("one", 40, 8, 100);
        state.queue("two", 40, 8, 100);

        ChunkPersistentManifestGate.QueueResult<String> overflow = state.queue("three", 30, 8, 100);
        require(overflow.consumed(), "budget overflow did not consume the current packet");
        require(overflow.releasedItems().equals(List.of("one", "two", "three")),
                "budget release allowed the current packet to overtake queued packets");
        require(!state.pending() && state.queuedCount() == 0 && state.queuedBytes() == 0L,
                "budget release retained stale gate state");
    }

    private static void verifiesCloseDropsRetainedState() {
        ChunkPersistentManifestGate.ManifestGateState<String> state =
                new ChunkPersistentManifestGate.ManifestGateState<>();
        state.arm(100L);
        state.queue("retained", 25, 8, 100);
        state.close();
        require(!state.pending() && state.queuedCount() == 0 && state.queuedBytes() == 0L,
                "channel close retained queued packets");
    }

    private static void require(boolean condition, String message) {
        if (!condition) {
            throw new IllegalStateException(message);
        }
    }
}
