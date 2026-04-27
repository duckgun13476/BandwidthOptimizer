package com.PinkCats.bandwidthoptimizer.chunk.integration.transport;

import com.PinkCats.bandwidthoptimizer.Bandwidthoptimizer;
import com.PinkCats.bandwidthoptimizer.chunk.classify.packet.ChunkPacketClassifier;
import com.PinkCats.bandwidthoptimizer.chunk.classify.packet.ChunkPacketDescriptor;
import com.PinkCats.bandwidthoptimizer.chunk.integration.ChunkInboundDecodeResult;
import com.PinkCats.bandwidthoptimizer.chunk.integration.ChunkRuntimeReferenceStore;
import com.PinkCats.bandwidthoptimizer.chunk.integration.transport.Envelope.ChunkTransportEnvelope;
import com.PinkCats.bandwidthoptimizer.chunk.integration.transport.Envelope.ChunkTransportEnvelopeCodec;
import com.PinkCats.bandwidthoptimizer.chunk.patch.ChunkPatch;
import com.PinkCats.bandwidthoptimizer.chunk.patch.ChunkPatchApplier;
import com.PinkCats.bandwidthoptimizer.chunk.patch.ChunkPatchBuilder;
import com.PinkCats.bandwidthoptimizer.chunk.plan.ChunkPlanDecision;
import com.PinkCats.bandwidthoptimizer.chunk.plan.ChunkPlanDecisionKind;
import com.PinkCats.bandwidthoptimizer.chunk.plan.ChunkTransportPlanner;
import com.PinkCats.bandwidthoptimizer.chunk.packet.ClientboundPlayPacketCodec;
import com.PinkCats.bandwidthoptimizer.chunk.packet.ChunkHeavyProtocolBypassPacketList;
import com.PinkCats.bandwidthoptimizer.chunk.protocol.hotspot.ChunkHotspotFrame;
import com.PinkCats.bandwidthoptimizer.chunk.protocol.hotspot.ChunkHotspotFrameCodec;
import com.PinkCats.bandwidthoptimizer.chunk.protocol.hotspot.ChunkHotspotFrameOp;
import com.PinkCats.bandwidthoptimizer.chunk.snapshot.lane.ChunkLanePacketSnapshot;
import com.PinkCats.bandwidthoptimizer.chunk.snapshot.lane.ChunkLaneSnapshot;
import com.PinkCats.bandwidthoptimizer.chunk.snapshot.shadow.ChunkShadowSnapshot;
import com.PinkCats.bandwidthoptimizer.chunk.snapshot.shadow.ChunkShadowSnapshotManager;
import com.PinkCats.bandwidthoptimizer.chunk.snapshot.ChunkSnapshotFingerprint;
import com.PinkCats.bandwidthoptimizer.chunk.snapshot.ChunkSnapshotFingerprintService;
import com.PinkCats.bandwidthoptimizer.chunk.PeerState.ChunkPeerChunkStateSnapshot;
import com.PinkCats.bandwidthoptimizer.chunk.PeerState.ChunkPeerStateManager;
import com.PinkCats.bandwidthoptimizer.chunk.PeerState.ChunkPeerStateSnapshot;
import com.PinkCats.bandwidthoptimizer.chunk.verify.ChunkHotspotStats;
import com.PinkCats.bandwidthoptimizer.chunk.verify.ChunkHotspotVerifyHooks;
import com.PinkCats.bandwidthoptimizer.experient.ExperientChunkHotspotAckDelayController;
import io.netty.channel.ChannelHandlerContext;
import net.minecraft.network.protocol.Packet;
import net.minecraft.network.protocol.game.ClientGamePacketListener;

import java.util.concurrent.atomic.AtomicLong;

public final class ChunkTransportDispatcher {

