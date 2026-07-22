package com.PinkCats.bandwidthoptimizer.gate.compat.create;

import net.minecraft.network.protocol.game.ClientboundBlockEntityDataPacket;
import net.minecraft.resources.ResourceLocation;

/**
 * Covers the existing Create background-hold set not owned by a specialized policy.
 */
public final class CreateGeneralBlockEntityRecoveryPolicy extends AbstractCreateBlockEntityRecoveryPolicy {

    @Override
    protected boolean shouldHoldWhileBackground(
            ResourceLocation typeKey,
            ClientboundBlockEntityDataPacket packet
    ) {
        return CreateBlockEntityUpdateGate.isEnabled()
                && CreateGateTypePolicy.shouldHoldGeneralWhileBackground(typeKey);
    }
}
