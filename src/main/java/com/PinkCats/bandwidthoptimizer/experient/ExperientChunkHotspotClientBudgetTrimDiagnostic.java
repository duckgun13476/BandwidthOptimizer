package com.PinkCats.bandwidthoptimizer.experient;

import com.PinkCats.bandwidthoptimizer.Bandwidthoptimizer;
import com.PinkCats.bandwidthoptimizer.channel.ChannelTransportPacketCodec;
import com.PinkCats.bandwidthoptimizer.channel.ChannelTransportSession;
import com.PinkCats.bandwidthoptimizer.channel.ChannelTransportStateManager;
import com.PinkCats.bandwidthoptimizer.channel.algorithm.KineticChannel;
import com.PinkCats.bandwidthoptimizer.chunk.integration.transport.ChunkTransportRuntimeConfig;
import com.PinkCats.bandwidthoptimizer.chunk.integration.transport.Envelope.ChunkTransportEnvelope;
import com.PinkCats.bandwidthoptimizer.chunk.integration.transport.Envelope.ChunkTransportEnvelopeCodec;
import com.PinkCats.bandwidthoptimizer.chunk.protocol.hotspot.ChunkHotspotFrame;
import com.PinkCats.bandwidthoptimizer.chunk.protocol.hotspot.ChunkHotspotFrameOp;
import io.netty.buffer.Unpooled;
import io.netty.channel.Channel;
import io.netty.channel.ChannelHandlerContext;
import net.minecraft.network.Connection;

import java.util.concurrent.ConcurrentHashMap;

public final class ExperientChunkHotspotClientBudgetTrimDiagnostic {

    public static final String ENABLED_PROPERTY = "bandwidthoptimizer.experient.chunkHotspotClientBudgetTrimReplay";
    private static final int MAX_PENDING_REPLAYS = 512;
    private static final ConcurrentHashMap<String, PendingReplay> PENDING_REPLAYS = new ConcurrentHashMap<>();
    private static final ConcurrentHashMap<String, ChunkHotspotFrame> FULL_REPLAY_TEMPLATES = new ConcurrentHashMap<>();

    private ExperientChunkHotspotClientBudgetTrimDiagnostic() {}


    public static void maybeRememberReplayCandidate(
            ChannelHandlerContext context,
            ChunkHotspotFrame frame,
            byte[] transportPayloadBytes
    ) {
        if (!shouldRemember(context, frame, transportPayloadBytes)) {
            return;
        }

        if (PENDING_REPLAYS.size() >= MAX_PENDING_REPLAYS || FULL_REPLAY_TEMPLATES.size() >= MAX_PENDING_REPLAYS) {
            PENDING_REPLAYS.clear();
            FULL_REPLAY_TEMPLATES.clear();
        }

        if (frame.operation() == ChunkHotspotFrameOp.PUBLISH_FULL) {
            String fullTemplateKey = buildReplayKey(
                    context.channel().id().asLongText(),
                    frame.epoch(),
                    frame.coordinate().logText(),
                    frame.fullSnapshotVersion(),
                    frame.payloadHash()
            );
            FULL_REPLAY_TEMPLATES.put(fullTemplateKey, frame);
            return;
        }

        String replayKey = buildReplayKey(
                context.channel().id().asLongText(),
                frame.epoch(),
                frame.coordinate().logText(),
                frame.fullSnapshotVersion(),
                frame.baseSnapshotHash()
        );
        PENDING_REPLAYS.put(replayKey, new PendingReplay(frame, transportPayloadBytes));
    }

    public static void maybeReplayAfterClientBudgetInvalidate(
            ChannelHandlerContext context,
            ChunkHotspotFrame invalidateFrame
    ) {
        if (!shouldReplay(context, invalidateFrame)) {
            return;
        }

        String key = buildReplayKey(
                context.channel().id().asLongText(),
                invalidateFrame.epoch(),
                invalidateFrame.coordinate().logText(),
                invalidateFrame.fullSnapshotVersion(),
                invalidateFrame.payloadHash()
        );
        PendingReplay pendingReplay = PENDING_REPLAYS.remove(key);
        if (pendingReplay != null && isReplayContextAlive(context, pendingReplay.frame())) {
            sendReplayFrame(context, pendingReplay.frame(), pendingReplay.transportPayloadBytes(), invalidateFrame.reason());
            return;
        }

        ChunkHotspotFrame fullTemplate = FULL_REPLAY_TEMPLATES.get(key);
        if (fullTemplate == null || !isReplayContextAlive(context, fullTemplate)) {
            return;
        }

        sendSyntheticRefReplay(context, fullTemplate, invalidateFrame.reason());
    }

    private static boolean shouldRemember(
            ChannelHandlerContext context,
            ChunkHotspotFrame frame,
            byte[] transportPayloadBytes
    ) {
        return isEnabled()
                && context != null
                && frame != null
                && (frame.operation() == ChunkHotspotFrameOp.PUBLISH_FULL
                || frame.operation() == ChunkHotspotFrameOp.PUBLISH_REF
                || frame.operation() == ChunkHotspotFrameOp.PUBLISH_PATCH)
                && frame.epoch() > 0L
                && frame.coordinate() != null
                && frame.coordinate().present()
                && frame.fullSnapshotVersion() > 0L
                && hasReplayBaseHash(frame)
                && (frame.operation() == ChunkHotspotFrameOp.PUBLISH_FULL
                || transportPayloadBytes != null && transportPayloadBytes.length > 0);
    }

