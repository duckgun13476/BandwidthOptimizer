package com.PinkCats.bandwidthoptimizer.chunk.integration.transport;

import com.PinkCats.bandwidthoptimizer.Bandwidthoptimizer;
import com.PinkCats.bandwidthoptimizer.channel.ChannelTransportPacketCodec;
import com.PinkCats.bandwidthoptimizer.channel.ChannelTransportRuntimeGuard;
import com.PinkCats.bandwidthoptimizer.channel.ChannelTransportSession;
import com.PinkCats.bandwidthoptimizer.channel.ChannelTransportStateManager;
import com.PinkCats.bandwidthoptimizer.channel.algorithm.KineticChannel;
import com.PinkCats.bandwidthoptimizer.channel.capture.ChannelTransportTelemetry;
import com.PinkCats.bandwidthoptimizer.chunk.classify.ChunkHotspotKind;
import com.PinkCats.bandwidthoptimizer.chunk.classify.ChunkLaneKind;
import com.PinkCats.bandwidthoptimizer.chunk.classify.ChunkPacketCoordinate;
import com.PinkCats.bandwidthoptimizer.chunk.protocol.ChunkHotspotFrame;
import com.PinkCats.bandwidthoptimizer.chunk.protocol.ChunkHotspotFrameCodec;
import com.PinkCats.bandwidthoptimizer.chunk.protocol.ChunkHotspotFrameOp;
import com.PinkCats.bandwidthoptimizer.chunk.state.peer.ChunkPeerChunkStateSnapshot;
import com.PinkCats.bandwidthoptimizer.chunk.verify.stats.ChunkHotspotStats;
import com.PinkCats.bandwidthoptimizer.chunk.verify.stats.ChunkHotspotVerifyHooks;
import io.netty.buffer.Unpooled;
import io.netty.channel.Channel;
import io.netty.channel.ChannelHandlerContext;
import net.minecraft.network.protocol.game.ClientboundForgetLevelChunkPacket;

public final class ChunkTransportControlFrameSender {


    // Chunk use or not control
    private ChunkTransportControlFrameSender() {}


    public static boolean sendAck(ChannelHandlerContext context, ChunkHotspotFrame sourceFrame, String reason) {
        return sendControlFrame(context, buildControlFrame(ChunkHotspotFrameOp.ACK, sourceFrame, reason));
    }


    public static boolean sendNack(ChannelHandlerContext context, ChunkHotspotFrame sourceFrame, String reason) {
        return sendControlFrame(context, buildControlFrame(ChunkHotspotFrameOp.NACK, sourceFrame, reason));
    }

    public static boolean sendInvalidate(ChannelHandlerContext context, ChunkHotspotFrame sourceFrame, String reason) {
        return sendControlFrame(context, buildControlFrame(ChunkHotspotFrameOp.INVALIDATE, sourceFrame, reason));
    }

    public static boolean sendLifecycleInvalidate(
            Channel channel,
            ChunkPacketCoordinate coordinate,
            ChunkPeerChunkStateSnapshot chunkSnapshot,
            String reason
    ) {
        if (channel == null || coordinate == null || !coordinate.present() || chunkSnapshot == null) {
            return false;
        }

        return sendControlFrame(channel, new ChunkHotspotFrame(
                ChunkHotspotFrameCodec.PROTOCOL_VERSION,
                ChunkHotspotFrameOp.INVALIDATE,
                chunkSnapshot.epoch(),
                chunkSnapshot.lastObservedChannelPacketCount(),
                "PLAY",
                ClientboundForgetLevelChunkPacket.class.getName(),
                ChunkHotspotKind.FULL_CHUNK,
                ChunkLaneKind.FULL,
                coordinate,
                0,
                chunkSnapshot.fullSnapshotVersion(),
                0L,
                chunkSnapshot.knownSnapshotHash(),
                chunkSnapshot.knownSnapshotHash(),
                0L,
                reason == null ? "" : reason
        ));
    }

