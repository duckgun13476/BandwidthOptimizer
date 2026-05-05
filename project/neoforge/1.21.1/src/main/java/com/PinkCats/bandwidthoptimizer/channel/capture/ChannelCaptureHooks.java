package com.PinkCats.bandwidthoptimizer.channel.capture;

import com.PinkCats.bandwidthoptimizer.channel.mes.ChannelFrameJsonlLogger;
import com.PinkCats.bandwidthoptimizer.report.ChannelTransportCompressionCaptureManager;
import io.netty.buffer.ByteBuf;
import io.netty.channel.ChannelHandlerContext;
import net.minecraft.network.Connection;
import net.minecraft.network.protocol.Packet;

import java.util.List;
import java.util.concurrent.atomic.AtomicReference;


public final class ChannelCaptureHooks {


    private static final AtomicReference<ChannelCapturedFrame> LAST_OUTBOUND_FRAME = new AtomicReference<>();

    private static final AtomicReference<ChannelCapturedFrame> LAST_INBOUND_FRAME = new AtomicReference<>();


    private ChannelCaptureHooks() {}

    public static void captureOutboundEncodedPacket(ChannelHandlerContext context, Packet<?> packet, ByteBuf encodedBuffer, int startIndexInclusive) {
        if (context == null || packet == null || encodedBuffer == null || !shouldCaptureFramePayload()) {
            return;
        }

        int endIndexExclusive = encodedBuffer.writerIndex();
        if (endIndexExclusive <= startIndexInclusive) {
            return;
        }

        byte[] encodedBytes = copyBytes(encodedBuffer, startIndexInclusive, endIndexExclusive);
        ChannelCapturedFrame frame = new ChannelCapturedFrame(
                readChannelId(context),
                "OUTBOUND",
                readProtocolName(context),
                packet.getClass().getName(),
                tryReadLeadingVarInt(encodedBytes),
                encodedBytes.length,
                encodedBytes,
                System.currentTimeMillis()
        );
        LAST_OUTBOUND_FRAME.set(frame);
        ChannelTransportCompressionCaptureManager.recordCapturedFrame(frame);
        ChannelFrameJsonlLogger.appendOutboundFrame(frame);
    }

    public static ChannelCapturedFrame beginInboundPreDecode(ChannelHandlerContext context, ByteBuf encodedBuffer) {
        if (context == null || encodedBuffer == null || !encodedBuffer.isReadable()) {
            return null;
        }

        if (!shouldCaptureFramePayload()) {
            return new ChannelCapturedFrame(
                    readChannelId(context),
                    "INBOUND",
                    readProtocolName(context),
                    "<pre-decode>",
                    -1,
                    encodedBuffer.readableBytes(),
                    new byte[0],
                    System.currentTimeMillis()
            );
        }

        byte[] encodedBytes = copyBytes(encodedBuffer, encodedBuffer.readerIndex(), encodedBuffer.writerIndex());
        return new ChannelCapturedFrame(
                readChannelId(context),
                "INBOUND",
                readProtocolName(context),
                "<pre-decode>",
                tryReadLeadingVarInt(encodedBytes),
                encodedBytes.length,
                encodedBytes,
                System.currentTimeMillis()
        );
    }

    public static void finishInboundDecode(ChannelCapturedFrame pendingFrame, List<Object> out, int outputSizeBeforeDecode) {
        if (pendingFrame == null || !shouldCaptureFramePayload()) {
            return;
        }

        ChannelCapturedFrame completedFrame = pendingFrame;
        if (out != null) {
            for (int index = outputSizeBeforeDecode; index < out.size(); index++) {
                Object decodedObject = out.get(index);
                if (decodedObject instanceof Packet<?> packet) {
                    completedFrame = pendingFrame.withPacketClassName(packet.getClass().getName());
                    break;
                }
            }
        }

        LAST_INBOUND_FRAME.set(completedFrame);
        ChannelTransportCompressionCaptureManager.recordCapturedFrame(completedFrame);
        ChannelFrameJsonlLogger.appendInboundFrame(completedFrame);
    }


    public static ChannelCapturedFrame lastOutboundFrame() {
        return LAST_OUTBOUND_FRAME.get();
    }


    public static ChannelCapturedFrame lastInboundFrame() {
        return LAST_INBOUND_FRAME.get();
    }


    public static void clearCapturedFrames() {
        LAST_OUTBOUND_FRAME.set(null);
        LAST_INBOUND_FRAME.set(null);
    }

    private static byte[] copyBytes(ByteBuf buffer, int startIndexInclusive, int endIndexExclusive) {
        int length = Math.max(endIndexExclusive - startIndexInclusive, 0);
        byte[] bytes = new byte[length];
        if (length > 0) {
            buffer.getBytes(startIndexInclusive, bytes);
        }
        return bytes;
    }

    private static String readProtocolName(ChannelHandlerContext context) {
        return "PLAY";
    }


    private static String readChannelId(ChannelHandlerContext context) {
        return context == null ? "<null-channel>" : context.channel().id().asLongText();
    }

    private static int tryReadLeadingVarInt(byte[] encodedBytes) {
        int value = 0;
        int position = 0;
        for (int index = 0; index < encodedBytes.length && index < 5; index++) {
            int current = encodedBytes[index] & 0xFF;
            value |= (current & 0x7F) << position;
            if ((current & 0x80) == 0) {
                return value;
            }
            position += 7;
        }
        return -1;
    }

    private static boolean shouldCaptureFramePayload() {
        return ChannelCaptureRuntimeConfig.isJsonlCaptureEnabled()
                || ChannelTransportCompressionCaptureManager.isCaptureActive();
    }
}
