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
import com.PinkCats.bandwidthoptimizer.chunk.state.peer.ChunkPeerChunkStateSnapshot;
import com.PinkCats.bandwidthoptimizer.chunk.state.peer.ChunkPeerStateManager;
import com.PinkCats.bandwidthoptimizer.chunk.state.peer.ChunkPeerStateSnapshot;
import io.netty.channel.ChannelHandlerContext;
import net.minecraft.network.protocol.Packet;

import java.util.concurrent.atomic.AtomicLong;

public final class ChunkTransportDispatcher {

    private static final AtomicLong OUTBOUND_FULL_FRAME_COUNT = new AtomicLong();
    private static final AtomicLong OUTBOUND_REF_FRAME_COUNT = new AtomicLong();
    private static final AtomicLong INBOUND_FULL_FRAME_COUNT = new AtomicLong();
    private static final AtomicLong INBOUND_REF_FRAME_COUNT = new AtomicLong();

    private ChunkTransportDispatcher() {
    }

    // 这个函数在真正发包前根据连接已知状态决定是发 full 还是 ref；当前 runtime 只处理 FULL_CHUNK 这一类热点包。
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
        if (!shouldUseRuntimeChunkTransport(descriptor)) {
            return null;
        }

        ChunkSnapshotFingerprint fingerprint = ChunkSnapshotFingerprintService.fingerprintOutboundPacket(originalPacketBytes);
        ChunkPeerChunkStateSnapshot knownChunkSnapshot =
                ChunkPeerStateManager.snapshotOutboundChunk(context, descriptor.coordinate());
        ChunkPeerStateSnapshot peerSnapshot = ChunkPeerStateManager.snapshotOutboundChannel(context);
        boolean shouldUseReference = shouldUseRuntimeReference(knownChunkSnapshot, fingerprint);
        ChunkHotspotFrame frame = shouldUseReference
                ? buildRuntimeRefFrame(descriptor, fingerprint, peerSnapshot, knownChunkSnapshot)
                : buildRuntimeFullFrame(descriptor, fingerprint, peerSnapshot);
        byte[] envelopePayloadBytes = shouldUseReference ? new byte[0] : originalPacketBytes;
        byte[] encodedEnvelopeBytes = ChunkTransportEnvelopeCodec.encodeEnvelope(
                new ChunkTransportEnvelope(frame, envelopePayloadBytes)
        );
        long frameCount = shouldUseReference
                ? OUTBOUND_REF_FRAME_COUNT.incrementAndGet()
                : OUTBOUND_FULL_FRAME_COUNT.incrementAndGet();
        if (shouldLogSample(frameCount)) {
            Bandwidthoptimizer.LOGGER.info(
                    "[ChunkTransport][Wrap] channel={}, op={}, count={}, epoch={}, observedPackets={}, chunk={}, payloadBytes={}, envelopeBytes={}, payloadHash={}",
                    readChannelId(context),
                    frame.operation().logName(),
                    frameCount,
                    frame.epoch(),
                    frame.observedPacketCount(),
                    descriptor.coordinate().logText(),
                    shouldUseReference ? 0 : originalPacketBytes == null ? 0 : originalPacketBytes.length,
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
        if (envelope.frame().operation() == ChunkHotspotFrameOp.PUBLISH_FULL) {
            byte[] restoredPacketBytes = envelope.copyOriginalPacketBytes();
            ChunkRuntimeReferenceStore.storePacketBytes(
                    readChannelId(context),
                    envelope.frame().payloadHash(),
                    restoredPacketBytes
            );
            logInboundFrame(context, packetBytes, envelope, restoredPacketBytes, OUTBOUND_FULL_FRAME_COUNT, INBOUND_FULL_FRAME_COUNT);
            return restoredPacketBytes;
        }

        if (envelope.frame().operation() == ChunkHotspotFrameOp.PUBLISH_REF) {
            byte[] restoredPacketBytes = ChunkRuntimeReferenceStore.findPacketBytes(
                    readChannelId(context),
                    envelope.frame().payloadHash()
            );
            if (restoredPacketBytes == null) {
                throw new IllegalStateException(
                        "Missing runtime ref-only payload for hash "
                                + shortenHash(envelope.frame().payloadHash())
                                + " on channel "
                                + readChannelId(context)
                );
            }
            logInboundFrame(context, packetBytes, envelope, restoredPacketBytes, OUTBOUND_REF_FRAME_COUNT, INBOUND_REF_FRAME_COUNT);
            return restoredPacketBytes;
        }

        throw new IllegalStateException("Unsupported chunk transport operation in runtime MVP: " + envelope.frame().operation().logName());
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
                fingerprint.hashHex(),
                fingerprint.hashHex(),
                0L,
                "runtime_full_publish"
        );
    }

