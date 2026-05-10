package com.PinkCats.bandwidthoptimizer.chunk.integration.transport;

import com.PinkCats.bandwidthoptimizer.Bandwidthoptimizer;
import com.PinkCats.bandwidthoptimizer.channel.ChannelTransportHooks;
import com.PinkCats.bandwidthoptimizer.channel.ChannelTransportPacketCodec;
import com.PinkCats.bandwidthoptimizer.channel.ChannelTransportRuntimeGuard;
import com.PinkCats.bandwidthoptimizer.channel.ChannelTransportSession;
import com.PinkCats.bandwidthoptimizer.channel.ChannelTransportStateManager;
import com.PinkCats.bandwidthoptimizer.channel.access.PacketEncoderFlowAccess;
import com.PinkCats.bandwidthoptimizer.channel.algorithm.KineticChannel;
import com.PinkCats.bandwidthoptimizer.channel.capture.ChannelTransportTelemetry;
import com.PinkCats.bandwidthoptimizer.chunk.classify.ChunkHotspotKind;
import com.PinkCats.bandwidthoptimizer.chunk.classify.ChunkLaneKind;
import com.PinkCats.bandwidthoptimizer.chunk.classify.packet.ChunkPacketCoordinate;
import com.PinkCats.bandwidthoptimizer.chunk.integration.transport.Envelope.ChunkTransportEnvelope;
import com.PinkCats.bandwidthoptimizer.chunk.integration.transport.Envelope.ChunkTransportEnvelopeCodec;
import com.PinkCats.bandwidthoptimizer.chunk.persistent.ChunkPersistentServerScope;
import com.PinkCats.bandwidthoptimizer.chunk.protocol.hotspot.ChunkHotspotFrame;
import com.PinkCats.bandwidthoptimizer.chunk.protocol.hotspot.ChunkHotspotFrameCodec;
import com.PinkCats.bandwidthoptimizer.chunk.protocol.hotspot.ChunkHotspotFrameOp;
import com.PinkCats.bandwidthoptimizer.chunk.PeerState.ChunkPeerChunkStateSnapshot;
import com.PinkCats.bandwidthoptimizer.chunk.verify.ChunkHotspotStats;
import com.PinkCats.bandwidthoptimizer.chunk.verify.ChunkHotspotVerifyHooks;
import com.PinkCats.bandwidthoptimizer.debug.DebugRuntimeConfig;
import io.netty.buffer.ByteBuf;
import io.netty.buffer.Unpooled;
import io.netty.channel.Channel;
import io.netty.channel.ChannelFuture;
import io.netty.channel.ChannelHandlerContext;
import net.minecraft.network.protocol.game.ClientboundForgetLevelChunkPacket;
import net.minecraft.network.protocol.PacketFlow;

import java.io.IOException;
import java.nio.channels.ClosedChannelException;
import java.util.Locale;

public final class ChunkTransportControlFrameSender {

    private static final String BOUNDARY_BARRIER_PACKET_CLASS_NAME =
            "bandwidthoptimizer.chunk.transport.BoundaryBarrier";

    // Chunk use or not control
    private ChunkTransportControlFrameSender() {}


    public static boolean sendAck(ChannelHandlerContext context, ChunkHotspotFrame sourceFrame, String reason) {
        return sendControlFrame(context, buildControlFrame(ChunkHotspotFrameOp.ACK, sourceFrame, reason));
    }


    public static boolean sendNack(ChannelHandlerContext context, ChunkHotspotFrame sourceFrame, String reason) {
        if (isRuntimeFailureReason(reason) && context != null && sourceFrame != null) {
            ChunkTransportBoundaryController.recordRuntimeFailure(context.channel(), sourceFrame.coordinate(), reason);
        }
        return sendControlFrame(context, buildControlFrame(ChunkHotspotFrameOp.NACK, sourceFrame, reason));
    }

    public static boolean sendInvalidate(ChannelHandlerContext context, ChunkHotspotFrame sourceFrame, String reason) {
        if (isRuntimeFailureReason(reason) && context != null && sourceFrame != null) {
            ChunkTransportBoundaryController.recordRuntimeFailure(context.channel(), sourceFrame.coordinate(), reason);
        }
        return sendControlFrame(context, buildControlFrame(ChunkHotspotFrameOp.INVALIDATE, sourceFrame, reason));
    }

    public static boolean sendBoundaryBarrier(Channel channel, long barrierId, String reason) {
        if (barrierId <= 0L) {
            return false;
        }

        String safeReason = reason == null || reason.isBlank() ? "post_boundary_barrier" : reason;
        String barrierToken = Long.toUnsignedString(barrierId, 16);
        return sendControlFrame(channel, new ChunkHotspotFrame(
                ChunkHotspotFrameCodec.PROTOCOL_VERSION,
                ChunkHotspotFrameOp.BARRIER,
                0L,
                barrierId,
                "PLAY",
                BOUNDARY_BARRIER_PACKET_CLASS_NAME,
                ChunkHotspotKind.FULL_CHUNK,
                ChunkLaneKind.FULL,
                ChunkPacketCoordinate.unknown(),
                0,
                0L,
                0L,
                "",
                barrierToken,
                0L,
                safeReason
        ));
    }


