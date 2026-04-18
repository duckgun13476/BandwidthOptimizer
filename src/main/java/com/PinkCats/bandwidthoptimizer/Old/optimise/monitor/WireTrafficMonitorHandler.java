package com.PinkCats.bandwidthoptimizer.Old.optimise.monitor;

import io.netty.buffer.ByteBuf;
import io.netty.buffer.ByteBufHolder;
import io.netty.channel.ChannelDuplexHandler;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.ChannelPromise;

public final class WireTrafficMonitorHandler extends ChannelDuplexHandler {

    public static final String HANDLER_NAME = "bandwidthoptimizer_wire_monitor";

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
        if (message instanceof ByteBuf byteBuf) {
            PacketTrafficMonitor.recordWireInbound(
                    context.channel(),
                    byteBuf.readableBytes(),
                    PacketTrafficMonitor.copyBytes(byteBuf, byteBuf.readerIndex(), byteBuf.writerIndex())
            );
            return;
        }

        if (message instanceof ByteBufHolder byteBufHolder) {
            ByteBuf content = byteBufHolder.content();
            PacketTrafficMonitor.recordWireInbound(
                    context.channel(),
                    content.readableBytes(),
                    PacketTrafficMonitor.copyBytes(content, content.readerIndex(), content.writerIndex())
            );
        }
    }

    private static void recordOutbound(ChannelHandlerContext context, Object message) {
        if (message instanceof ByteBuf byteBuf) {
            PacketTrafficMonitor.recordWireOutbound(
                    context.channel(),
                    byteBuf.readableBytes(),
                    PacketTrafficMonitor.copyBytes(byteBuf, byteBuf.readerIndex(), byteBuf.writerIndex())
            );
            return;
        }

        if (message instanceof ByteBufHolder byteBufHolder) {
            ByteBuf content = byteBufHolder.content();
            PacketTrafficMonitor.recordWireOutbound(
                    context.channel(),
                    content.readableBytes(),
                    PacketTrafficMonitor.copyBytes(content, content.readerIndex(), content.writerIndex())
            );
        }
    }
}
