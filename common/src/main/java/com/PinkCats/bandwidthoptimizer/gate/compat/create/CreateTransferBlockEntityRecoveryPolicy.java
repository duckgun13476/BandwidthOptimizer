package com.PinkCats.bandwidthoptimizer.gate.compat.create;

import net.minecraft.network.protocol.game.ClientboundBlockEntityDataPacket;
import net.minecraft.resources.ResourceLocation;

/**
 * Transfer-only updates can resume from their latest block-entity state.
 */
public final class CreateTransferBlockEntityRecoveryPolicy extends AbstractCreateBlockEntityRecoveryPolicy {

    @Override
    protected boolean shouldHoldWhileBackground(
            ResourceLocation typeKey,
            ClientboundBlockEntityDataPacket packet
    ) {
        return CreateBlockEntityUpdateGate.isEnabled()
                && CreateGateTypePolicy.isTransferBlockEntity(typeKey);
    }
}