    private static ChunkHotspotFrame buildControlFrame(
            ChunkHotspotFrameOp operation,
            ChunkHotspotFrame sourceFrame,
            String reason
    ) {
        if (sourceFrame == null) {
            throw new IllegalArgumentException("sourceFrame must not be null");
        }

        return new ChunkHotspotFrame(
                ChunkHotspotFrameCodec.PROTOCOL_VERSION,
                operation,
                sourceFrame.epoch(),
                sourceFrame.observedPacketCount(),
                sourceFrame.protocolName(),
                sourceFrame.packetClassName(),
                sourceFrame.hotspotKind(),
                sourceFrame.laneKind(),
                sourceFrame.coordinate(),
                0,
                sourceFrame.fullSnapshotVersion(),
                sourceFrame.laneVersion(),
                sourceFrame.baseSnapshotHash(),
                sourceFrame.payloadHash(),
                0L,
                reason == null ? "" : reason
        );
    }

    private static boolean sendControlFrame(ChannelHandlerContext context, ChunkHotspotFrame frame) {
        return sendControlFrame(context == null ? null : context.channel(), frame);
    }


    private static boolean sendControlFrame(Channel channel, ChunkHotspotFrame frame) {
        if (channel == null
                || frame == null
                || !ChunkTransportRuntimeConfig.isEnabled()
                || !ChannelTransportRuntimeGuard.isTransportAvailable()
                || !"PLAY".equalsIgnoreCase(readProtocolName(channel))) {
            return false;
        }

        ChannelHandlerContext encoderContext = channel.pipeline().context("encoder");
        if (encoderContext == null) {
            return false;
        }

        try {
            byte[] encodedEnvelopeBytes = ChunkTransportEnvelopeCodec.encodeEnvelope(
                    new ChunkTransportEnvelope(frame, new byte[0])
            );
            ChannelTransportSession transportSession = ChannelTransportStateManager.getOrCreateSession(channel);
            ChannelTransportPacketCodec.WrappedTransportFrame wrappedFrame =
                    KineticChannel.processOutboundPacket(transportSession, encodedEnvelopeBytes);
            if (wrappedFrame == null) {
                return false;
            }

            encoderContext.writeAndFlush(Unpooled.wrappedBuffer(wrappedFrame.transportFrameBytes())).addListener(future -> {
                if (!future.isSuccess()) {
                    Throwable failure = future.cause() == null
                            ? new IllegalStateException("Unknown chunk control frame send failure")
                            : future.cause();
                    ChannelTransportRuntimeGuard.disableTransport("chunk-control-frame-send", failure);
                }
            });
            ChannelTransportTelemetry.recordOutboundWrap(readProtocolName(channel), wrappedFrame);
            ChunkHotspotStats.recordOutboundFrame(frame, 0, encodedEnvelopeBytes.length);
            ChunkHotspotVerifyHooks.flushCurrentReport();
            Bandwidthoptimizer.LOGGER.info(
                    "[ChunkTransport][Control][Send] channel={}, op={}, epoch={}, chunk={}, fullVersion={}, payloadHash={}, reason={}",
                    channel.id().asLongText(),
                    frame.operation().logName(),
                    frame.epoch(),
                    frame.coordinate().logText(),
                    frame.fullSnapshotVersion(),
                    shortenHash(frame.payloadHash()),
                    frame.reason()
            );
            return true;
        } catch (Throwable throwable) {
            ChannelTransportRuntimeGuard.disableTransport("chunk-control-frame-send", throwable);
            return false;
        }
    }

    private static String readProtocolName(Channel channel) {
        Object protocol = channel.attr(net.minecraft.network.Connection.ATTRIBUTE_PROTOCOL).get();
        return protocol == null ? "null" : String.valueOf(protocol);
    }

    private static String shortenHash(String hashHex) {
        if (hashHex == null || hashHex.isBlank()) {
            return "<none>";
        }
        return hashHex.length() <= 12 ? hashHex : hashHex.substring(0, 12);
    }
}
