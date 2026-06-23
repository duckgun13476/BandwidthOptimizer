package com.PinkCats.bandwidthoptimizer.mixin.minecraft;

import com.PinkCats.bandwidthoptimizer.channel.debug.NettySpikeProbe;
import com.PinkCats.bandwidthoptimizer.server.stat.ServerBandwidthStatsRegistry;
import com.PinkCats.bandwidthoptimizer.server.stat.ServerBandwidthWireMonitorHandler;
import io.netty.channel.Channel;
import net.minecraft.network.Connection;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(Connection.class)
public abstract class ConnectionBandwidthStatsMixin {

    @Shadow
    private Channel channel;

    // 连接激活时安装带宽统计和 Netty 心跳探针，后续 event loop 卡超过阈值会输出低频尖峰日志。
    @Inject(method = "channelActive", at = @At("RETURN"))
    private void bandwidthoptimizer$installBandwidthStats(io.netty.channel.ChannelHandlerContext context, CallbackInfo ci) {
        NettySpikeProbe.BO_Diag_nettyMSPT(context);
        Channel activeChannel = this.channel == null && context != null ? context.channel() : this.channel;
        if (activeChannel == null) {
            return;
        }

        ServerBandwidthStatsRegistry.getOrCreate(activeChannel);
        if (activeChannel.pipeline().get(ServerBandwidthWireMonitorHandler.HANDLER_NAME) != null) {
            return;
        }
        activeChannel.pipeline().addFirst(
                ServerBandwidthWireMonitorHandler.HANDLER_NAME,
                new ServerBandwidthWireMonitorHandler()
        );
    }
}
