package com.PinkCats.bandwidthoptimizer.Old.minecraft;

import com.PinkCats.bandwidthoptimizer.Old.optimise.monitor.WireTrafficMonitorHandler;
import io.netty.channel.Channel;
import net.minecraft.network.Connection;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(Connection.class)
public abstract class ConnectionWireMonitorMixin {

    @Shadow
    private Channel channel;

    @Inject(method = "channelActive", at = @At("RETURN"))
    private void bandwidthoptimizer$installWireMonitor(io.netty.channel.ChannelHandlerContext context, CallbackInfo ci) {
        if (this.channel == null || this.channel.pipeline().get(WireTrafficMonitorHandler.HANDLER_NAME) != null) {
            return;
        }
        this.channel.pipeline().addFirst(WireTrafficMonitorHandler.HANDLER_NAME, new WireTrafficMonitorHandler());
    }
}
