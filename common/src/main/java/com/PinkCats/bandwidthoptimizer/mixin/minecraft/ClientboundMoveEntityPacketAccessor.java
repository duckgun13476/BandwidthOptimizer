package com.PinkCats.bandwidthoptimizer.mixin.minecraft;

import net.minecraft.network.protocol.game.ClientboundMoveEntityPacket;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

@Mixin(ClientboundMoveEntityPacket.class)
public interface ClientboundMoveEntityPacketAccessor {

    @Accessor("entityId")
    int bandwidthoptimizer$getEntityId();
}
