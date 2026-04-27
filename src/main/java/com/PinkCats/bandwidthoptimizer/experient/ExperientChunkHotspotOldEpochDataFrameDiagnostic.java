package com.PinkCats.bandwidthoptimizer.experient;

import com.PinkCats.bandwidthoptimizer.Bandwidthoptimizer;
import com.PinkCats.bandwidthoptimizer.channel.ChannelTransportPacketCodec;
import com.PinkCats.bandwidthoptimizer.channel.ChannelTransportSession;
import com.PinkCats.bandwidthoptimizer.channel.ChannelTransportStateManager;
import com.PinkCats.bandwidthoptimizer.channel.algorithm.KineticChannel;
import com.PinkCats.bandwidthoptimizer.chunk.integration.transport.Envelope.ChunkTransportEnvelope;
import com.PinkCats.bandwidthoptimizer.chunk.integration.transport.Envelope.ChunkTransportEnvelopeCodec;
import com.PinkCats.bandwidthoptimizer.chunk.integration.transport.ChunkTransportRuntimeConfig;
import com.PinkCats.bandwidthoptimizer.chunk.protocol.hotspot.ChunkHotspotFrame;
import com.PinkCats.bandwidthoptimizer.chunk.protocol.hotspot.ChunkHotspotFrameOp;
import io.netty.buffer.Unpooled;
import io.netty.channel.Channel;
import io.netty.channel.ChannelHandlerContext;

import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.ConcurrentHashMap;

public final class ExperientChunkHotspotOldEpochDataFrameDiagnostic {

    private static final AtomicBoolean OLD_EPOCH_FULL_REPLAY_SCHEDULED = new AtomicBoolean();
    private static final ConcurrentHashMap<String, PendingReplay> PENDING_REPLAYS = new ConcurrentHashMap<>();

    private ExperientChunkHotspotOldEpochDataFrameDiagnostic() {}

    public static void maybeScheduleOldEpochFullReplay(
            ChannelHandlerContext context,
            ChunkHotspotFrame frame,
            byte[] transportPayloadBytes
    ) {
        if (!shouldScheduleDiagnostic(context, frame, transportPayloadBytes)
                || !OLD_EPOCH_FULL_REPLAY_SCHEDULED.compareAndSet(false, true)) {
            return;
        }

        byte[] safePayloadBytes = transportPayloadBytes.clone();
        String channelId = context.channel().id().asLongText();
        PENDING_REPLAYS.put(
                channelId,
                new PendingReplay(frame, safePayloadBytes)
        );
        Bandwidthoptimizer.LOGGER.info(
                "[Experient][ChunkDiag] register old-epoch full replay, channel={}, epoch={}, chunk={}, fullVersion={}, payloadHash={}, payloadBytes={}",
                channelId,
                frame.epoch(),
                frame.coordinate().logText(),
                frame.fullSnapshotVersion(),
                shortenHash(frame.payloadHash()),
                safePayloadBytes.length
        );
    }


    public static void maybeReplayAfterNewerClientEpoch(
            ChannelHandlerContext context,
            ChunkHotspotFrame inboundControlFrame
    ) {
        if (context == null || inboundControlFrame == null) {
            return;
        }

        PendingReplay pendingReplay = PENDING_REPLAYS.get(context.channel().id().asLongText());
        if (pendingReplay == null
                || inboundControlFrame.epoch() <= pendingReplay.frame().epoch()
                || !isReplayContextAlive(context, pendingReplay.frame())) {
            return;
        }

        if (PENDING_REPLAYS.remove(context.channel().id().asLongText(), pendingReplay)) {
            sendReplayFrame(
                    context,
                    pendingReplay.frame(),
                    pendingReplay.transportPayloadBytes(),
                    inboundControlFrame.epoch(),
                    inboundControlFrame.operation()
            );
        }
    }