    private static final AtomicLong OUTBOUND_FULL_FRAME_COUNT = new AtomicLong();
    private static final AtomicLong OUTBOUND_REF_FRAME_COUNT = new AtomicLong();
    private static final AtomicLong OUTBOUND_PATCH_FRAME_COUNT = new AtomicLong();
    private static final AtomicLong OUTBOUND_ACK_FRAME_COUNT = new AtomicLong();
    private static final AtomicLong OUTBOUND_NACK_FRAME_COUNT = new AtomicLong();
    private static final AtomicLong OUTBOUND_INVALIDATE_FRAME_COUNT = new AtomicLong();
    private static final AtomicLong INBOUND_FULL_FRAME_COUNT = new AtomicLong();
    private static final AtomicLong INBOUND_REF_FRAME_COUNT = new AtomicLong();
    private static final AtomicLong INBOUND_PATCH_FRAME_COUNT = new AtomicLong();
    private static final AtomicLong INBOUND_ACK_FRAME_COUNT = new AtomicLong();
    private static final AtomicLong INBOUND_NACK_FRAME_COUNT = new AtomicLong();
    private static final AtomicLong INBOUND_INVALIDATE_FRAME_COUNT = new AtomicLong();

    private ChunkTransportDispatcher() {
    }

    // full/ref/patch -> chunk transport envelope。
    public static byte[] tryEncodeOutboundPacket(
            ChannelHandlerContext context,
            String protocolName,
            Packet<?> packet,
            byte[] originalPacketBytes
    ) {
        if (!ChunkTransportRuntimeConfig.isEnabled())
            return null;

        if (shouldBypassHeavyChunkProtocol(packet))
            return null;

        ChunkPacketDescriptor descriptor = ChunkPacketClassifier.classifyOutboundPlayPacket(protocolName, packet);
        if (!shouldUseRuntimeChunkTransport(descriptor))
            return null;

        ChunkTransportBoundaryController.ChunkTransportPermit transportPermit =
                ChunkTransportBoundaryController.permitChunkTransport(context, descriptor);
        if (!transportPermit.allowed()) {
            return null;
        }

        ChunkSnapshotFingerprint fingerprint = ChunkSnapshotFingerprintService.fingerprintOutboundPacket(originalPacketBytes);
        ChunkPeerChunkStateSnapshot knownChunkSnapshot =
                ChunkPeerStateManager.snapshotOutboundChunk(context, descriptor.coordinate());
        ChunkPeerStateSnapshot peerSnapshot = ChunkPeerStateManager.snapshotOutboundChannel(context);
        ChunkShadowSnapshot localChunkSnapshot =
                ChunkShadowSnapshotManager.snapshotChunk(readChannelId(context), descriptor.coordinate());
        ChunkPatchBuilder.ChunkPatchBuildResult patchBuildResult = ChunkPatchBuilder.buildPatchFromSnapshot(
                localChunkSnapshot,
                descriptor,
                packet,
                originalPacketBytes,
                fingerprint
        );
        RuntimeChunkTransportDecision runtimeDecision =
                decideRuntimeTransport(
                        descriptor,
                        fingerprint,
                        peerSnapshot,
                        knownChunkSnapshot,
                        patchBuildResult,
                        originalPacketBytes
                );
        if (runtimeDecision == null) {
            return null;
        }

        byte[] envelopePayloadBytes = runtimeDecision.copyTransportPayloadBytes();
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
                    envelopePayloadBytes.length,
                    encodedEnvelopeBytes.length,
                    fingerprint.shortHash()
            );
        }
        return encodedEnvelopeBytes;
    }

    public static OutboundChunkEncodeResult tryEncodeOutboundPacketWithTrace(
            ChannelHandlerContext context,
            String protocolName,
            Packet<?> packet,
            byte[] originalPacketBytes
    ) {
        if (!ChunkTransportRuntimeConfig.isEnabled())
            return OutboundChunkEncodeResult.bypass(false, "runtime_chunk_transport_disabled");

        if (shouldBypassHeavyChunkProtocol(packet))
            return OutboundChunkEncodeResult.bypass(false, "heavy_chunk_protocol_bypass");

        ChunkPacketDescriptor descriptor = ChunkPacketClassifier.classifyOutboundPlayPacket(protocolName, packet);
        boolean chunkPacketCandidate = shouldUseRuntimeChunkTransport(descriptor);
        if (!chunkPacketCandidate)
            return OutboundChunkEncodeResult.bypass(false, "descriptor_not_chunk_candidate");

        ChunkTransportBoundaryController.ChunkTransportPermit transportPermit =
                ChunkTransportBoundaryController.permitChunkTransport(context, descriptor);
        if (!transportPermit.allowed()) {
            return OutboundChunkEncodeResult.bypass(true, transportPermit.reason());
        }

        ChunkSnapshotFingerprint fingerprint = ChunkSnapshotFingerprintService.fingerprintOutboundPacket(originalPacketBytes);
        if (fingerprint == null) {
            return OutboundChunkEncodeResult.bypass(true, "missing_snapshot_fingerprint");
        }

        ChunkPeerChunkStateSnapshot knownChunkSnapshot =
                ChunkPeerStateManager.snapshotOutboundChunk(context, descriptor.coordinate());
        ChunkPeerStateSnapshot peerSnapshot = ChunkPeerStateManager.snapshotOutboundChannel(context);
        ChunkShadowSnapshot localChunkSnapshot =
                ChunkShadowSnapshotManager.snapshotChunk(readChannelId(context), descriptor.coordinate());
        ChunkPatchBuilder.ChunkPatchBuildResult patchBuildResult = ChunkPatchBuilder.buildPatchFromSnapshot(
                localChunkSnapshot,
                descriptor,
                packet,
                originalPacketBytes,
                fingerprint
        );
        RuntimeChunkPlanningResult planningResult =
                planRuntimeTransportWithTrace(
                        descriptor,
                        fingerprint,
                        peerSnapshot,
                        knownChunkSnapshot,
                        patchBuildResult,
                        originalPacketBytes
                );
        RuntimeChunkTransportDecision runtimeDecision = planningResult.transportDecision();
        if (runtimeDecision == null) {
            return OutboundChunkEncodeResult.bypass(true, planningResult.bypassReason());
        }

        byte[] encodedEnvelopeBytes = ChunkTransportEnvelopeCodec.encodeEnvelope(
                new ChunkTransportEnvelope(runtimeDecision.frame(), runtimeDecision.copyTransportPayloadBytes())
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
                    runtimeDecision.copyTransportPayloadBytes().length,
                    encodedEnvelopeBytes.length,
                    fingerprint.shortHash()
            );
        }
        return OutboundChunkEncodeResult.applied(
                runtimeDecision.operation(),
                runtimeDecision.frame().reason(),
                encodedEnvelopeBytes
        );
    }

    public static ChunkInboundDecodeResult tryDecodeInboundPacket(ChannelHandlerContext context, byte[] packetBytes) {
        if (!ChunkTransportEnvelopeCodec.looksLikeEnvelope(packetBytes)) {
            return ChunkInboundDecodeResult.passthrough(packetBytes);
        }

        ChunkTransportEnvelope envelope = ChunkTransportEnvelopeCodec.decodeEnvelope(packetBytes);
        if (envelope.frame().operation() == ChunkHotspotFrameOp.PUBLISH_FULL) {
            byte[] restoredPacketBytes = envelope.copyOriginalPacketBytes();
            observeInboundSnapshot(context, envelope.frame(), restoredPacketBytes);
            ChunkRuntimeReferenceStore.storePacketBytes(
                    readChannelId(context),
                    envelope.frame().payloadHash(),
                    restoredPacketBytes
            );
            ChunkRuntimeReferenceStore.storeFullSnapshot(readChannelId(context), envelope.frame(), restoredPacketBytes);
            if (!ExperientChunkHotspotAckDelayController.maybeDelayAck(context, envelope.frame(), "runtime_full_received")) {
                ChunkTransportControlFrameSender.sendAck(context, envelope.frame(), "runtime_full_received");
            }
            logInboundFrame(context, packetBytes, envelope, restoredPacketBytes, INBOUND_FULL_FRAME_COUNT);
            return ChunkInboundDecodeResult.passthrough(restoredPacketBytes);
        }

        if (envelope.frame().operation() == ChunkHotspotFrameOp.PUBLISH_REF) {
            String channelId = readChannelId(context);
            byte[] restoredPacketBytes = ChunkShadowSnapshotManager.materializeFullChunkPacket(
                    channelId,
                    envelope.frame().coordinate(),
                    envelope.frame().fullSnapshotVersion(),
                    envelope.frame().baseSnapshotHash()
            );
            boolean restoredFromSnapshot = restoredPacketBytes != null;
            ChunkRuntimeReferenceStore.RuntimeFullSnapshot runtimeFullSnapshot = null;
            if (!restoredFromSnapshot) {
                runtimeFullSnapshot = ChunkRuntimeReferenceStore.findFullSnapshot(
                        channelId,
                        envelope.frame().coordinate()
                );
                restoredPacketBytes = ChunkRuntimeReferenceStore.findPacketBytes(
                        channelId,
                        envelope.frame().payloadHash()
                );
            }
            if (restoredPacketBytes == null || (!restoredFromSnapshot && !hasMatchingRuntimeFullSnapshot(runtimeFullSnapshot, envelope.frame()))) {
                ChunkTransportControlFrameSender.sendNack(context, envelope.frame(), "runtime_ref_missing_base");
                logInboundControlFrame(context, packetBytes, envelope.frame(), INBOUND_NACK_FRAME_COUNT);
                return ChunkInboundDecodeResult.consumeControlFrame();
            }
            observeInboundSnapshot(context, envelope.frame(), restoredPacketBytes);
            ChunkRuntimeReferenceStore.storePacketBytes(
                    channelId,
                    envelope.frame().payloadHash(),
                    restoredPacketBytes
            );
            ChunkRuntimeReferenceStore.storeFullSnapshot(channelId, envelope.frame(), restoredPacketBytes);
            logInboundFrame(context, packetBytes, envelope, restoredPacketBytes, INBOUND_REF_FRAME_COUNT);
            return ChunkInboundDecodeResult.passthrough(restoredPacketBytes);
        }

        if (envelope.frame().operation() == ChunkHotspotFrameOp.PUBLISH_PATCH) {
            byte[] restoredPacketBytes = tryRestorePatchedPacket(context, envelope);
            if (restoredPacketBytes == null) {
                return ChunkInboundDecodeResult.consumeControlFrame();
            }
            observeInboundSnapshot(context, envelope.frame(), restoredPacketBytes);
            logInboundFrame(context, packetBytes, envelope, restoredPacketBytes, INBOUND_PATCH_FRAME_COUNT);
            return ChunkInboundDecodeResult.passthrough(restoredPacketBytes);
        }

        if (envelope.frame().operation() == ChunkHotspotFrameOp.ACK) {
            ChunkPeerStateManager.acknowledgeOutboundChunk(context, envelope.frame());
            logInboundControlFrame(context, packetBytes, envelope.frame(), INBOUND_ACK_FRAME_COUNT);
            return ChunkInboundDecodeResult.consumeControlFrame();
        }

        if (envelope.frame().operation() == ChunkHotspotFrameOp.NACK) {
            ChunkPeerStateManager.negativeAcknowledgeOutboundChunk(context, envelope.frame());
            ChunkTransportControlFrameSender.sendInvalidate(context, envelope.frame(), "runtime_nack_received");
            logInboundControlFrame(context, packetBytes, envelope.frame(), INBOUND_NACK_FRAME_COUNT);
            return ChunkInboundDecodeResult.consumeControlFrame();
        }

        if (envelope.frame().operation() == ChunkHotspotFrameOp.INVALIDATE) {
            ChunkPeerStateManager.invalidateOutboundChunk(context, envelope.frame());
            ChunkRuntimeReferenceStore.invalidateFullSnapshot(readChannelId(context), envelope.frame().coordinate());
            ChunkShadowSnapshotManager.invalidateChunk(readChannelId(context), envelope.frame().coordinate());
            logInboundControlFrame(context, packetBytes, envelope.frame(), INBOUND_INVALIDATE_FRAME_COUNT);
            return ChunkInboundDecodeResult.consumeControlFrame();
        }

        throw new IllegalStateException("Unsupported chunk transport operation in runtime MVP: " + envelope.frame().operation().logName());
    }

    private static RuntimeChunkTransportDecision decideRuntimeTransport(
            ChunkPacketDescriptor descriptor,
            ChunkSnapshotFingerprint fingerprint,
            ChunkPeerStateSnapshot peerSnapshot,
            ChunkPeerChunkStateSnapshot knownChunkSnapshot,
            ChunkPatchBuilder.ChunkPatchBuildResult patchBuildResult,
            byte[] originalPacketBytes
    ) {
        if (!shouldUseRuntimeChunkTransport(descriptor) || fingerprint == null) {
            return null;
        }

        ChunkPlanDecision decision = ChunkTransportPlanner.planOutboundTransport(
                descriptor,
                fingerprint,
                knownChunkSnapshot,
                null,
                patchBuildResult
        );
        if (decision == null || decision.decisionKind() == ChunkPlanDecisionKind.BYPASS) {
            return null;
        }

        return new RuntimeChunkTransportDecision(
                mapOperation(decision.decisionKind()),
                buildRuntimeFrame(descriptor, peerSnapshot, decision),
                resolveTransportPayloadBytes(decision, patchBuildResult, originalPacketBytes)
        );
    }

    private static RuntimeChunkPlanningResult planRuntimeTransportWithTrace(
            ChunkPacketDescriptor descriptor,
            ChunkSnapshotFingerprint fingerprint,
            ChunkPeerStateSnapshot peerSnapshot,
            ChunkPeerChunkStateSnapshot knownChunkSnapshot,
            ChunkPatchBuilder.ChunkPatchBuildResult patchBuildResult,
            byte[] originalPacketBytes
    ) {
        if (!shouldUseRuntimeChunkTransport(descriptor) || fingerprint == null) {
            return new RuntimeChunkPlanningResult(null, "runtime_chunk_not_applicable");
        }

        ChunkPlanDecision decision = ChunkTransportPlanner.planOutboundTransport(
                descriptor,
                fingerprint,
                knownChunkSnapshot,
                null,
                patchBuildResult
        );
        if (decision == null || decision.decisionKind() == ChunkPlanDecisionKind.BYPASS) {
            String bypassReason = decision == null ? "planner_returned_null" : decision.reason();
            return new RuntimeChunkPlanningResult(null, bypassReason);
        }

        return new RuntimeChunkPlanningResult(
                new RuntimeChunkTransportDecision(
                        mapOperation(decision.decisionKind()),
                        buildRuntimeFrame(descriptor, peerSnapshot, decision),
                        resolveTransportPayloadBytes(decision, patchBuildResult, originalPacketBytes)
                ),
                ""
        );
    }

    private static boolean shouldBypassHeavyChunkProtocol(Packet<?> packet) {
        return ChunkHeavyProtocolBypassPacketList.shouldBypassHeavyChunkProtocol(packet);
    }


    private static ChunkHotspotFrame buildRuntimeFrame(
            ChunkPacketDescriptor descriptor,
            ChunkPeerStateSnapshot peerSnapshot,
            ChunkPlanDecision decision
    ) {
        long epoch = peerSnapshot == null ? 0L : peerSnapshot.epoch();
        long observedPackets = peerSnapshot == null ? 0L : peerSnapshot.observedPacketCount();
        return new ChunkHotspotFrame(
                ChunkHotspotFrameCodec.PROTOCOL_VERSION,
                mapOperation(decision.decisionKind()),
                epoch,
                observedPackets,
                descriptor.protocolName(),
                descriptor.packetClassName(),
                descriptor.hotspotKind(),
                descriptor.laneKind(),
                descriptor.coordinate(),
                resolveOriginalEncodedBytes(decision),
                decision.fullSnapshotVersion(),
                decision.laneVersion(),
                resolveBaseSnapshotHash(decision),
                decision.currentPayloadHash(),
                decision.deltaBytesSinceFullSnapshot(),
                decision.reason()
        );
    }

    private static boolean shouldUseRuntimeChunkTransport(ChunkPacketDescriptor descriptor) {
        return descriptor != null
                && descriptor.hotspotKind() != null
                && descriptor.hasChunkCoordinate();
    }


    private static byte[] resolveTransportPayloadBytes(
            ChunkPlanDecision decision,
            ChunkPatchBuilder.ChunkPatchBuildResult patchBuildResult,
            byte[] originalPacketBytes
    ) {
        if (decision == null || decision.decisionKind() == ChunkPlanDecisionKind.PUBLISH_REF) {
            return new byte[0];
        }

        if (decision.decisionKind() == ChunkPlanDecisionKind.PUBLISH_PATCH
                && patchBuildResult != null
                && patchBuildResult.patch() != null
                && patchBuildResult.beneficial()) {
            return patchBuildResult.copyEncodedPatchBytes();
        }

        return originalPacketBytes == null ? new byte[0] : originalPacketBytes.clone();
    }


    private static byte[] tryRestorePatchedPacket(ChannelHandlerContext context, ChunkTransportEnvelope envelope) {
        if (context == null || envelope == null || envelope.frame() == null) {
            return null;
        }

        String channelId = readChannelId(context);
        ChunkPatch chunkPatch;
        try {
            chunkPatch = ChunkPatch.decode(envelope.copyOriginalPacketBytes());
        } catch (RuntimeException exception) {
            ChunkTransportControlFrameSender.sendNack(context, envelope.frame(), "runtime_patch_decode_failed");
            return null;
        }

        ChunkShadowSnapshot chunkSnapshot = ChunkShadowSnapshotManager.snapshotChunk(channelId, envelope.frame().coordinate());
        if (!hasMatchingSnapshotFullBase(chunkSnapshot, envelope.frame())) {
            ChunkTransportControlFrameSender.sendNack(context, envelope.frame(), "runtime_patch_missing_full_base");
            return null;
        }

        ChunkLanePacketSnapshot basePacketSnapshot = resolvePatchBasePacketSnapshot(chunkSnapshot, envelope.frame(), chunkPatch);
        byte[] basePacketBytes = basePacketSnapshot == null ? new byte[0] : basePacketSnapshot.copyOriginalPacketBytes();
        if (!chunkPatch.basePayloadHash().isBlank() && basePacketSnapshot == null) {
            ChunkTransportControlFrameSender.sendNack(context, envelope.frame(), "runtime_patch_missing_lane_base");
            return null;
        }

        try {
            return ChunkPatchApplier.applyPatch(chunkPatch, basePacketBytes, envelope.frame().payloadHash());
        } catch (RuntimeException exception) {
            ChunkTransportControlFrameSender.sendNack(context, envelope.frame(), "runtime_patch_apply_failed");
            return null;
        }
    }

    private static void observeInboundSnapshot(
            ChannelHandlerContext context,
            ChunkHotspotFrame frame,
            byte[] restoredPacketBytes
    ) {
        if (context == null || frame == null || restoredPacketBytes == null) {
            return;
        }

        Packet<ClientGamePacketListener> restoredPacket = decodeClientboundPlayPacket(restoredPacketBytes);
        ChunkPacketDescriptor descriptor = ChunkPacketClassifier.classifyOutboundPlayPacket("PLAY", restoredPacket);
        if (descriptor == null) {
            return;
        }

        ChunkShadowSnapshotManager.observeInboundPacket(
                readChannelId(context),
                frame.epoch(),
                descriptor,
                restoredPacket,
                restoredPacketBytes
        );
    }

    private static Packet<ClientGamePacketListener> decodeClientboundPlayPacket(byte[] restoredPacketBytes) {
        return ClientboundPlayPacketCodec.decodePacket(restoredPacketBytes);
    }


    private static boolean hasMatchingSnapshotFullBase(ChunkShadowSnapshot chunkSnapshot, ChunkHotspotFrame frame) {
        return chunkSnapshot != null
                && frame != null
                && chunkSnapshot.hasFullSnapshot()
                && chunkSnapshot.fullSnapshotVersion() == frame.fullSnapshotVersion()
                && chunkSnapshot.fullSnapshotHash() != null
                && chunkSnapshot.fullSnapshotHash().equals(frame.baseSnapshotHash());
    }


    private static ChunkLanePacketSnapshot resolvePatchBasePacketSnapshot(
            ChunkShadowSnapshot chunkSnapshot,
            ChunkHotspotFrame frame,
            ChunkPatch chunkPatch
    ) {
        if (chunkSnapshot == null || frame == null || chunkPatch == null) {
            return null;
        }

        ChunkLaneSnapshot laneSnapshot = chunkSnapshot.laneSnapshot(frame.laneKind());
        if (laneSnapshot == null) {
            return null;
        }

        ChunkLanePacketSnapshot basePacketSnapshot = laneSnapshot.packet(chunkPatch.semanticKey());
        if (basePacketSnapshot == null) {
            return null;
        }

        if (!chunkPatch.basePayloadHash().isBlank()
                && !chunkPatch.basePayloadHash().equals(basePacketSnapshot.payloadHash())) {
            return null;
        }
        return basePacketSnapshot;
    }


    private static ChunkHotspotFrameOp mapOperation(ChunkPlanDecisionKind decisionKind) {
        if (decisionKind == ChunkPlanDecisionKind.PUBLISH_FULL) {
            return ChunkHotspotFrameOp.PUBLISH_FULL;
        }
        if (decisionKind == ChunkPlanDecisionKind.PUBLISH_REF) {
            return ChunkHotspotFrameOp.PUBLISH_REF;
        }
        return ChunkHotspotFrameOp.PUBLISH_PATCH;
    }


    private static String resolveBaseSnapshotHash(ChunkPlanDecision decision) {
        if (decision == null) {
            return "";
        }
        if (decision.decisionKind() == ChunkPlanDecisionKind.PUBLISH_FULL) {
            return decision.currentPayloadHash();
        }
        return decision.knownSnapshotHash();
    }

    private static int resolveOriginalEncodedBytes(ChunkPlanDecision decision) {
        if (decision == null || decision.decisionKind() == ChunkPlanDecisionKind.PUBLISH_REF) {
            return 0;
        }
        return Math.max(decision.encodedBytes(), 0);
    }

    private static boolean hasMatchingRuntimeFullSnapshot(
            ChunkRuntimeReferenceStore.RuntimeFullSnapshot runtimeFullSnapshot,
            ChunkHotspotFrame frame
    ) {
        return runtimeFullSnapshot != null
                && frame != null
                && frame.coordinate() != null
                && frame.coordinate().present()
                && runtimeFullSnapshot.coordinate() != null
                && runtimeFullSnapshot.coordinate().present()
                && runtimeFullSnapshot.coordinate().chunkX() == frame.coordinate().chunkX()
                && runtimeFullSnapshot.coordinate().chunkZ() == frame.coordinate().chunkZ()
                && runtimeFullSnapshot.fullSnapshotVersion() == frame.fullSnapshotVersion()
                && runtimeFullSnapshot.payloadHash() != null
                && runtimeFullSnapshot.payloadHash().equals(frame.baseSnapshotHash());
    }

    private static long incrementOutboundFrameCount(ChunkHotspotFrameOp operation) {
        if (operation == ChunkHotspotFrameOp.PUBLISH_REF) {
            return OUTBOUND_REF_FRAME_COUNT.incrementAndGet();
        }
        if (operation == ChunkHotspotFrameOp.PUBLISH_PATCH) {
            return OUTBOUND_PATCH_FRAME_COUNT.incrementAndGet();
        }
        if (operation == ChunkHotspotFrameOp.ACK) {
            return OUTBOUND_ACK_FRAME_COUNT.incrementAndGet();
        }
        if (operation == ChunkHotspotFrameOp.NACK) {
            return OUTBOUND_NACK_FRAME_COUNT.incrementAndGet();
        }
        if (operation == ChunkHotspotFrameOp.INVALIDATE) {
            return OUTBOUND_INVALIDATE_FRAME_COUNT.incrementAndGet();
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

    private static void logInboundControlFrame(
            ChannelHandlerContext context,
            byte[] packetBytes,
            ChunkHotspotFrame frame,
            AtomicLong inboundCounter
    ) {
        ChunkHotspotStats.recordInboundFrame(frame, 0, packetBytes == null ? 0 : packetBytes.length);
        ChunkHotspotVerifyHooks.flushCurrentReport();
        long frameCount = inboundCounter.incrementAndGet();
        if (!shouldLogSample(frameCount)) {
            return;
        }

        Bandwidthoptimizer.LOGGER.info(
                "[ChunkTransport][Control][Recv] channel={}, op={}, count={}, epoch={}, chunk={}, fullVersion={}, payloadHash={}, reason={}",
                readChannelId(context),
                frame.operation().logName(),
                frameCount,
                frame.epoch(),
                frame.coordinate().logText(),
                frame.fullSnapshotVersion(),
                shortenHash(frame.payloadHash()),
                frame.reason()
        );
    }

    private static boolean shouldLogSample(long frameCount) {
        return frameCount <= 5L || frameCount % 100L == 0L;
    }

    private static String readChannelId(ChannelHandlerContext context) {
        if (context == null) {
            return "<null>";
        }
        return context.channel().id().asLongText();
    }

    private static String shortenHash(String hashHex) {
        if (hashHex == null || hashHex.isBlank()) {
            return "<none>";
        }
        return hashHex.length() <= 12 ? hashHex : hashHex.substring(0, 12);
    }

    private record RuntimeChunkTransportDecision(
            ChunkHotspotFrameOp operation,
            ChunkHotspotFrame frame,
            byte[] transportPayloadBytes
    ) {
        private RuntimeChunkTransportDecision {
            transportPayloadBytes = transportPayloadBytes == null ? new byte[0] : transportPayloadBytes.clone();
        }

        private byte[] copyTransportPayloadBytes() {
            return this.transportPayloadBytes.clone();
        }
    }

    private record RuntimeChunkPlanningResult(
            RuntimeChunkTransportDecision transportDecision,
            String bypassReason
    ) {
        private RuntimeChunkPlanningResult {
            bypassReason = bypassReason == null ? "" : bypassReason;
        }
    }

    public record OutboundChunkEncodeResult(
            boolean chunkPacketCandidate,
            boolean chunkProtocolApplied,
            ChunkHotspotFrameOp operation,
            String traceReason,
            byte[] encodedPacketBytes
    ) {
        public OutboundChunkEncodeResult {
            traceReason = traceReason == null ? "" : traceReason;
            encodedPacketBytes = encodedPacketBytes == null ? null : encodedPacketBytes.clone();
        }

        private static OutboundChunkEncodeResult bypass(boolean chunkPacketCandidate, String traceReason) {
            return new OutboundChunkEncodeResult(chunkPacketCandidate, false, null, traceReason, null);
        }

        private static OutboundChunkEncodeResult applied(
                ChunkHotspotFrameOp operation,
                String traceReason,
                byte[] encodedPacketBytes
        ) {
            return new OutboundChunkEncodeResult(true, true, operation, traceReason, encodedPacketBytes);
        }

        public byte[] copyEncodedPacketBytes() {
            return this.encodedPacketBytes == null ? null : this.encodedPacketBytes.clone();
        }
    }
}
