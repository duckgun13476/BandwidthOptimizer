package com.PinkCats.bandwidthoptimizer.gate.compat.create;

import net.minecraft.network.protocol.game.ClientboundBlockEntityDataPacket;
import net.minecraft.resources.ResourceLocation;

/**
 * Worker animation updates are restored from their latest server state.
 */
public final class CreateWorkerBlockEntityRecoveryPolicy extends AbstractCreateBlockEntityRecoveryPolicy {

    @Override
    protected boolean shouldHoldWhileBackground(
            ResourceLocation typeKey,
            ClientboundBlockEntityDataPacket packet
    ) {
        return CreateBlockEntityUpdateGate.isEnabled()
                && CreateGateTypePolicy.isWorkerBlockEntity(typeKey);
    }
}
