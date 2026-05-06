package com.PinkCats.bandwidthoptimizer.mixin.minecraft;

import net.minecraft.network.Connection;
import net.minecraft.server.network.ServerCommonPacketListenerImpl;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

@Mixin(ServerCommonPacketListenerImpl.class)
public interface ServerGamePacketListenerImplAccessor {

    @Accessor("connection")
    Connection bandwidthoptimizer$getConnection();
}
