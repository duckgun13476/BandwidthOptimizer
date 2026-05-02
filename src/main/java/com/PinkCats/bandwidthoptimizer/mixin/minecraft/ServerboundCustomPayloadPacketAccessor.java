package com.PinkCats.bandwidthoptimizer.mixin.minecraft;

import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.protocol.game.ServerboundCustomPayloadPacket;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

@Mixin(ServerboundCustomPayloadPacket.class)
public interface ServerboundCustomPayloadPacketAccessor {

    @Accessor("data")
    FriendlyByteBuf bandwidthoptimizer$data();
}