    public static boolean sendBarrierAck(ChannelHandlerContext context, ChunkHotspotFrame sourceFrame, String reason) {
        return sendControlFrame(context, buildControlFrame(ChunkHotspotFrameOp.BARRIER_ACK, sourceFrame, reason));
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

    public static boolean sendClientCacheBudgetInvalidate(
            Channel channel,
            long scopeId,
            ChunkPacketCoordinate coordinate,
            long fullSnapshotVersion,
            String fullSnapshotHash,
            String reason
    ) {
        if (channel == null || coordinate == null || !coordinate.present() || fullSnapshotVersion <= 0L) {
            return false;
        }

        String safeFullSnapshotHash = fullSnapshotHash == null ? "" : fullSnapshotHash;
        return sendControlFrame(channel, new ChunkHotspotFrame(
                ChunkHotspotFrameCodec.PROTOCOL_VERSION,
                ChunkHotspotFrameOp.INVALIDATE,
                Math.max(scopeId, 0L),
                0L,
                "PLAY",
                ClientboundForgetLevelChunkPacket.class.getName(),
                ChunkHotspotKind.FULL_CHUNK,
                ChunkLaneKind.FULL,
                coordinate,
                0,
                Math.max(fullSnapshotVersion, 0L),
                0L,
                safeFullSnapshotHash,
                safeFullSnapshotHash,
                0L,
                reason == null ? "" : reason
        ));
    }

    // server chunk invoke reuse
    public static boolean sendPersistentClientCacheManifest(Channel channel, ChunkHotspotFrame manifestFrame) {
        if (manifestFrame == null || manifestFrame.operation() != ChunkHotspotFrameOp.CLIENT_CACHE_MANIFEST) {
            return false;
        }
        return sendControlFrame(channel, manifestFrame);
    }

    public static boolean sendServerCacheScope(Channel channel, String reason) {
        String scopeHash = ChunkPersistentServerScope.currentScopeHash();
        if (!ChunkPersistentServerScope.isSafeScopeHash(scopeHash)) {
            return false;
        }
        return sendControlFrame(channel, new ChunkHotspotFrame(
                ChunkHotspotFrameCodec.PROTOCOL_VERSION,
                ChunkHotspotFrameOp.SERVER_CACHE_SCOPE,
                0L,
                0L,
                "PLAY",
                "bandwidthoptimizer.chunk.transport.ServerCacheScope",
                ChunkHotspotKind.FULL_CHUNK,
                ChunkLaneKind.FULL,
                ChunkPacketCoordinate.unknown(),
                0,
                0L,
                0L,
                scopeHash,
                scopeHash,
                0L,
                reason == null || reason.isBlank() ? "server_cache_scope" : reason
        ));
    }

    public static boolean sendReplayFullFrame(
            Channel channel,
            ChunkHotspotFrame sourceFrame,
            byte[] originalPacketBytes,
            String reason
    ) {
        if (sourceFrame == null || originalPacketBytes == null || originalPacketBytes.length == 0) {
            return false;
        }

        ChunkHotspotFrame replayFullFrame = new ChunkHotspotFrame(
                ChunkHotspotFrameCodec.PROTOCOL_VERSION,
                ChunkHotspotFrameOp.PUBLISH_FULL,
                sourceFrame.epoch(),
                sourceFrame.observedPacketCount(),
                sourceFrame.protocolName(),
                sourceFrame.packetClassName(),
                sourceFrame.hotspotKind(),
                sourceFrame.laneKind(),
                sourceFrame.coordinate(),
                originalPacketBytes.length,
                sourceFrame.fullSnapshotVersion(),
                sourceFrame.laneVersion(),
                sourceFrame.payloadHash(),
                sourceFrame.payloadHash(),
                0L,
                reason == null ? "" : reason
        );
        return sendEnvelopeFrame(channel, replayFullFrame, originalPacketBytes, originalPacketBytes.length);
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
        return sendEnvelopeFrame(channel, frame, new byte[0], 0);
    }

    private static boolean sendEnvelopeFrame(
            Channel channel,
            ChunkHotspotFrame frame,
            byte[] payloadBytes,
            int logicalPacketBytes
    ) {
        if (channel == null
                || frame == null
                || isChannelClosing(channel)
                || !ChunkTransportRuntimeConfig.isEnabled()
                || !ChannelTransportRuntimeGuard.isTransportAvailable()
                || !"PLAY".equalsIgnoreCase(readProtocolName(channel))) {
            return false;
        }

        ChannelHandlerContext encoderContext = channel.pipeline().context("encoder");
        if (encoderContext == null) {
            return false;
        }

        ByteBuf carrierPacketBytes = null;
        boolean handedToPipeline = false;
        try {
            byte[] encodedEnvelopeBytes = ChunkTransportEnvelopeCodec.encodeEnvelope(
                    new ChunkTransportEnvelope(frame, payloadBytes)
            );
            ChannelTransportSession transportSession = ChannelTransportStateManager.getOrCreateSession(channel);
            ChannelTransportPacketCodec.WrappedTransportFrame wrappedFrame =
                    KineticChannel.processOutboundPacket(transportSession, encodedEnvelopeBytes);
            if (wrappedFrame == null) {
                return false;
            }

            PacketFlow outboundPacketFlow = resolveOutboundPacketFlow(encoderContext);
            if (outboundPacketFlow == null) {
                return false;
            }

            carrierPacketBytes = Unpooled.buffer();
            if (!ChannelTransportHooks.writeTransportCarrierPacket(
                    encoderContext,
                    outboundPacketFlow,
                    carrierPacketBytes,
                    wrappedFrame.transportFrameBytes()
            )) {
                carrierPacketBytes.release();
                return false;
            }

            ChannelFuture writeFuture = encoderContext.writeAndFlush(carrierPacketBytes);
            handedToPipeline = true;
            writeFuture.addListener(future -> {
                if (!future.isSuccess()) {
                    Throwable failure = future.cause() == null
                            ? new IllegalStateException("Unknown chunk control frame send failure")
                            : future.cause();
                    if (shouldIgnoreControlFrameSendFailure(channel, failure)) {
                        return;
                    }
                    ChannelTransportRuntimeGuard.disableTransport("chunk-control-frame-send", failure);
                }
            });
            ChannelTransportTelemetry.recordOutboundWrap(readProtocolName(channel), wrappedFrame);
            ChunkHotspotStats.recordOutboundFrame(frame, Math.max(logicalPacketBytes, 0), encodedEnvelopeBytes.length);
            ChunkHotspotVerifyHooks.flushCurrentReport();
            if (DebugRuntimeConfig.isDiagnoseEnabled()) {
                Bandwidthoptimizer.LOGGER.info(
                        "[ChunkTransport][Control][Send] channel={}, op={}, epoch={}, observedPackets={}, chunk={}, fullVersion={}, payloadHash={}, payloadBytes={}, reason={}",
                        channel.id().asLongText(),
                        frame.operation().logName(),
                        frame.epoch(),
                        frame.observedPacketCount(),
                        frame.coordinate().logText(),
                        frame.fullSnapshotVersion(),
                        shortenHash(frame.payloadHash()),
                        payloadBytes == null ? 0 : payloadBytes.length,
                        frame.reason()
                );
            }
            return true;
        } catch (Throwable throwable) {
            if (!handedToPipeline) {
                releaseQuietly(carrierPacketBytes);
            }
            if (shouldIgnoreControlFrameSendFailure(channel, throwable)) {
                return false;
            }
            ChannelTransportRuntimeGuard.disableTransport("chunk-control-frame-send", throwable);
            return false;
        }
    }

    private static PacketFlow resolveOutboundPacketFlow(ChannelHandlerContext encoderContext) {
        if (encoderContext == null || !(encoderContext.handler() instanceof PacketEncoderFlowAccess flowAccess)) {
            return null;
        }
        return flowAccess.bandwidthoptimizer$getPacketFlow();
    }

    private static void releaseQuietly(ByteBuf byteBuf) {
        if (byteBuf == null) {
            return;
        }
        try {
            byteBuf.release();
        } catch (RuntimeException ignored) {}
    }

    private static String readProtocolName(Channel channel) {
        Object protocol = net.minecraft.network.ConnectionProtocol.PLAY;
        return protocol == null ? "null" : String.valueOf(protocol);
    }

    private static boolean isRuntimeFailureReason(String reason) {
        return reason != null && reason.startsWith("runtime_");
    }

    private static boolean shouldIgnoreControlFrameSendFailure(Channel channel, Throwable throwable) {
        return isChannelClosing(channel) || isExpectedShutdownFailure(throwable);
    }

    private static boolean isChannelClosing(Channel channel) {
        return channel == null || !channel.isOpen() || !channel.isActive();
    }

    private static boolean isExpectedShutdownFailure(Throwable throwable) {
        Throwable current = throwable;
        while (current != null) {
            if (current instanceof ClosedChannelException) {
                return true;
            }
            if (current instanceof IOException && hasExpectedShutdownMessage(current.getMessage())) {
                return true;
            }
            current = current.getCause();
        }
        return false;
    }

    private static boolean hasExpectedShutdownMessage(String message) {
        if (message == null || message.isBlank()) {
            return false;
        }
        String lowerCaseMessage = message.toLowerCase(Locale.ROOT);
        return lowerCaseMessage.contains("connection reset")
                || lowerCaseMessage.contains("broken pipe")
                || lowerCaseMessage.contains("forcibly closed")
                || lowerCaseMessage.contains("existing connection was forcibly closed");
    }

    private static String shortenHash(String hashHex) {
        if (hashHex == null || hashHex.isBlank()) {
            return "<none>";
        }
        return hashHex.length() <= 12 ? hashHex : hashHex.substring(0, 12);
    }
}
