package com.PinkCats.bandwidthoptimizer.chunk.integration.transport;

import com.PinkCats.bandwidthoptimizer.Bandwidthoptimizer;
import com.PinkCats.bandwidthoptimizer.chunk.classify.ChunkHotspotKind;
import com.PinkCats.bandwidthoptimizer.chunk.classify.ChunkLaneKind;
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
import com.PinkCats.bandwidthoptimizer.chunk.verify.stats.ChunkHotspotStats;
import com.PinkCats.bandwidthoptimizer.chunk.verify.stats.ChunkHotspotVerifyHooks;
import io.netty.channel.ChannelHandlerContext;
import net.minecraft.network.protocol.Packet;

import java.util.concurrent.atomic.AtomicLong;

public final class ChunkTransportDispatcher {

    private static final AtomicLong OUTBOUND_FULL_FRAME_COUNT = new AtomicLong();
    private static final AtomicLong OUTBOUND_REF_FRAME_COUNT = new AtomicLong();
    private static final AtomicLong OUTBOUND_PATCH_FRAME_COUNT = new AtomicLong();
    private static final AtomicLong INBOUND_FULL_FRAME_COUNT = new AtomicLong();
    private static final AtomicLong INBOUND_REF_FRAME_COUNT = new AtomicLong();
    private static final AtomicLong INBOUND_PATCH_FRAME_COUNT = new AtomicLong();

    private ChunkTransportDispatcher() {
    }

    // full/ref/patch -> chunk transport envelope。
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
        RuntimeChunkTransportDecision runtimeDecision =
                decideRuntimeTransport(descriptor, fingerprint, peerSnapshot, knownChunkSnapshot);
        if (runtimeDecision == null) {
            return null;
        }

        byte[] envelopePayloadBytes = runtimeDecision.operation() == ChunkHotspotFrameOp.PUBLISH_REF
                ? new byte[0]
                : originalPacketBytes;
        byte[] encodedEnvelopeBytes = ChunkTransportEnvelopeCodec.encodeEnvelope(
                new ChunkTransportEnvelope(runtimeDecision.frame(), envelopePayloadBytes)
        );
        ChunkHotspotStats.recordOutboundFrame(
                runtimeDecision.frame(),
                originalPacketBytes == null ? 0 : originalPacketBytes.length,
                encodedEnvelopeBytes.length
        );
        ChunkHotspotVerifyHooks.flushCurrentReport();
        long frameCount = incrementOutboundFrameCount(runtimeDecision.operation());
        if (shouldLogSample(frameCount)) {
            Bandwidthoptimizer.LOGGER.info(
                    "[ChunkTransport][Wrap] channel={}, op={}, count={}, epoch={}, observedPackets={}, chunk={}, payloadBytes={}, envelopeBytes={}, payloadHash={}",
                    readChannelId(context),
                    runtimeDecision.operation().logName(),
                    frameCount,
                    runtimeDecision.frame().epoch(),
                    runtimeDecision.frame().observedPacketCount(),
                    descriptor.coordinate().logText(),
                    runtimeDecision.operation() == ChunkHotspotFrameOp.PUBLISH_REF ? 0 : originalPacketBytes == null ? 0 : originalPacketBytes.length,
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
            logInboundFrame(context, packetBytes, envelope, restoredPacketBytes, INBOUND_FULL_FRAME_COUNT);
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
            logInboundFrame(context, packetBytes, envelope, restoredPacketBytes, INBOUND_REF_FRAME_COUNT);
            return restoredPacketBytes;
        }

        if (envelope.frame().operation() == ChunkHotspotFrameOp.PUBLISH_PATCH) {
            byte[] restoredPacketBytes = envelope.copyOriginalPacketBytes();
            logInboundFrame(context, packetBytes, envelope, restoredPacketBytes, INBOUND_PATCH_FRAME_COUNT);
            return restoredPacketBytes;
        }

        throw new IllegalStateException("Unsupported chunk transport operation in runtime MVP: " + envelope.frame().operation().logName());
    }

