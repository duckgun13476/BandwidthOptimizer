package com.PinkCats.bandwidthoptimizer.channel.capture;

import com.PinkCats.bandwidthoptimizer.channel.mes.ChannelFrameJsonlLogger;
import com.PinkCats.bandwidthoptimizer.compat.minecraft.ConnectionProtocolNameCompat;
import com.PinkCats.bandwidthoptimizer.report.ChannelTransportCompressionCaptureManager;
import io.netty.buffer.ByteBuf;
import io.netty.channel.Channel;
import io.netty.channel.ChannelHandlerContext;
import io.netty.util.AttributeKey;
import net.minecraft.network.protocol.Packet;

import java.util.List;
import java.util.concurrent.atomic.AtomicReference;


public final class ChannelCaptureHooks {


    private static final AtomicReference<ChannelCapturedFrame> LAST_OUTBOUND_FRAME = new AtomicReference<>();

    private static final AtomicReference<ChannelCapturedFrame> LAST_INBOUND_FRAME = new AtomicReference<>();

    private static final AttributeKey<ChannelCapturedFrame> LAST_INBOUND_DECODE_CANDIDATE_KEY =
            AttributeKey.valueOf("bandwidthoptimizer:last_inbound_decode_candidate");


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

    public static void captureOutboundPacketStream(ChannelHandlerContext context, Packet<?> packet, byte[] encodedBytes) {
        captureOutboundPacketStream(
                context,
                packet == null ? "<unknown>" : packet.getClass().getName(),
                tryReadLeadingVarInt(encodedBytes),
                encodedBytes
        );
    }

    public static void captureOutboundPacketStream(
            ChannelHandlerContext context,
            String packetClassName,
            int packetId,
            byte[] encodedBytes
    ) {
        if (context == null || encodedBytes == null || !ChannelCaptureRuntimeConfig.isJsonlCaptureEnabled()) {
            return;
        }

        ChannelCapturedFrame frame = new ChannelCapturedFrame(
                readChannelId(context),
                "OUTBOUND",
                readProtocolName(context),
                packetClassName == null || packetClassName.isBlank() ? "<unknown>" : packetClassName,
                packetId,
                encodedBytes.length,
                encodedBytes.clone(),
                System.currentTimeMillis()
        );
        ChannelFrameJsonlLogger.appendOutboundPacketStreamFrame(frame);
    }


    public static ChannelCapturedFrame beginInboundPreDecode(ChannelHandlerContext context, ByteBuf encodedBuffer) {
        if (context == null || encodedBuffer == null || !encodedBuffer.isReadable()) {
            return null;
        }

        int readableBytes = encodedBuffer.readableBytes();
        byte[] encodedBytes = shouldCaptureInboundPreDecodePayload()
                ? copyInboundCandidateBytes(encodedBuffer)
                : new byte[0];
        ChannelCapturedFrame frame = new ChannelCapturedFrame(
                readChannelId(context),
                "INBOUND",
                readProtocolName(context),
                "<pre-decode>",
                tryReadLeadingVarInt(encodedBytes),
                readableBytes,
                encodedBytes,
                System.currentTimeMillis()
        );
        context.channel().attr(LAST_INBOUND_DECODE_CANDIDATE_KEY).set(frame);
        return frame;
    }

    public static void finishInboundDecode(ChannelHandlerContext context, ChannelCapturedFrame pendingFrame, List<Object> out, int outputSizeBeforeDecode) {
        clearInboundDecodeCandidate(context == null ? null : context.channel());
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
        ChannelFrameJsonlLogger.appendInboundPacketStreamFrame(completedFrame);
    }

    public static ChannelCapturedFrame lastInboundDecodeCandidate(Channel channel) {
        return channel == null ? null : channel.attr(LAST_INBOUND_DECODE_CANDIDATE_KEY).get();
    }
    
    public static void clearInboundDecodeCandidate(Channel channel) {
        if (channel != null) {
            channel.attr(LAST_INBOUND_DECODE_CANDIDATE_KEY).set(null);
        }
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

    private static byte[] copyInboundCandidateBytes(ByteBuf buffer) {
        int maxPayloadBytes = ChannelDecoderExceptionDumpConfig.maxPayloadBytes();
        if (maxPayloadBytes <= 0) {
            return new byte[0];
        }
        int startIndex = buffer.readerIndex();
        int endIndex = Math.min(buffer.writerIndex(), startIndex + maxPayloadBytes);
        return copyBytes(buffer, startIndex, endIndex);
    }

    private static String readProtocolName(ChannelHandlerContext context) {
        return ConnectionProtocolNameCompat.readProtocolName(context == null ? null : context.channel());
    }


    private static String readChannelId(ChannelHandlerContext context) {
        return context == null ? "<null-channel>" : com.PinkCats.bandwidthoptimizer.channel.ChannelIdentity.longText(context.channel());
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

    private static boolean shouldCaptureInboundPreDecodePayload() {
        return shouldCaptureFramePayload() || ChannelDecoderExceptionDumpConfig.isEnabled();
    }
}
