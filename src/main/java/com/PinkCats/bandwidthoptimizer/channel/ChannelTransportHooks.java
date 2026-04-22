package com.PinkCats.bandwidthoptimizer.channel;

import com.PinkCats.bandwidthoptimizer.channel.access.PacketDecoderFlowAccess;
import com.PinkCats.bandwidthoptimizer.channel.algorithm.KineticChannel;
import com.PinkCats.bandwidthoptimizer.channel.algorithm.mes.Incomplete;
import com.PinkCats.bandwidthoptimizer.channel.capture.ChannelCaptureHooks;
import com.PinkCats.bandwidthoptimizer.channel.capture.ChannelCapturedFrame;
import com.PinkCats.bandwidthoptimizer.channel.capture.ChannelTransportTelemetry;
import io.netty.buffer.ByteBuf;
import io.netty.buffer.ByteBufUtil;
import io.netty.buffer.Unpooled;
import io.netty.channel.ChannelHandlerContext;
import net.minecraft.network.Connection;
import net.minecraft.network.ConnectionProtocol;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.PacketListener;
import net.minecraft.network.protocol.Packet;
import net.minecraft.network.protocol.PacketFlow;

import java.io.IOException;
import java.util.List;

public final class ChannelTransportHooks {

    private ChannelTransportHooks() {}

    // Send handle
    public static void tryToWrapOutboundPacket(ChannelHandlerContext context, ByteBuf out, int startIndexInclusive) {

        // Fulfillment
        if (context == null || out == null ||
                !ChannelTransportRuntimeGuard.isTransportAvailable() ||
                shouldUseTransportForCurrentProtocol(context))
            return;


        try {
            int endIndexExclusive = out.writerIndex();
            if (endIndexExclusive <= startIndexInclusive) {
                return;
            }

            byte[] originalPacketBytes = ByteBufUtil.getBytes(out, startIndexInclusive, endIndexExclusive - startIndexInclusive, false);
            ChannelTransportSession transportSession = ChannelTransportStateManager.getOrCreateSession(context.channel());
            ChannelTransportPacketCodec.WrappedTransportFrame wrappedFrame =
                    KineticChannel.processOutboundPacket(transportSession, originalPacketBytes);
            if (wrappedFrame == null) {
                return;
            }

            out.writerIndex(startIndexInclusive);
            out.writeBytes(wrappedFrame.transportFrameBytes());
            ChannelTransportTelemetry.recordOutboundWrap(readProtocolName(context), wrappedFrame);
        } catch (Throwable throwable) {
            ChannelTransportRuntimeGuard.disableTransport("outbound-wrap", throwable);
        }
    }

    // Receive handle
    public static <T extends PacketListener> boolean tryDecodeInboundTransportFrame(
            ChannelHandlerContext context,
            ByteBuf in,
            List<Object> out,
            PacketDecoderFlowAccess packetDecoderFlowAccess
    ) throws Exception {
        if (context == null || in == null || out == null || packetDecoderFlowAccess == null || !in.isReadable()
                || !ChannelTransportRuntimeGuard.isTransportAvailable() || shouldUseTransportForCurrentProtocol(context)) {
            return false;
        }

        byte[] inboundPacketBytes = ByteBufUtil.getBytes(in, in.readerIndex(), in.readableBytes(), false);
        ChannelTransportSession transportSession = ChannelTransportStateManager.getOrCreateSession(context.channel());
        ChannelTransportPacketCodec.UnwrappedTransportFrame unwrappedFrame =
                KineticChannel.tryUnpackInboundPacket(transportSession, inboundPacketBytes);
        if (unwrappedFrame == null) {
            return false;
        }

        ByteBuf restoredBuffer = Unpooled.wrappedBuffer(unwrappedFrame.restoredPacketBytes());
        int outputSizeBeforeDecode = out.size();
        ChannelCapturedFrame pendingInboundFrame = ChannelCaptureHooks.beginInboundPreDecode(context, restoredBuffer);
        try {
            FriendlyByteBuf friendlyBuffer = new FriendlyByteBuf(restoredBuffer);
            int readableBytes = friendlyBuffer.readableBytes();
            int packetId = friendlyBuffer.readVarInt();
            ConnectionProtocol protocol = readConnectionProtocol(context);
            PacketFlow packetFlow = packetDecoderFlowAccess.bandwidthoptimizer$getPacketFlow();
            @SuppressWarnings("unchecked")
            Packet<? super T> packet = (Packet<? super T>) protocol.createPacket(packetFlow, packetId, friendlyBuffer);
            if (packet == null) {
                throw new IOException("Bad packet id " + packetId);
            }

            if (friendlyBuffer.readableBytes() > 0) {
                throw new IOException(
                        "Restored transport packet left "
                                + friendlyBuffer.readableBytes()
                                + " extra bytes after decode. packetClass="
                                + packet.getClass().getName()
                                + ", readableBytesBeforeDecode="
                                + readableBytes
                );
            }

            out.add(packet);
            in.readerIndex(in.writerIndex());
            ChannelCaptureHooks.finishInboundDecode(pendingInboundFrame, out, outputSizeBeforeDecode);
            ChannelTransportTelemetry.recordInboundUnwrap(readProtocolName(context), unwrappedFrame);
            return true;
        } catch (Throwable throwable) {
            ChannelTransportRuntimeGuard.disableTransport("inbound-unwrap", throwable);
            throw throwable;
        } finally {
            restoredBuffer.release();
        }
    }



    private static String readProtocolName(ChannelHandlerContext context) {
        Object protocol = context.channel().attr(Connection.ATTRIBUTE_PROTOCOL).get();
        return protocol == null ? "null" : String.valueOf(protocol);
    }


    private static ConnectionProtocol readConnectionProtocol(ChannelHandlerContext context) {
        ConnectionProtocol protocol = context.channel().attr(Connection.ATTRIBUTE_PROTOCOL).get();
        if (protocol == null)
            throw new IllegalStateException("Missing ConnectionProtocol on inbound transport decode");
        return protocol;
    }


    @Incomplete("Only PLAY packet now")
    private static boolean shouldUseTransportForCurrentProtocol(ChannelHandlerContext context) {
        return !"PLAY".equalsIgnoreCase(readProtocolName(context));
    }


}
