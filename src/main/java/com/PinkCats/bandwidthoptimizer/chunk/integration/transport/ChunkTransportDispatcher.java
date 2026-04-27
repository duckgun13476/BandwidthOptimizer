package com.PinkCats.bandwidthoptimizer.chunk.integration.transport;

import com.PinkCats.bandwidthoptimizer.Bandwidthoptimizer;
import com.PinkCats.bandwidthoptimizer.chunk.budget.ChunkClientTrimmedFullBaseStore;
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
import com.PinkCats.bandwidthoptimizer.experient.ExperientChunkHotspotClientBudgetTrimDiagnostic;
import com.PinkCats.bandwidthoptimizer.experient.ExperientChunkHotspotOldEpochDataFrameDiagnostic;
import com.PinkCats.bandwidthoptimizer.experient.ExperientChunkHotspotOldEpochInvalidateDiagnostic;
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
    private static final AtomicLong INBOUND_BARRIER_FRAME_COUNT = new AtomicLong();
    private static final AtomicLong INBOUND_BARRIER_ACK_FRAME_COUNT = new AtomicLong();
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

        ChunkPeerStateSnapshot peerSnapshot = ChunkPeerStateManager.snapshotOutboundChannel(context);
        if (!hasActiveRuntimeScope(peerSnapshot)) {
            return null;
        }

        ChunkSnapshotFingerprint fingerprint = ChunkSnapshotFingerprintService.fingerprintOutboundPacket(originalPacketBytes);
        long scopeId = peerSnapshot.epoch();
        ChunkPeerChunkStateSnapshot knownChunkSnapshot =
                ChunkPeerStateManager.snapshotOutboundChunk(context, scopeId, descriptor.coordinate());
        ChunkShadowSnapshot localChunkSnapshot =
                ChunkShadowSnapshotManager.snapshotChunk(readChannelId(context), scopeId, descriptor.coordinate());
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
        ExperientChunkHotspotOldEpochDataFrameDiagnostic.maybeScheduleOldEpochFullReplay(
                context,
                runtimeDecision.frame(),
                envelopePayloadBytes
        );
        ExperientChunkHotspotClientBudgetTrimDiagnostic.maybeRememberReplayCandidate(
                context,
                runtimeDecision.frame(),
                envelopePayloadBytes
        );
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

        ChunkPeerStateSnapshot peerSnapshot = ChunkPeerStateManager.snapshotOutboundChannel(context);
        if (!hasActiveRuntimeScope(peerSnapshot)) {
            return OutboundChunkEncodeResult.bypass(true, "missing_bound_chunk_scope");
        }

        ChunkSnapshotFingerprint fingerprint = ChunkSnapshotFingerprintService.fingerprintOutboundPacket(originalPacketBytes);
        if (fingerprint == null) {
            return OutboundChunkEncodeResult.bypass(true, "missing_snapshot_fingerprint");
        }

        long scopeId = peerSnapshot.epoch();
        ChunkPeerChunkStateSnapshot knownChunkSnapshot =
                ChunkPeerStateManager.snapshotOutboundChunk(context, scopeId, descriptor.coordinate());
        ChunkShadowSnapshot localChunkSnapshot =
                ChunkShadowSnapshotManager.snapshotChunk(readChannelId(context), scopeId, descriptor.coordinate());
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
        ExperientChunkHotspotOldEpochDataFrameDiagnostic.maybeScheduleOldEpochFullReplay(
                context,
                runtimeDecision.frame(),
                runtimeDecision.copyTransportPayloadBytes()
        );
        ExperientChunkHotspotClientBudgetTrimDiagnostic.maybeRememberReplayCandidate(
                context,
                runtimeDecision.frame(),
                runtimeDecision.copyTransportPayloadBytes()
        );
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
        if (isRuntimeChunkDataFrame(envelope.frame())) {
            ChunkTransportBoundaryController.InboundRuntimeFrameDecision inboundFrameDecision =
                    ChunkTransportBoundaryController.beginInboundRuntimeFrame(context, envelope.frame().epoch());
            logDiagnosticInboundRuntimeFrame(context, envelope.frame(), inboundFrameDecision);
            if (!inboundFrameDecision.allowed()) {
                logIgnoredInboundRuntimeFrame(context, envelope.frame(), inboundFrameDecision.reason());
                return ChunkInboundDecodeResult.consumeControlFrame();
            }
        }
        if (envelope.frame().operation() == ChunkHotspotFrameOp.PUBLISH_FULL) {
            byte[] restoredPacketBytes = envelope.copyOriginalPacketBytes();
            observeInboundSnapshot(context, envelope.frame(), restoredPacketBytes);
            ChunkRuntimeReferenceStore.storePacketBytes(
                    readChannelId(context),
                    envelope.frame().payloadHash(),
                    restoredPacketBytes
            );
            ChunkRuntimeReferenceStore.storeFullSnapshot(readChannelId(context), envelope.frame());
            if (!ExperientChunkHotspotAckDelayController.maybeDelayAck(context, envelope.frame(), "runtime_full_received")) {
                ChunkTransportControlFrameSender.sendAck(context, envelope.frame(), "runtime_full_received");
            }
            ExperientChunkHotspotOldEpochInvalidateDiagnostic.maybeScheduleOldEpochInvalidate(context, envelope.frame());
            logInboundFrame(context, packetBytes, envelope, restoredPacketBytes, INBOUND_FULL_FRAME_COUNT);
            return ChunkInboundDecodeResult.passthrough(restoredPacketBytes);
        }

        if (envelope.frame().operation() == ChunkHotspotFrameOp.PUBLISH_REF) {
            String channelId = readChannelId(context);
            byte[] restoredPacketBytes = ChunkShadowSnapshotManager.materializeFullChunkPacket(
                    channelId,
                    envelope.frame().epoch(),
                    envelope.frame().coordinate(),
                    envelope.frame().fullSnapshotVersion(),
                    envelope.frame().baseSnapshotHash()
            );
            boolean restoredFromSnapshot = restoredPacketBytes != null;
            ChunkRuntimeReferenceStore.RuntimeFullSnapshot runtimeFullSnapshot = null;
            if (!restoredFromSnapshot) {
                runtimeFullSnapshot = ChunkRuntimeReferenceStore.findFullSnapshot(
                        channelId,
                        envelope.frame().epoch(),
                        envelope.frame().coordinate()
                );
                restoredPacketBytes = ChunkRuntimeReferenceStore.findPacketBytes(
                        channelId,
                        envelope.frame().payloadHash()
                );
            }
            if (restoredPacketBytes == null || (!restoredFromSnapshot && !hasMatchingRuntimeFullSnapshot(runtimeFullSnapshot, envelope.frame()))) {
                String trimmedBudgetIgnoreReason = resolveTrimmedBudgetIgnoreReason(context, envelope.frame(), "runtime_ref_missing_base");
                if (!trimmedBudgetIgnoreReason.isBlank()) {
                    logTrimmedBudgetSuppressedRuntimeFrame(context, envelope.frame(), trimmedBudgetIgnoreReason);
                    return ChunkInboundDecodeResult.consumeControlFrame();
                }
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
            ChunkRuntimeReferenceStore.storeFullSnapshot(channelId, envelope.frame());
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

        if (envelope.frame().operation() == ChunkHotspotFrameOp.BARRIER) {
            ChunkTransportControlFrameSender.sendBarrierAck(context, envelope.frame(), "runtime_boundary_barrier_received");
            logInboundControlFrame(context, packetBytes, envelope.frame(), INBOUND_BARRIER_FRAME_COUNT);
            return ChunkInboundDecodeResult.consumeControlFrame();
        }

        if (envelope.frame().operation() == ChunkHotspotFrameOp.BARRIER_ACK) {
            ChunkTransportBoundaryController.acknowledgeOutboundBarrier(context, envelope.frame());
            logInboundControlFrame(context, packetBytes, envelope.frame(), INBOUND_BARRIER_ACK_FRAME_COUNT);
            return ChunkInboundDecodeResult.consumeControlFrame();
        }

        if (envelope.frame().operation() == ChunkHotspotFrameOp.ACK) {
            ExperientChunkHotspotOldEpochDataFrameDiagnostic.maybeReplayAfterNewerClientEpoch(context, envelope.frame());
            ChunkPeerStateManager.acknowledgeOutboundChunk(context, envelope.frame());
            logInboundControlFrame(context, packetBytes, envelope.frame(), INBOUND_ACK_FRAME_COUNT);
            return ChunkInboundDecodeResult.consumeControlFrame();
        }

        if (envelope.frame().operation() == ChunkHotspotFrameOp.NACK) {
            ExperientChunkHotspotOldEpochDataFrameDiagnostic.maybeReplayAfterNewerClientEpoch(context, envelope.frame());
            ChunkPeerChunkStateSnapshot currentChunkSnapshot =
                    ChunkPeerStateManager.snapshotOutboundChunk(context, envelope.frame().epoch(), envelope.frame().coordinate());
            if (!matchesCurrentReceiverBaseControlFrame(currentChunkSnapshot, envelope.frame())) {
                logIgnoredReceiverControlFrame("Nack", context, envelope.frame(), currentChunkSnapshot);
                logInboundControlFrame(context, packetBytes, envelope.frame(), INBOUND_NACK_FRAME_COUNT);
                return ChunkInboundDecodeResult.consumeControlFrame();
            }
            ChunkPeerStateManager.negativeAcknowledgeOutboundChunk(context, envelope.frame());
            ChunkTransportControlFrameSender.sendInvalidate(context, envelope.frame(), "runtime_nack_received");
            logInboundControlFrame(context, packetBytes, envelope.frame(), INBOUND_NACK_FRAME_COUNT);
            return ChunkInboundDecodeResult.consumeControlFrame();
        }

        if (envelope.frame().operation() == ChunkHotspotFrameOp.INVALIDATE) {
            ExperientChunkHotspotOldEpochDataFrameDiagnostic.maybeReplayAfterNewerClientEpoch(context, envelope.frame());
            ChunkPeerChunkStateSnapshot currentChunkSnapshot =
                    ChunkPeerStateManager.snapshotOutboundChunk(context, envelope.frame().epoch(), envelope.frame().coordinate());
            if (!matchesCurrentReceiverBaseControlFrame(currentChunkSnapshot, envelope.frame())) {
                logIgnoredReceiverControlFrame("Invalidate", context, envelope.frame(), currentChunkSnapshot);
                logInboundControlFrame(context, packetBytes, envelope.frame(), INBOUND_INVALIDATE_FRAME_COUNT);
                return ChunkInboundDecodeResult.consumeControlFrame();
            }
            logOldEpochInvalidateDiagnosticBefore(context, envelope.frame(), currentChunkSnapshot);
            ExperientChunkHotspotClientBudgetTrimDiagnostic.maybeReplayAfterClientBudgetInvalidate(context, envelope.frame());
            ChunkPeerStateManager.invalidateOutboundChunk(context, envelope.frame());
            ChunkRuntimeReferenceStore.invalidateFullSnapshot(readChannelId(context), envelope.frame().epoch(), envelope.frame().coordinate());
            ChunkShadowSnapshotManager.invalidateChunk(readChannelId(context), envelope.frame().epoch(), envelope.frame().coordinate());
            logOldEpochInvalidateDiagnosticAfter(context, envelope.frame());
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

    private static boolean hasActiveRuntimeScope(ChunkPeerStateSnapshot peerSnapshot) {
        return peerSnapshot != null && peerSnapshot.epoch() > 0L;
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

        ChunkShadowSnapshot chunkSnapshot = ChunkShadowSnapshotManager.snapshotChunk(channelId, envelope.frame().epoch(), envelope.frame().coordinate());
        if (!hasMatchingSnapshotFullBase(chunkSnapshot, envelope.frame())) {
            String trimmedBudgetIgnoreReason = resolveTrimmedBudgetIgnoreReason(context, envelope.frame(), "runtime_patch_missing_full_base");
            if (!trimmedBudgetIgnoreReason.isBlank()) {
                logTrimmedBudgetSuppressedRuntimeFrame(context, envelope.frame(), trimmedBudgetIgnoreReason);
                return null;
            }
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


    private static String resolveTrimmedBudgetIgnoreReason(
            ChannelHandlerContext context,
            ChunkHotspotFrame frame,
            String missingReason
    ) {
        ChunkClientTrimmedFullBaseStore.TrimmedFullBaseTombstone tombstone =
                ChunkClientTrimmedFullBaseStore.findMatchingTrimmedFullBase(readChannelId(context), frame);
        if (tombstone == null) {
            return "";
        }
        return (missingReason == null || missingReason.isBlank()
                ? "client_cache_budget_trim_pending_invalidate"
                : "client_cache_budget_trim_pending_invalidate_" + missingReason)
                + "::"
                + tombstone.reason();
    }


    private static boolean matchesCurrentReceiverBaseControlFrame(
            ChunkPeerChunkStateSnapshot chunkSnapshot,
            ChunkHotspotFrame frame
    ) {
        String expectedFullBaseHash = resolveReceiverControlFrameFullBaseHash(frame);
        return chunkSnapshot != null
                && frame != null
                && chunkSnapshot.knownSnapshotPublished()
                && chunkSnapshot.fullSnapshotVersion() > 0L
                && chunkSnapshot.fullSnapshotVersion() == frame.fullSnapshotVersion()
                && chunkSnapshot.knownSnapshotHash() != null
                && !chunkSnapshot.knownSnapshotHash().isBlank()
                && expectedFullBaseHash != null
                && !expectedFullBaseHash.isBlank()
                && chunkSnapshot.knownSnapshotHash().equals(expectedFullBaseHash);
    }


    private static String resolveReceiverControlFrameFullBaseHash(ChunkHotspotFrame frame) {
        if (frame == null) {
            return "";
        }
        if (frame.baseSnapshotHash() != null && !frame.baseSnapshotHash().isBlank()) {
            return frame.baseSnapshotHash();
        }
        return frame.payloadHash() == null ? "" : frame.payloadHash();
    }


    private static void logIgnoredReceiverControlFrame(
            String label,
            ChannelHandlerContext context,
            ChunkHotspotFrame frame,
            ChunkPeerChunkStateSnapshot currentChunkSnapshot
    ) {
        Bandwidthoptimizer.LOGGER.info(
                "[ChunkTransport][Control][Ignore] action={}, channel={}, op={}, epoch={}, chunk={}, fullVersion={}, baseHash={}, payloadHash={}, state={}",
                label,
                context == null ? "<none>" : readChannelId(context),
                frame == null || frame.operation() == null ? "<unknown>" : frame.operation().logName(),
                frame == null ? 0L : frame.epoch(),
                frame == null || frame.coordinate() == null ? "<unknown>" : frame.coordinate().logText(),
                frame == null ? 0L : frame.fullSnapshotVersion(),
                frame == null ? "<none>" : shortenHash(frame.baseSnapshotHash()),
                frame == null ? "<none>" : shortenHash(frame.payloadHash()),
                currentChunkSnapshot == null ? "<missing>" : currentChunkSnapshot.summaryText()
        );
    }


    private static void logOldEpochInvalidateDiagnosticBefore(
            ChannelHandlerContext context,
            ChunkHotspotFrame frame,
            ChunkPeerChunkStateSnapshot targetEpochSnapshot
    ) {
        if (!isOldEpochInvalidateDiagnostic(frame)) {
            return;
        }

        ChunkPeerStateSnapshot channelSnapshot = ChunkPeerStateManager.snapshotOutboundChannel(context);
        long currentEpoch = channelSnapshot == null ? 0L : channelSnapshot.epoch();
        ChunkPeerChunkStateSnapshot currentEpochSnapshot = ChunkPeerStateManager.snapshotOutboundChunk(
                context,
                currentEpoch,
                frame.coordinate()
        );
        Bandwidthoptimizer.LOGGER.info(
                "[ChunkTransport][Diag][OldEpochInvalidate][Before] channel={}, currentEpoch={}, frameEpoch={}, chunk={}, currentScopeState={}, targetScopeState={}",
                readChannelId(context),
                currentEpoch,
                frame == null ? 0L : frame.epoch(),
                frame == null || frame.coordinate() == null ? "<unknown>" : frame.coordinate().logText(),
                currentEpochSnapshot == null ? "<missing>" : currentEpochSnapshot.summaryText(),
                targetEpochSnapshot == null ? "<missing>" : targetEpochSnapshot.summaryText()
        );
    }


    private static void logOldEpochInvalidateDiagnosticAfter(
            ChannelHandlerContext context,
            ChunkHotspotFrame frame
    ) {
        if (!isOldEpochInvalidateDiagnostic(frame)) {
            return;
        }

        ChunkPeerStateSnapshot channelSnapshot = ChunkPeerStateManager.snapshotOutboundChannel(context);
        long currentEpoch = channelSnapshot == null ? 0L : channelSnapshot.epoch();
        ChunkPeerChunkStateSnapshot currentEpochSnapshot = ChunkPeerStateManager.snapshotOutboundChunk(
                context,
                currentEpoch,
                frame.coordinate()
        );
        ChunkPeerChunkStateSnapshot targetEpochSnapshot = ChunkPeerStateManager.snapshotOutboundChunk(
                context,
                frame.epoch(),
                frame.coordinate()
        );
        Bandwidthoptimizer.LOGGER.info(
                "[ChunkTransport][Diag][OldEpochInvalidate][After] channel={}, currentEpoch={}, frameEpoch={}, chunk={}, currentScopeState={}, targetScopeState={}",
                readChannelId(context),
                currentEpoch,
                frame == null ? 0L : frame.epoch(),
                frame == null || frame.coordinate() == null ? "<unknown>" : frame.coordinate().logText(),
                currentEpochSnapshot == null ? "<missing>" : currentEpochSnapshot.summaryText(),
                targetEpochSnapshot == null ? "<missing>" : targetEpochSnapshot.summaryText()
        );
    }

    private static boolean isOldEpochInvalidateDiagnostic(ChunkHotspotFrame frame) {
        return frame != null && "experient_diagnostic_old_epoch_invalidate".equals(frame.reason());
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
                "[ChunkTransport][Control][Recv] channel={}, op={}, count={}, epoch={}, observedPackets={}, chunk={}, fullVersion={}, payloadHash={}, reason={}",
                readChannelId(context),
                frame.operation().logName(),
                frameCount,
                frame.epoch(),
                frame.observedPacketCount(),
                frame.coordinate().logText(),
                frame.fullSnapshotVersion(),
                shortenHash(frame.payloadHash()),
                frame.reason()
        );
    }

    private static void logIgnoredInboundRuntimeFrame(
            ChannelHandlerContext context,
            ChunkHotspotFrame frame,
            String reason
    ) {
        Bandwidthoptimizer.LOGGER.info(
                "[ChunkTransport][Data][Ignore] channel={}, op={}, epoch={}, chunk={}, fullVersion={}, baseHash={}, payloadHash={}, reason={}",
                readChannelId(context),
                frame == null || frame.operation() == null ? "<unknown>" : frame.operation().logName(),
                frame == null ? 0L : frame.epoch(),
                frame == null || frame.coordinate() == null ? "<unknown>" : frame.coordinate().logText(),
                frame == null ? 0L : frame.fullSnapshotVersion(),
                frame == null ? "<none>" : shortenHash(frame.baseSnapshotHash()),
                frame == null ? "<none>" : shortenHash(frame.payloadHash()),
                reason == null ? "" : reason
        );
    }

    private static void logTrimmedBudgetSuppressedRuntimeFrame(
            ChannelHandlerContext context,
            ChunkHotspotFrame frame,
            String reason
    ) {
        logIgnoredInboundRuntimeFrame(context, frame, reason);
        Bandwidthoptimizer.LOGGER.info(
                "[ChunkTransport][Budget][Ignore] channel={}, op={}, epoch={}, chunk={}, fullVersion={}, baseHash={}, payloadHash={}, reason={}",
                readChannelId(context),
                frame == null || frame.operation() == null ? "<unknown>" : frame.operation().logName(),
                frame == null ? 0L : frame.epoch(),
                frame == null || frame.coordinate() == null ? "<unknown>" : frame.coordinate().logText(),
                frame == null ? 0L : frame.fullSnapshotVersion(),
                frame == null ? "<none>" : shortenHash(frame.baseSnapshotHash()),
                frame == null ? "<none>" : shortenHash(frame.payloadHash()),
                reason == null ? "" : reason
        );
    }

    private static void logDiagnosticInboundRuntimeFrame(
            ChannelHandlerContext context,
            ChunkHotspotFrame frame,
            ChunkTransportBoundaryController.InboundRuntimeFrameDecision decision
    ) {
        if (!isOldEpochFullReplayDiagnostic(frame) || decision == null) {
            return;
        }
        Bandwidthoptimizer.LOGGER.info(
                "[ChunkTransport][Data][Diag] channel={}, op={}, epoch={}, chunk={}, fullVersion={}, payloadHash={}, allowed={}, currentEpoch={}, reason={}",
                readChannelId(context),
                frame.operation() == null ? "<unknown>" : frame.operation().logName(),
                frame.epoch(),
                frame.coordinate() == null ? "<unknown>" : frame.coordinate().logText(),
                frame.fullSnapshotVersion(),
                shortenHash(frame.payloadHash()),
                decision.allowed(),
                decision.currentEpoch(),
                decision.reason()
        );
    }

    private static boolean isRuntimeChunkDataFrame(ChunkHotspotFrame frame) {
        if (frame == null || frame.operation() == null) {
            return false;
        }
        return frame.operation() == ChunkHotspotFrameOp.PUBLISH_FULL
                || frame.operation() == ChunkHotspotFrameOp.PUBLISH_REF
                || frame.operation() == ChunkHotspotFrameOp.PUBLISH_PATCH;
    }

    private static boolean isOldEpochFullReplayDiagnostic(ChunkHotspotFrame frame) {
        return frame != null && "experient_diagnostic_old_epoch_full_replay".equals(frame.reason());
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
