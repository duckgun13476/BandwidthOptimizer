package com.PinkCats.bandwidthoptimizer.server.stat;

import io.netty.buffer.ByteBuf;
import io.netty.buffer.ByteBufHolder;
import io.netty.channel.ChannelDuplexHandler;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.ChannelPromise;

public final class ServerBandwidthWireMonitorHandler extends ChannelDuplexHandler {

    public static final String HANDLER_NAME = "bandwidthoptimizer_server_bandwidth_wire_monitor";

    @Override
    public void channelRead(ChannelHandlerContext context, Object message) throws Exception {
        recordInbound(context, message);
        super.channelRead(context, message);
    }

    @Override
    public void write(ChannelHandlerContext context, Object message, ChannelPromise promise) throws Exception {
        recordOutbound(context, message);
        super.write(context, message, promise);
    }


    private static void recordInbound(ChannelHandlerContext context, Object message) {
        ChannelBandwidthStats stats = ServerBandwidthStatsRegistry.getOrCreate(context);
        if (stats == null) {
            return;
        }
        int byteLength = readableBytes(message);
        if (byteLength > 0) {
            stats.recordInboundWire(byteLength);
        }
    }


    private static void recordOutbound(ChannelHandlerContext context, Object message) {
        ChannelBandwidthStats stats = ServerBandwidthStatsRegistry.getOrCreate(context);
        if (stats == null) {
            return;
        }
        int byteLength = readableBytes(message);
        if (byteLength > 0) {
            stats.recordOutboundWire(byteLength);
        }
    }


    private static int readableBytes(Object message) {
        if (message instanceof ByteBuf byteBuf) {
            return byteBuf.readableBytes();
        }
        if (message instanceof ByteBufHolder byteBufHolder) {
            return byteBufHolder.content().readableBytes();
        }
        return 0;
    }
}
