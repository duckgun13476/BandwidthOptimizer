package com.PinkCats.bandwidthoptimizer.mixin.minecraft;

import net.minecraft.client.multiplayer.ClientHandshakePacketListenerImpl;
import net.minecraft.network.Connection;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

@Mixin(ClientHandshakePacketListenerImpl.class)
public interface RequiredPeerLoginAccessor {
    @Accessor("connection")
    Connection bandwidthoptimizer$getConnection();
}
