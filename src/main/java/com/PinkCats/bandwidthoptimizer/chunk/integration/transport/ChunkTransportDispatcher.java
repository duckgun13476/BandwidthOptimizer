package com.PinkCats.bandwidthoptimizer.chunk.integration.transport;

import com.PinkCats.bandwidthoptimizer.Bandwidthoptimizer;
import com.PinkCats.bandwidthoptimizer.chunk.classify.ChunkHotspotKind;
import com.PinkCats.bandwidthoptimizer.chunk.classify.ChunkPacketClassifier;
import com.PinkCats.bandwidthoptimizer.chunk.classify.ChunkPacketDescriptor;
import com.PinkCats.bandwidthoptimizer.chunk.protocol.ChunkHotspotFrame;
import com.PinkCats.bandwidthoptimizer.chunk.protocol.ChunkHotspotFrameCodec;
import com.PinkCats.bandwidthoptimizer.chunk.protocol.ChunkHotspotFrameOp;
import com.PinkCats.bandwidthoptimizer.chunk.snapshot.ChunkSnapshotFingerprint;
import com.PinkCats.bandwidthoptimizer.chunk.snapshot.ChunkSnapshotFingerprintService;
import com.PinkCats.bandwidthoptimizer.chunk.state.peer.ChunkPeerStateManager;
import com.PinkCats.bandwidthoptimizer.chunk.state.peer.ChunkPeerStateSnapshot;
import io.netty.channel.ChannelHandlerContext;
import net.minecraft.network.protocol.Packet;

import java.util.concurrent.atomic.AtomicLong;

public final class ChunkTransportDispatcher {

    private static final AtomicLong OUTBOUND_FULL_FRAME_COUNT = new AtomicLong();
    private static final AtomicLong INBOUND_FULL_FRAME_COUNT = new AtomicLong();

    private ChunkTransportDispatcher() {
    }

    // Only handle full chunk
    public static byte[] tryEncodeOutboundPacket(
            ChannelHandlerContext context,
            String protocolName,
            Packet<?> packet,
            byte[] originalPacketBytes
    ) {
        if (!ChunkTransportRuntimeConfig.isEnabled()) {
            return null;
        }

        ChunkPacketDescriptor descriptor = ChunkPacketClassifier.classifyOutboundPlayPacket(protocolName, packet);
        if (!shouldUseRuntimeFullOnly(descriptor)) {
            return null;
        }

        ChunkSnapshotFingerprint fingerprint = ChunkSnapshotFingerprintService.fingerprintOutboundPacket(originalPacketBytes);
        ChunkPeerStateSnapshot peerSnapshot = ChunkPeerStateManager.snapshotOutboundChannel(context);
        ChunkHotspotFrame frame = buildRuntimeFullFrame(descriptor, fingerprint, peerSnapshot);
        byte[] encodedEnvelopeBytes = ChunkTransportEnvelopeCodec.encodeEnvelope(
                new ChunkTransportEnvelope(frame, originalPacketBytes)
        );
        long frameCount = OUTBOUND_FULL_FRAME_COUNT.incrementAndGet();
        if (shouldLogSample(frameCount)) {
            Bandwidthoptimizer.LOGGER.info(
                    "[ChunkTransport][Wrap] channel={}, count={}, epoch={}, observedPackets={}, chunk={}, payloadBytes={}, envelopeBytes={}, payloadHash={}",
                    readChannelId(context),
                    frameCount,
                    frame.epoch(),
                    frame.observedPacketCount(),
                    descriptor.coordinate().logText(),
                    originalPacketBytes == null ? 0 : originalPacketBytes.length,
                    encodedEnvelopeBytes.length,
                    fingerprint.shortHash()
            );
        }
        return encodedEnvelopeBytes;
    }

    //  transport -> packet
    public static byte[] tryDecodeInboundPacket(ChannelHandlerContext context, byte[] packetBytes) {
        if (!ChunkTransportEnvelopeCodec.looksLikeEnvelope(packetBytes)) {
            return packetBytes;
        }

        ChunkTransportEnvelope envelope = ChunkTransportEnvelopeCodec.decodeEnvelope(packetBytes);
        if (envelope.frame().operation() != ChunkHotspotFrameOp.PUBLISH_FULL) {
            throw new IllegalStateException("Unsupported chunk transport operation in runtime MVP: " + envelope.frame().operation().logName());
        }

        byte[] restoredPacketBytes = envelope.copyOriginalPacketBytes();
        long frameCount = INBOUND_FULL_FRAME_COUNT.incrementAndGet();
        if (shouldLogSample(frameCount)) {
            Bandwidthoptimizer.LOGGER.info(
                    "[ChunkTransport][Unwrap] channel={}, count={}, epoch={}, observedPackets={}, chunk={}, payloadBytes={}, envelopeBytes={}, payloadHash={}",
                    readChannelId(context),
                    frameCount,
                    envelope.frame().epoch(),
                    envelope.frame().observedPacketCount(),
                    envelope.frame().coordinate().logText(),
                    restoredPacketBytes.length,
                    packetBytes == null ? 0 : packetBytes.length,
                    shortenHash(envelope.frame().payloadHash())
            );
        }
        return restoredPacketBytes;
    }


    private static ChunkHotspotFrame buildRuntimeFullFrame(
            ChunkPacketDescriptor descriptor,
            ChunkSnapshotFingerprint fingerprint,
            ChunkPeerStateSnapshot peerSnapshot
    ) {
        long epoch = peerSnapshot == null ? 0L : peerSnapshot.epoch();
        long observedPackets = peerSnapshot == null ? 0L : peerSnapshot.observedPacketCount();
        return new ChunkHotspotFrame(
                ChunkHotspotFrameCodec.PROTOCOL_VERSION,
                ChunkHotspotFrameOp.PUBLISH_FULL,
                epoch,
                observedPackets,
                descriptor.protocolName(),
                descriptor.packetClassName(),
                descriptor.hotspotKind(),
                descriptor.laneKind(),
                descriptor.coordinate(),
                Math.max(fingerprint.encodedBytes(), 0),
                0L,
                0L,
                "",
                fingerprint.hashHex(),
                0L,
                "runtime_full_only_mvp"
        );
    }

    private static boolean shouldUseRuntimeFullOnly(ChunkPacketDescriptor descriptor) {
        return descriptor != null
                && descriptor.hotspotKind() == ChunkHotspotKind.FULL_CHUNK
                && descriptor.hasChunkCoordinate();}


    private static boolean shouldLogSample(long frameCount) {
        return frameCount <= 5L || frameCount % 100L == 0L;
    }


    private static String readChannelId(ChannelHandlerContext context) {
        if (context == null)
            return "<null>";
        return context.channel().id().asLongText();
    }

    private static String shortenHash(String hashHex) {
        if (hashHex == null || hashHex.isBlank())
            return "<none>";
        return hashHex.length() <= 12 ? hashHex : hashHex.substring(0, 12);
    }
}