    private static boolean shouldReplay(
            ChannelHandlerContext context,
            ChunkHotspotFrame invalidateFrame
    ) {
        return isEnabled()
                && context != null
                && invalidateFrame != null
                && invalidateFrame.operation() == ChunkHotspotFrameOp.INVALIDATE
                && invalidateFrame.coordinate() != null
                && invalidateFrame.coordinate().present()
                && invalidateFrame.epoch() > 0L
                && invalidateFrame.fullSnapshotVersion() > 0L
                && invalidateFrame.payloadHash() != null
                && !invalidateFrame.payloadHash().isBlank()
                && invalidateFrame.reason() != null
                && invalidateFrame.reason().startsWith("client_chunk_cache_budget");
    }

    private static void sendReplayFrame(
            ChannelHandlerContext context,
            ChunkHotspotFrame frame,
            byte[] transportPayloadBytes,
            String triggerReason
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
                    "experient_diagnostic_budget_trim_replay"
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
                    "[Experient][ChunkDiag] send budget-trim replay, channel={}, op={}, epoch={}, chunk={}, fullVersion={}, baseHash={}, triggerReason={}, payloadBytes={}, envelopeBytes={}",
                    channel.id().asLongText(),
                    frame.operation().logName(),
                    frame.epoch(),
                    frame.coordinate().logText(),
                    frame.fullSnapshotVersion(),
                    shortenHash(frame.baseSnapshotHash()),
                    triggerReason == null || triggerReason.isBlank() ? "<unknown>" : triggerReason,
                    transportPayloadBytes.length,
                    encodedEnvelopeBytes.length
            );
            encoderContext.writeAndFlush(Unpooled.wrappedBuffer(wrappedFrame.transportFrameBytes()));
        } catch (Throwable throwable) {
            Bandwidthoptimizer.LOGGER.warn(
                    "[Experient][ChunkDiag] failed to send budget-trim replay, channel={}, op={}, epoch={}, chunk={}, fullVersion={}, baseHash={}, error={}",
                    channel.id().asLongText(),
                    frame.operation().logName(),
                    frame.epoch(),
                    frame.coordinate().logText(),
                    frame.fullSnapshotVersion(),
                    shortenHash(frame.baseSnapshotHash()),
                    throwable.toString()
            );
        }
    }

    private static void sendSyntheticRefReplay(
            ChannelHandlerContext context,
            ChunkHotspotFrame fullTemplate,
            String triggerReason
    ) {
        if (fullTemplate == null || fullTemplate.payloadHash() == null || fullTemplate.payloadHash().isBlank()) {
            return;
        }

        ChunkHotspotFrame syntheticRefFrame = new ChunkHotspotFrame(
                fullTemplate.protocolVersion(),
                ChunkHotspotFrameOp.PUBLISH_REF,
                fullTemplate.epoch(),
                fullTemplate.observedPacketCount(),
                fullTemplate.protocolName(),
                fullTemplate.packetClassName(),
                fullTemplate.hotspotKind(),
                fullTemplate.laneKind(),
                fullTemplate.coordinate(),
                fullTemplate.originalEncodedBytes(),
                fullTemplate.fullSnapshotVersion(),
                fullTemplate.laneVersion(),
                fullTemplate.payloadHash(),
                fullTemplate.payloadHash(),
                0L,
                "experient_diagnostic_budget_trim_synthetic_ref_replay"
        );
        sendReplayFrame(context, syntheticRefFrame, new byte[0], triggerReason);
    }

    private static boolean isReplayContextAlive(ChannelHandlerContext context, ChunkHotspotFrame frame) {
        if (context == null || frame == null) {
            return false;
        }

        Channel channel = context.channel();
        if (channel == null || !channel.isOpen() || !ChunkTransportRuntimeConfig.isEnabled()) {
            return false;
        }

        Object protocol = channel.attr(Connection.ATTRIBUTE_PROTOCOL).get();
        return protocol != null && "PLAY".equalsIgnoreCase(String.valueOf(protocol));
    }

    private static boolean isEnabled() {
        return ExperientRuntimeFlags.isEnabled()
                && Boolean.parseBoolean(System.getProperty(ENABLED_PROPERTY, "false"));
    }

    private static boolean hasReplayBaseHash(ChunkHotspotFrame frame) {
        if (frame == null) {
            return false;
        }

        if (frame.operation() == ChunkHotspotFrameOp.PUBLISH_FULL) {
            return frame.payloadHash() != null && !frame.payloadHash().isBlank();
        }
        return frame.baseSnapshotHash() != null && !frame.baseSnapshotHash().isBlank();
    }

    private static String buildReplayKey(
            String channelId,
            long epoch,
            String chunkText,
            long fullSnapshotVersion,
            String fullBaseHash
    ) {
        return channelId
                + ":"
                + epoch
                + ":"
                + chunkText
                + ":"
                + fullSnapshotVersion
                + ":"
                + fullBaseHash;
    }

    private static String shortenHash(String hashHex) {
        if (hashHex == null || hashHex.isBlank()) {
            return "<none>";
        }
        return hashHex.length() <= 12 ? hashHex : hashHex.substring(0, 12);
    }

    private record PendingReplay(
            ChunkHotspotFrame frame,
            byte[] transportPayloadBytes
    ) {
        private PendingReplay {
            transportPayloadBytes = transportPayloadBytes == null ? new byte[0] : transportPayloadBytes.clone();
        }
    }
}
