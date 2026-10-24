package com.PinkCats.bandwidthoptimizer.networkprobe;

import io.netty.buffer.ByteBuf;
import io.netty.buffer.ByteBufHolder;
import io.netty.channel.*;

/** Read-only Netty byte boundary; write completion is NOT peer delivery or a TCP ACK. */
public final class ProbeConnectionMonitor extends ChannelDuplexHandler {
    public static final String NAME="bandwidthoptimizer_network_probe_monitor";
    @Override public void channelRead(ChannelHandlerContext ctx,Object msg) throws Exception {
        NetworkProbeSession session=NetworkProbeService.current(ctx.channel());
        if (session!=null) session.nettyInbound+=size(msg);
        super.channelRead(ctx,msg);
    }
    @Override public void write(ChannelHandlerContext ctx,Object msg,ChannelPromise promise) throws Exception {
        NetworkProbeSession session=NetworkProbeService.current(ctx.channel());
        if (session!=null) session.nettyOutbound+=size(msg);
        super.write(ctx,msg,promise);
    }
    @Override public void channelInactive(ChannelHandlerContext ctx) throws Exception {
        NetworkProbeService.closed(ctx.channel(),"connection_closed_cause_unknown");
        super.channelInactive(ctx);
    }
    @Override public void exceptionCaught(ChannelHandlerContext ctx,Throwable cause) throws Exception {
        NetworkProbeSession session=NetworkProbeService.current(ctx.channel());
        if (session!=null) session.lastError=cause.getClass().getName();
        super.exceptionCaught(ctx,cause); // Preserve the original exception and connection behavior.
    }
    private static int size(Object message) {
        if (message instanceof ByteBuf b) return b.readableBytes();
        if (message instanceof ByteBufHolder b) return b.content().readableBytes();
        return 0;
    }
}
