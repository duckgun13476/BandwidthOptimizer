package com.PinkCats.bandwidthoptimizer.gate.recovery;

public final class AttributeRecoveryPolicyRegressionMain {

    private AttributeRecoveryPolicyRegressionMain() {
    }

    public static void main(String[] args) {
        AttributeRecoveryPolicy.PlayerState state = new AttributeRecoveryPolicy.PlayerState();
        for (int entityId = 0; entityId < AttributeRecoveryPolicy.MAX_PENDING_ENTITY_IDS; entityId++) {
            require(state.remember(entityId), "bounded IDs must be captured");
        }
        require(state.remember(0), "duplicate IDs must not consume the budget");
        require(state.pendingCount() == AttributeRecoveryPolicy.MAX_PENDING_ENTITY_IDS,
                "pending IDs must stay at the hard limit");
        require(!state.remember(AttributeRecoveryPolicy.MAX_PENDING_ENTITY_IDS),
                "overflow must switch to pass-through");
        require(state.isPassThrough(), "overflow mode must remain pass-through until restore");
        require(!state.remember(AttributeRecoveryPolicy.MAX_PENDING_ENTITY_IDS + 1),
                "later updates must pass through after overflow");
        require(state.drain().size() == AttributeRecoveryPolicy.MAX_PENDING_ENTITY_IDS,
                "restore must retain every captured entity ID");
        require(!state.isPassThrough(), "drain must reset overflow state");
        require(state.remember(7), "state must be reusable after restore");
        System.out.println("Attribute recovery policy regression passed.");
    }

    private static void require(boolean condition, String message) {
        if (!condition) {
            throw new AssertionError(message);
        }
    }
}