    private static void sendReplayFrame(
            ChannelHandlerContext context,
            ChunkHotspotFrame frame,
            byte[] transportPayloadBytes,
            long currentEpoch,
            ChunkHotspotFrameOp triggerOperation
    ) {
        Channel channel = context.channel();
        ChannelHandlerContext encoderContext = channel.pipeline().context("encoder");
        if (encoderContext == null) {
            return;
        }

        try {
            ChunkHotspotFrame diagnosticReplayFrame = new ChunkHotspotFrame(
                    frame.protocolVersion(),
                    frame.operation(),
                    frame.epoch(),
                    frame.observedPacketCount(),
                    frame.protocolName(),
                    frame.packetClassName(),
                    frame.hotspotKind(),
                    frame.laneKind(),
                    frame.coordinate(),
                    frame.originalEncodedBytes(),
                    frame.fullSnapshotVersion(),
                    frame.laneVersion(),
                    frame.baseSnapshotHash(),
                    frame.payloadHash(),
                    frame.deltaBytesSinceFullSnapshot(),
                    "experient_diagnostic_old_epoch_full_replay"
            );
            byte[] encodedEnvelopeBytes = ChunkTransportEnvelopeCodec.encodeEnvelope(
                    new ChunkTransportEnvelope(diagnosticReplayFrame, transportPayloadBytes)
            );
            ChannelTransportSession transportSession = ChannelTransportStateManager.getOrCreateSession(channel);
            ChannelTransportPacketCodec.WrappedTransportFrame wrappedFrame =
                    KineticChannel.processOutboundPacket(transportSession, encodedEnvelopeBytes);
            if (wrappedFrame == null) {
                return;
            }

            Bandwidthoptimizer.LOGGER.info(
                    "[Experient][ChunkDiag] send old-epoch full replay, channel={}, frameEpoch={}, currentEpoch={}, triggerOp={}, chunk={}, fullVersion={}, payloadHash={}, payloadBytes={}, envelopeBytes={}",
                    channel.id().asLongText(),
                    frame.epoch(),
                    currentEpoch,
                    triggerOperation == null ? "<unknown>" : triggerOperation.logName(),
                    frame.coordinate().logText(),
                    frame.fullSnapshotVersion(),
                    shortenHash(frame.payloadHash()),
                    transportPayloadBytes.length,
                    encodedEnvelopeBytes.length
            );
            encoderContext.writeAndFlush(Unpooled.wrappedBuffer(wrappedFrame.transportFrameBytes()));
        } catch (Throwable throwable) {
            Bandwidthoptimizer.LOGGER.warn(
                    "[Experient][ChunkDiag] failed to send old-epoch full replay, channel={}, frameEpoch={}, chunk={}, fullVersion={}, payloadHash={}, error={}",
                    channel.id().asLongText(),
                    frame.epoch(),
                    frame.coordinate().logText(),
                    frame.fullSnapshotVersion(),
                    shortenHash(frame.payloadHash()),
                    throwable.toString()
            );
        }
    }

    private static boolean shouldScheduleDiagnostic(
            ChannelHandlerContext context,
            ChunkHotspotFrame frame,
            byte[] transportPayloadBytes
    ) {
        return ExperientRuntimeFlags.isEnabled()
                && ExperientChunkHotspotAckDelayRuntimeConfig.isEnabled()
                && ExperientChunkHotspotPathRuntimeConfig.isDimensionHopMode()
                && context != null
                && frame != null
                && frame.operation() == ChunkHotspotFrameOp.PUBLISH_FULL
                && frame.epoch() == 1L
                && frame.coordinate() != null
                && frame.coordinate().present()
                && frame.fullSnapshotVersion() > 0L
                && frame.payloadHash() != null
                && !frame.payloadHash().isBlank()
                && transportPayloadBytes != null
                && transportPayloadBytes.length > 0;
    }

    private static boolean isReplayContextAlive(ChannelHandlerContext context, ChunkHotspotFrame frame) {
        if (context == null || frame == null) {
            return false;
        }

        Channel channel = context.channel();
        if (channel == null
                || !channel.isOpen()
                || !ChunkTransportRuntimeConfig.isEnabled()) {
            return false;
        }

        Object protocol = channel.attr(net.minecraft.network.Connection.ATTRIBUTE_PROTOCOL).get();
        return protocol != null && "PLAY".equalsIgnoreCase(String.valueOf(protocol));
    }

    private record PendingReplay(
            ChunkHotspotFrame frame,
            byte[] transportPayloadBytes
    ) {
        private PendingReplay {
            transportPayloadBytes = transportPayloadBytes == null ? new byte[0] : transportPayloadBytes.clone();
        }
    }

    private static String shortenHash(String hashHex) {
        if (hashHex == null || hashHex.isBlank()) {
            return "<none>";
        }
        return hashHex.length() <= 12 ? hashHex : hashHex.substring(0, 12);
    }
}