    private static RuntimeChunkTransportDecision decideRuntimeTransport(
            ChunkPacketDescriptor descriptor,
            ChunkSnapshotFingerprint fingerprint,
            ChunkPeerStateSnapshot peerSnapshot,
            ChunkPeerChunkStateSnapshot knownChunkSnapshot
    ) {
        if (!shouldUseRuntimeChunkTransport(descriptor) || fingerprint == null) {
            return null;
        }

        if (descriptor.hotspotKind() == ChunkHotspotKind.FULL_CHUNK) {
            if (shouldUseRuntimeReference(knownChunkSnapshot, fingerprint)) {
                return new RuntimeChunkTransportDecision(
                        ChunkHotspotFrameOp.PUBLISH_REF,
                        buildRuntimeRefFrame(descriptor, fingerprint, peerSnapshot, knownChunkSnapshot)
                );
            }
            return new RuntimeChunkTransportDecision(
                    ChunkHotspotFrameOp.PUBLISH_FULL,
                    buildRuntimeFullFrame(descriptor, fingerprint, peerSnapshot)
            );
        }

        if (!shouldUseRuntimePatch(knownChunkSnapshot)) {
            return null;
        }

        return new RuntimeChunkTransportDecision(
                ChunkHotspotFrameOp.PUBLISH_PATCH,
                buildRuntimePatchFrame(descriptor, fingerprint, peerSnapshot, knownChunkSnapshot)
        );
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

    private static ChunkHotspotFrame buildRuntimePatchFrame(
            ChunkPacketDescriptor descriptor,
            ChunkSnapshotFingerprint fingerprint,
            ChunkPeerStateSnapshot peerSnapshot,
            ChunkPeerChunkStateSnapshot knownChunkSnapshot
    ) {
        long epoch = peerSnapshot == null ? 0L : peerSnapshot.epoch();
        long observedPackets = peerSnapshot == null ? 0L : peerSnapshot.observedPacketCount();
        return new ChunkHotspotFrame(
                ChunkHotspotFrameCodec.PROTOCOL_VERSION,
                ChunkHotspotFrameOp.PUBLISH_PATCH,
                epoch,
                observedPackets,
                descriptor.protocolName(),
                descriptor.packetClassName(),
                descriptor.hotspotKind(),
                descriptor.laneKind(),
                descriptor.coordinate(),
                Math.max(fingerprint.encodedBytes(), 0),
                knownChunkSnapshot == null ? 0L : knownChunkSnapshot.fullSnapshotVersion(),
                resolveLaneVersion(descriptor.laneKind(), knownChunkSnapshot),
                knownChunkSnapshot == null ? fingerprint.hashHex() : knownChunkSnapshot.knownSnapshotHash(),
                fingerprint.hashHex(),
                knownChunkSnapshot == null ? 0L : knownChunkSnapshot.deltaBytesSinceFullSnapshot(),
                "runtime_passthrough_patch_publish"
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
                && descriptor.hotspotKind() != null
                && descriptor.hasChunkCoordinate();
    }


    private static boolean shouldUseRuntimePatch(ChunkPeerChunkStateSnapshot knownChunkSnapshot) {
        return knownChunkSnapshot != null
                && knownChunkSnapshot.knownSnapshotPublished()
                && knownChunkSnapshot.fullSnapshotVersion() > 0L;
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


    private static long incrementOutboundFrameCount(ChunkHotspotFrameOp operation) {
        if (operation == ChunkHotspotFrameOp.PUBLISH_REF) {
            return OUTBOUND_REF_FRAME_COUNT.incrementAndGet();
        }
        if (operation == ChunkHotspotFrameOp.PUBLISH_PATCH) {
            return OUTBOUND_PATCH_FRAME_COUNT.incrementAndGet();
        }
        return OUTBOUND_FULL_FRAME_COUNT.incrementAndGet();
    }


    private static void logInboundFrame(
            ChannelHandlerContext context,
            byte[] packetBytes,
            ChunkTransportEnvelope envelope,
            byte[] restoredPacketBytes,
            AtomicLong inboundCounter
    ) {
        ChunkHotspotStats.recordInboundFrame(
                envelope.frame(),
                restoredPacketBytes == null ? 0 : restoredPacketBytes.length,
                packetBytes == null ? 0 : packetBytes.length
        );
        ChunkHotspotVerifyHooks.flushCurrentReport();
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

    private static long resolveLaneVersion(ChunkLaneKind laneKind, ChunkPeerChunkStateSnapshot knownChunkSnapshot) {
        if (laneKind == null || knownChunkSnapshot == null) {
            return 0L;
        }
        if (laneKind == ChunkLaneKind.LIGHT) {
            return knownChunkSnapshot.lightLaneVersion();
        }
        if (laneKind == ChunkLaneKind.SECTION_BLOCKS) {
            return knownChunkSnapshot.sectionBlocksLaneVersion();
        }
        if (laneKind == ChunkLaneKind.BLOCK) {
            return knownChunkSnapshot.blockLaneVersion();
        }
        if (laneKind == ChunkLaneKind.BLOCK_ENTITY) {
            return knownChunkSnapshot.blockEntityLaneVersion();
        }
        return 0L;
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

    private record RuntimeChunkTransportDecision(
            ChunkHotspotFrameOp operation,
            ChunkHotspotFrame frame
    ) {
    }
}