    private static ChunkHotspotFrame buildRuntimeRefFrame(
            ChunkPacketDescriptor descriptor,
            ChunkSnapshotFingerprint fingerprint,
            ChunkPeerStateSnapshot peerSnapshot,
            ChunkPeerChunkStateSnapshot knownChunkSnapshot
    ) {
        long epoch = peerSnapshot == null ? 0L : peerSnapshot.epoch();
        long observedPackets = peerSnapshot == null ? 0L : peerSnapshot.observedPacketCount();
        long fullSnapshotVersion = knownChunkSnapshot == null ? 0L : knownChunkSnapshot.fullSnapshotVersion();
        return new ChunkHotspotFrame(
                ChunkHotspotFrameCodec.PROTOCOL_VERSION,
                ChunkHotspotFrameOp.PUBLISH_REF,
                epoch,
                observedPackets,
                descriptor.protocolName(),
                descriptor.packetClassName(),
                descriptor.hotspotKind(),
                descriptor.laneKind(),
                descriptor.coordinate(),
                0,
                fullSnapshotVersion,
                0L,
                knownChunkSnapshot == null ? fingerprint.hashHex() : knownChunkSnapshot.knownSnapshotHash(),
                fingerprint.hashHex(),
                0L,
                "runtime_ref_only_publish"
        );
    }

    private static boolean shouldUseRuntimeChunkTransport(ChunkPacketDescriptor descriptor) {
        return descriptor != null
                && descriptor.hotspotKind() == ChunkHotspotKind.FULL_CHUNK
                && descriptor.hasChunkCoordinate();
    }


    private static boolean shouldUseRuntimeReference(
            ChunkPeerChunkStateSnapshot knownChunkSnapshot,
            ChunkSnapshotFingerprint fingerprint
    ) {
        return knownChunkSnapshot != null
                && knownChunkSnapshot.knownSnapshotPublished()
                && knownChunkSnapshot.totalObservedPacketCount() > 0L
                && fingerprint != null
                && fingerprint.hashHex().equals(knownChunkSnapshot.knownSnapshotHash());
    }


    private static void logInboundFrame(
            ChannelHandlerContext context,
            byte[] packetBytes,
            ChunkTransportEnvelope envelope,
            byte[] restoredPacketBytes,
            AtomicLong ignoredOutboundCounter,
            AtomicLong inboundCounter
    ) {
        long frameCount = inboundCounter.incrementAndGet();
        if (!shouldLogSample(frameCount)) {
            return;
        }

        Bandwidthoptimizer.LOGGER.info(
                "[ChunkTransport][Unwrap] channel={}, op={}, count={}, epoch={}, observedPackets={}, chunk={}, payloadBytes={}, envelopeBytes={}, payloadHash={}",
                readChannelId(context),
                envelope.frame().operation().logName(),
                frameCount,
                envelope.frame().epoch(),
                envelope.frame().observedPacketCount(),
                envelope.frame().coordinate().logText(),
                restoredPacketBytes.length,
                packetBytes == null ? 0 : packetBytes.length,
                shortenHash(envelope.frame().payloadHash())
        );
    }


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
