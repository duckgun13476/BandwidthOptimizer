package com.PinkCats.bandwidthoptimizer.gate.integration.minecraft;

import java.util.List;

public final class ActiveEntityViewGateRegressionMain {

    private ActiveEntityViewGateRegressionMain() {}

    public static void main(String[] args) {
        verifyCoalescingAndRecovery();
        verifyEntityIdReuse();
        verifyDimensionScopeChange();
        verifyBoundedRetention();
        System.out.println("Active entity view gate regression passed.");
    }

    private static void verifyCoalescingAndRecovery() {
        ActiveEntityViewGate.PlayerState state = new ActiveEntityViewGate.PlayerState();
        require(state.remember(7, ActiveEntityViewGate.POSITION, 10L).captured(),
                "first update must be captured");
        require(state.remember(7, ActiveEntityViewGate.MOTION, 11L).coalesced(),
                "later updates for one entity must coalesce");
        require(state.pendingCount() == 1, "coalescing must retain one entity");

        List<ActiveEntityViewGate.ReadyEntity> hidden = state.drainReady(12L, 1, ignored -> true).ready();
        require(hidden.isEmpty(), "hidden entity must remain deferred before timeout");
        List<ActiveEntityViewGate.ReadyEntity> visible = state.drainReady(13L, 1, ignored -> false).ready();
        require(visible.size() == 1 && !visible.get(0).timedOut(),
                "visible entity must release immediately");
        require(visible.get(0).pending().mask()
                        == (ActiveEntityViewGate.POSITION | ActiveEntityViewGate.MOTION),
                "visible recovery must preserve every coalesced state kind");

        state.remember(8, ActiveEntityViewGate.ATTRIBUTES, 20L);
        List<ActiveEntityViewGate.ReadyEntity> timedOut = state.drainReady(
                20L + ActiveEntityViewGate.MAX_HOLD_TICKS,
                1,
                ignored -> true).ready();
        require(timedOut.size() == 1 && timedOut.get(0).timedOut(),
                "continuously hidden entity must receive a bounded authoritative refresh");
    }

    private static void verifyEntityIdReuse() {
        ActiveEntityViewGate.PlayerState state = new ActiveEntityViewGate.PlayerState();
        state.remember(17, ActiveEntityViewGate.POSITION, 30L);
        state.remove(17);
        state.remember(17, ActiveEntityViewGate.ATTRIBUTES, 30L);

        require(state.drainReady(31L, 1, ignored -> false).ready().isEmpty(),
                "stale queue generation must not release a reused entity ID");
        List<ActiveEntityViewGate.ReadyEntity> current = state.drainReady(31L, 1, ignored -> false).ready();
        require(current.size() == 1
                        && current.get(0).pending().mask() == ActiveEntityViewGate.ATTRIBUTES,
                "the current generation must release independently");
    }

    private static void verifyDimensionScopeChange() {
        ActiveEntityViewGate.PlayerState state = new ActiveEntityViewGate.PlayerState();
        Object firstLevel = new Object();
        Object secondLevel = new Object();
        state.enterScope(firstLevel);
        state.remember(29, ActiveEntityViewGate.POSITION, 40L);
        state.enterScope(secondLevel);

        require(state.pendingCount() == 0 && state.queueSize() == 0,
                "dimension changes must discard entity IDs from the previous level");
    }

    private static void verifyBoundedRetention() {
        ActiveEntityViewGate.PlayerState state = new ActiveEntityViewGate.PlayerState();
        for (int entityId = 0; entityId < ActiveEntityViewGate.MAX_PENDING_ENTITIES_PER_PLAYER; entityId++) {
            require(state.remember(entityId, ActiveEntityViewGate.POSITION, 0L).captured(),
                    "bounded entities must be admitted");
        }
        require(!state.remember(ActiveEntityViewGate.MAX_PENDING_ENTITIES_PER_PLAYER,
                        ActiveEntityViewGate.POSITION,
                        0L).captured(),
                "entity retention must fail open at its hard limit");

        for (int cycle = 0; cycle < ActiveEntityViewGate.MAX_QUEUE_ENTRIES_PER_PLAYER * 3; cycle++) {
            int entityId = cycle % ActiveEntityViewGate.MAX_PENDING_ENTITIES_PER_PLAYER;
            state.remove(entityId);
            require(state.remember(entityId, ActiveEntityViewGate.POSITION, cycle + 1L).captured(),
                    "reused IDs must remain admissible");
        }
        require(state.pendingCount() == ActiveEntityViewGate.MAX_PENDING_ENTITIES_PER_PLAYER,
                "ID churn must not change the entity bound");
        require(state.queueSize() <= ActiveEntityViewGate.MAX_QUEUE_ENTRIES_PER_PLAYER,
                "stale generations must not grow the queue beyond its hard bound");
    }

    private static void require(boolean condition, String message) {
        if (!condition) {
            throw new AssertionError(message);
        }
    }
}
