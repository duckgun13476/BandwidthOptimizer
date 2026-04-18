package com.PinkCats.bandwidthoptimizer.Old.minecraft;

import io.netty.channel.ChannelHandlerContext;
import net.minecraft.network.Connection;
import net.minecraft.network.protocol.Packet;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Invoker;

@Mixin(Connection.class)
public interface ConnectionReplayInvokerMixin {

    @Invoker("channelRead0")
    void bandwidthoptimizer$invokeChannelRead0(ChannelHandlerContext context, Packet<?> packet);
}
