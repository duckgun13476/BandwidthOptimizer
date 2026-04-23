package com.PinkCats.bandwidthoptimizer.channel;

import com.PinkCats.bandwidthoptimizer.channel.access.PacketDecoderFlowAccess;
import com.PinkCats.bandwidthoptimizer.channel.algorithm.KineticChannel;
import com.PinkCats.bandwidthoptimizer.channel.algorithm.batch.ChannelTransportBatchManager;
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
            if (ChannelTransportBatchManager.shouldBatchOutboundPacket(context)) {
                out.writerIndex(startIndexInclusive);
                ChannelTransportBatchManager.enqueueOutboundPacket(context, originalPacketBytes);
                return;
            }

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

        try {
            if (ChannelTransportBatchManager.shouldReplayInboundAsBatch(unwrappedFrame)) {
                ChannelTransportBatchManager.replayInboundBatch(
                        context,
                        decodeInboundReplayEntries(context, unwrappedFrame, packetDecoderFlowAccess)
                );
            } else {
                decodeInboundPacketsIntoOutput(context, unwrappedFrame, out, packetDecoderFlowAccess);
            }
            in.readerIndex(in.writerIndex());
            ChannelTransportTelemetry.recordInboundUnwrap(readProtocolName(context), unwrappedFrame);
            return true;
        } catch (Throwable throwable) {
            ChannelTransportRuntimeGuard.disableTransport("inbound-unwrap", throwable);
            throw throwable;
        }
    }

    private static <T extends PacketListener> void decodeInboundPacketsIntoOutput(
            ChannelHandlerContext context,
            ChannelTransportPacketCodec.UnwrappedTransportFrame unwrappedFrame,
            List<Object> out,
            PacketDecoderFlowAccess packetDecoderFlowAccess
    ) throws Exception {
        for (byte[] restoredPacketBytes : unwrappedFrame.restoredPacketBytesList()) {
            ChannelCapturedFrame pendingInboundFrame = beginInboundCapture(context, restoredPacketBytes);
            int outputSizeBeforeDecode = out.size();
            Packet<? super T> restoredPacket = decodeRestoredPacket(context, restoredPacketBytes, packetDecoderFlowAccess);
            out.add(restoredPacket);
            ChannelCaptureHooks.finishInboundDecode(pendingInboundFrame, out, outputSizeBeforeDecode);
        }
    }

    private static <T extends PacketListener> List<ChannelTransportBatchManager.InboundReplayEntry> decodeInboundReplayEntries(
            ChannelHandlerContext context,
            ChannelTransportPacketCodec.UnwrappedTransportFrame unwrappedFrame,
            PacketDecoderFlowAccess packetDecoderFlowAccess
    ) throws Exception {
        List<ChannelTransportBatchManager.InboundReplayEntry> replayEntries = new java.util.ArrayList<>(unwrappedFrame.restoredPacketCount());
        for (byte[] restoredPacketBytes : unwrappedFrame.restoredPacketBytesList()) {
            replayEntries.add(new ChannelTransportBatchManager.InboundReplayEntry(
                    restoredPacketBytes,
                    decodeRestoredPacket(context, restoredPacketBytes, packetDecoderFlowAccess)
            ));
        }
        return List.copyOf(replayEntries);
    }

    private static ChannelCapturedFrame beginInboundCapture(ChannelHandlerContext context, byte[] restoredPacketBytes) {
        ByteBuf restoredBuffer = Unpooled.wrappedBuffer(restoredPacketBytes);
        try {
            return ChannelCaptureHooks.beginInboundPreDecode(context, restoredBuffer);
        } finally {
            restoredBuffer.release();
        }
    }

    private static <T extends PacketListener> Packet<? super T> decodeRestoredPacket(
            ChannelHandlerContext context,
            byte[] restoredPacketBytes,
            PacketDecoderFlowAccess packetDecoderFlowAccess
    ) throws IOException {
        ByteBuf restoredBuffer = Unpooled.wrappedBuffer(restoredPacketBytes);
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

            return packet;
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
