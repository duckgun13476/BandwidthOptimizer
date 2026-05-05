package com.PinkCats.bandwidthoptimizer.chunk.integration.transport;

import com.PinkCats.bandwidthoptimizer.Bandwidthoptimizer;
import com.PinkCats.bandwidthoptimizer.chunk.budget.ChunkClientTrimmedFullBaseStore;
import com.PinkCats.bandwidthoptimizer.chunk.classify.ChunkHotspotKind;
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
import com.PinkCats.bandwidthoptimizer.debug.DebugRuntimeConfig;
import io.netty.channel.ChannelHandlerContext;
import net.minecraft.network.protocol.Packet;
import net.minecraft.network.protocol.game.ClientGamePacketListener;

import java.util.concurrent.atomic.AtomicLong;

public final class ChunkTransportDispatcher {

    private static final String WATCH_BOUNDARY_REUSE_PROBE_MISS_REASON =
            "runtime_ref_missing_base_after_watch_boundary_reuse_probe";
    private static final String WATCH_BOUNDARY_REUSE_PROBE_ACK_REASON =
            "runtime_ref_reused_after_watch_boundary";
    private static final String WATCH_BOUNDARY_REUSE_PROBE_FALLBACK_FULL_REASON =
            "fallback_full_after_watch_boundary_reuse_probe_miss";
    private static final String WATCH_BOUNDARY_REUSE_PROBE_FALLBACK_INVALIDATE_REASON =
            "runtime_watch_boundary_reuse_probe_fallback_unavailable";
    private static final String WATCH_BOUNDARY_REFRESH_PATCH_ACK_REASON =
            "runtime_patch_reused_after_watch_boundary";
    private static final String WATCH_BOUNDARY_REFRESH_PATCH_FALLBACK_FULL_REASON =
            "fallback_full_after_watch_boundary_refresh_patch_miss";
    private static final String WATCH_BOUNDARY_REFRESH_PATCH_FALLBACK_INVALIDATE_REASON =
            "runtime_watch_boundary_refresh_patch_fallback_unavailable";
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
                resolvePlanningChunkSnapshot(context, scopeId, descriptor, fingerprint);
        ChunkPatchBuilder.ChunkPatchBuildResult patchBuildResult = buildOutboundPatchCandidate(
                context,
                scopeId,
                descriptor,
                packet,
                originalPacketBytes,
                fingerprint,
                knownChunkSnapshot
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
        if (shouldLogDiagnose() && shouldLogSample(frameCount)) {
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
        rememberWatchBoundaryFallbackFrame(context, runtimeDecision, originalPacketBytes);
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
                resolvePlanningChunkSnapshot(context, scopeId, descriptor, fingerprint);
        ChunkPatchBuilder.ChunkPatchBuildResult patchBuildResult = buildOutboundPatchCandidate(
                context,
                scopeId,
                descriptor,
                packet,
                originalPacketBytes,
                fingerprint,
                knownChunkSnapshot
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
        if (shouldLogDiagnose() && shouldLogSample(frameCount)) {
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
        rememberWatchBoundaryFallbackFrame(context, runtimeDecision, originalPacketBytes);
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
            ChunkTransportControlFrameSender.sendAck(context, envelope.frame(), "runtime_full_received");
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
                        envelope.frame().baseSnapshotHash()
                );
            }
            if (restoredPacketBytes == null
                    || (!restoredFromSnapshot
                    && !hasMatchingRuntimeFullSnapshot(runtimeFullSnapshot, envelope.frame(), restoredPacketBytes)
                    && !canUseContentAddressedFullPacket(envelope.frame(), restoredPacketBytes))) {
                String trimmedBudgetIgnoreReason = resolveTrimmedBudgetIgnoreReason(context, envelope.frame(), "runtime_ref_missing_base");
                if (!trimmedBudgetIgnoreReason.isBlank()) {
                    logTrimmedBudgetSuppressedRuntimeFrame(context, envelope.frame(), trimmedBudgetIgnoreReason);
                    return ChunkInboundDecodeResult.consumeControlFrame();
                }
                ChunkTransportControlFrameSender.sendNack(
                        context,
                        envelope.frame(),
                        resolveRefMissingBaseReason(envelope.frame())
                );
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
            if (isWatchBoundaryReuseProbeFrame(envelope.frame())) {
                ChunkTransportControlFrameSender.sendAck(context, envelope.frame(), WATCH_BOUNDARY_REUSE_PROBE_ACK_REASON);
            }
            logInboundFrame(context, packetBytes, envelope, restoredPacketBytes, INBOUND_REF_FRAME_COUNT);
            return ChunkInboundDecodeResult.passthrough(restoredPacketBytes);
        }

        if (envelope.frame().operation() == ChunkHotspotFrameOp.PUBLISH_PATCH) {
            byte[] restoredPacketBytes = tryRestorePatchedPacket(context, envelope);
            if (restoredPacketBytes == null) {
                return ChunkInboundDecodeResult.consumeControlFrame();
            }
            observeInboundSnapshot(context, envelope.frame(), restoredPacketBytes);
            acknowledgeWatchBoundaryRefreshPatchIfNeeded(context, envelope.frame(), restoredPacketBytes);
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
            if (!matchesCurrentChannelEpoch(context, envelope.frame())) {
                logIgnoredReceiverControlFrame("Ack", context, envelope.frame(), null);
                logInboundControlFrame(context, packetBytes, envelope.frame(), INBOUND_ACK_FRAME_COUNT);
                return ChunkInboundDecodeResult.consumeControlFrame();
            }
            ChunkPeerStateManager.acknowledgeOutboundChunk(context, envelope.frame());
            ChunkWatchBoundaryReusePendingStore.clearPendingFull(readChannelId(context), envelope.frame());
            logInboundControlFrame(context, packetBytes, envelope.frame(), INBOUND_ACK_FRAME_COUNT);
            return ChunkInboundDecodeResult.consumeControlFrame();
        }

        if (envelope.frame().operation() == ChunkHotspotFrameOp.NACK) {
            if (!matchesCurrentChannelEpoch(context, envelope.frame())) {
                logIgnoredReceiverControlFrame("Nack", context, envelope.frame(), null);
                logInboundControlFrame(context, packetBytes, envelope.frame(), INBOUND_NACK_FRAME_COUNT);
                return ChunkInboundDecodeResult.consumeControlFrame();
            }
            ChunkPeerChunkStateSnapshot currentChunkSnapshot =
                    ChunkPeerStateManager.snapshotOutboundChunk(context, envelope.frame().epoch(), envelope.frame().coordinate());
            if (!matchesCurrentReceiverBaseControlFrame(currentChunkSnapshot, envelope.frame())) {
                logIgnoredReceiverControlFrame("Nack", context, envelope.frame(), currentChunkSnapshot);
                logInboundControlFrame(context, packetBytes, envelope.frame(), INBOUND_NACK_FRAME_COUNT);
                return ChunkInboundDecodeResult.consumeControlFrame();
            }
            if (tryHandleWatchBoundaryFallbackNack(context, envelope.frame())) {
                logInboundControlFrame(context, packetBytes, envelope.frame(), INBOUND_NACK_FRAME_COUNT);
                return ChunkInboundDecodeResult.consumeControlFrame();
            }
            ChunkPeerStateManager.negativeAcknowledgeOutboundChunk(context, envelope.frame());
            ChunkTransportControlFrameSender.sendInvalidate(context, envelope.frame(), "runtime_nack_received");
            logInboundControlFrame(context, packetBytes, envelope.frame(), INBOUND_NACK_FRAME_COUNT);
            return ChunkInboundDecodeResult.consumeControlFrame();
        }

        if (envelope.frame().operation() == ChunkHotspotFrameOp.INVALIDATE) {
            if (!matchesCurrentChannelEpoch(context, envelope.frame())) {
                logIgnoredReceiverControlFrame("Invalidate", context, envelope.frame(), null);
                logInboundControlFrame(context, packetBytes, envelope.frame(), INBOUND_INVALIDATE_FRAME_COUNT);
                return ChunkInboundDecodeResult.consumeControlFrame();
            }
            ChunkPeerChunkStateSnapshot currentChunkSnapshot =
                    ChunkPeerStateManager.snapshotOutboundChunk(context, envelope.frame().epoch(), envelope.frame().coordinate());
            if (!matchesCurrentReceiverBaseControlFrame(currentChunkSnapshot, envelope.frame())) {
                logIgnoredReceiverControlFrame("Invalidate", context, envelope.frame(), currentChunkSnapshot);
                logInboundControlFrame(context, packetBytes, envelope.frame(), INBOUND_INVALIDATE_FRAME_COUNT);
                return ChunkInboundDecodeResult.consumeControlFrame();
            }
            ChunkWatchBoundaryReusePendingStore.clearPendingFull(readChannelId(context), envelope.frame());
            ChunkPeerStateManager.invalidateOutboundChunk(context, envelope.frame());
            ChunkRuntimeReferenceStore.invalidateFullSnapshot(readChannelId(context), envelope.frame().epoch(), envelope.frame().coordinate());
            ChunkShadowSnapshotManager.invalidateChunk(readChannelId(context), envelope.frame().epoch(), envelope.frame().coordinate());
            logInboundControlFrame(context, packetBytes, envelope.frame(), INBOUND_INVALIDATE_FRAME_COUNT);
            return ChunkInboundDecodeResult.consumeControlFrame();
        }

        throw new IllegalStateException("Unsupported chunk transport operation in runtime MVP: " + envelope.frame().operation().logName());
    }

    private static ChunkPatchBuilder.ChunkPatchBuildResult buildOutboundPatchCandidate(
            ChannelHandlerContext context,
            long scopeId,
            ChunkPacketDescriptor descriptor,
            Packet<?> packet,
            byte[] originalPacketBytes,
            ChunkSnapshotFingerprint fingerprint,
            ChunkPeerChunkStateSnapshot knownChunkSnapshot
    ) {
        ChunkShadowSnapshot localChunkSnapshot =
                ChunkShadowSnapshotManager.snapshotChunk(readChannelId(context), scopeId, descriptor.coordinate());
        ChunkPatchBuilder.ChunkPatchBuildResult primaryPatchBuildResult = ChunkPatchBuilder.buildPatchFromSnapshot(
                localChunkSnapshot,
                descriptor,
                packet,
                originalPacketBytes,
                fingerprint
        );
        ChunkPatchBuilder.ChunkPatchBuildResult storedFullFallbackPatchBuildResult =
                buildWatchBoundaryRefreshPatchFromStoredFullBase(
                        context,
                        scopeId,
                        descriptor,
                        originalPacketBytes,
                        fingerprint,
                        knownChunkSnapshot
                );
        return preferSmallerBeneficialPatchResult(primaryPatchBuildResult, storedFullFallbackPatchBuildResult);
    }


    private static ChunkPeerChunkStateSnapshot resolvePlanningChunkSnapshot(
            ChannelHandlerContext context,
            long scopeId,
            ChunkPacketDescriptor descriptor,
            ChunkSnapshotFingerprint fingerprint
    ) {
        if (descriptor == null || descriptor.coordinate() == null || !descriptor.coordinate().present()) {
            return null;
        }

        ChunkPeerChunkStateSnapshot currentScopeSnapshot =
                ChunkPeerStateManager.snapshotOutboundChunk(context, scopeId, descriptor.coordinate());
        if (currentScopeSnapshot != null
                || !canUseCrossScopeFullChunkReference(descriptor, fingerprint)) {
            return currentScopeSnapshot;
        }

        ChunkPeerChunkStateSnapshot latestKnownSnapshot =
                ChunkPeerStateManager.snapshotLatestKnownOutboundChunkAcrossScopes(context, descriptor.coordinate());
        if (latestKnownSnapshot == null
                || latestKnownSnapshot.epoch() == scopeId
                || latestKnownSnapshot.knownSnapshotHash() == null
                || latestKnownSnapshot.knownSnapshotHash().isBlank()) {
            return null;
        }
        return markCrossScopeSnapshotWaitingForReplay(latestKnownSnapshot);
    }

    // After teleport chunk will use ref not full
    private static boolean canUseCrossScopeFullChunkReference(
            ChunkPacketDescriptor descriptor,
            ChunkSnapshotFingerprint fingerprint
    ) {
        return descriptor != null
                && descriptor.hotspotKind() == ChunkHotspotKind.FULL_CHUNK
                && descriptor.coordinate() != null
                && descriptor.coordinate().present()
                && fingerprint != null
                && fingerprint.hashHex() != null
                && !fingerprint.hashHex().isBlank();
    }

    private static ChunkPeerChunkStateSnapshot markCrossScopeSnapshotWaitingForReplay(
            ChunkPeerChunkStateSnapshot snapshot
    ) {
        if (snapshot == null) {
            return null;
        }
        return new ChunkPeerChunkStateSnapshot(
                snapshot.chunkKey(),
                snapshot.epoch(),
                snapshot.knownSnapshotPublished(),
                false,
                true,
                snapshot.totalObservedPacketCount(),
                snapshot.fullSnapshotVersion(),
                0L,
                snapshot.mutationVersion(),
                snapshot.lightLaneVersion(),
                snapshot.sectionBlocksLaneVersion(),
                snapshot.blockLaneVersion(),
                snapshot.blockEntityLaneVersion(),
                snapshot.deltaPacketCountSinceFullSnapshot(),
                snapshot.deltaBytesSinceFullSnapshot(),
                snapshot.lastHotspotKind(),
                snapshot.lastLaneKind(),
                snapshot.knownSnapshotHash(),
                snapshot.knownSnapshotShortHash(),
                "",
                snapshot.lastPayloadHash(),
                snapshot.lastPayloadShortHash(),
                snapshot.lastEncodedBytes(),
                snapshot.lastFullSnapshotEncodedBytes(),
                snapshot.lastObservedChannelPacketCount(),
                snapshot.lastObservedAtMillis(),
                snapshot.lastAcknowledgedAtMillis(),
                snapshot.lastNegativeAckAtMillis(),
                snapshot.lastInvalidatedAtMillis()
        );
    }

    private static ChunkPatchBuilder.ChunkPatchBuildResult buildWatchBoundaryRefreshPatchFromStoredFullBase(
            ChannelHandlerContext context,
            long scopeId,
            ChunkPacketDescriptor descriptor,
            byte[] originalPacketBytes,
            ChunkSnapshotFingerprint fingerprint,
            ChunkPeerChunkStateSnapshot knownChunkSnapshot
    ) {
        if (!shouldTryStoredFullBasePatch(context, descriptor, originalPacketBytes, fingerprint, knownChunkSnapshot)) {
            return ChunkPatchBuilder.ChunkPatchBuildResult.unavailable("stored_full_base_patch_not_applicable");
        }

        long baseScopeId = resolveStoredFullBaseScopeId(scopeId, knownChunkSnapshot);
        byte[] storedFullBasePacketBytes = ChunkShadowSnapshotManager.materializeFullChunkPacket(
                readChannelId(context),
                baseScopeId,
                descriptor.coordinate(),
                knownChunkSnapshot.fullSnapshotVersion(),
                knownChunkSnapshot.knownSnapshotHash()
        );
        if (storedFullBasePacketBytes == null || storedFullBasePacketBytes.length == 0) {
            return ChunkPatchBuilder.ChunkPatchBuildResult.unavailable("stored_full_base_packet_missing");
        }

        ChunkLanePacketSnapshot storedFullBasePacketSnapshot = new ChunkLanePacketSnapshot(
                descriptor.protocolName(),
                descriptor.packetClassName(),
                descriptor.hotspotKind(),
                descriptor.laneKind(),
                "full",
                knownChunkSnapshot.fullSnapshotVersion(),
                knownChunkSnapshot.fullSnapshotVersion(),
                knownChunkSnapshot.knownSnapshotHash(),
                shortenHash(knownChunkSnapshot.knownSnapshotHash()),
                storedFullBasePacketBytes.length,
                0L,
                storedFullBasePacketBytes
        );
        return ChunkPatchBuilder.buildPatch(
                descriptor,
                "full",
                storedFullBasePacketSnapshot,
                originalPacketBytes
        );
    }

    private static boolean shouldTryStoredFullBasePatch(
            ChannelHandlerContext context,
            ChunkPacketDescriptor descriptor,
            byte[] originalPacketBytes,
            ChunkSnapshotFingerprint fingerprint,
            ChunkPeerChunkStateSnapshot knownChunkSnapshot
    ) {
        return context != null
                && descriptor != null
                && descriptor.hotspotKind() == ChunkHotspotKind.FULL_CHUNK
                && descriptor.coordinate() != null
                && descriptor.coordinate().present()
                && originalPacketBytes != null
                && originalPacketBytes.length > 0
                && fingerprint != null
                && knownChunkSnapshot != null
                && knownChunkSnapshot.knownSnapshotPublished()
                && knownChunkSnapshot.fullReplayRequiredBeforeDelta()
                && knownChunkSnapshot.fullSnapshotVersion() > 0L
                && knownChunkSnapshot.knownSnapshotHash() != null
                && !knownChunkSnapshot.knownSnapshotHash().isBlank()
                && !knownChunkSnapshot.knownSnapshotHash().equals(fingerprint.hashHex());
    }

    private static long resolveStoredFullBaseScopeId(long currentScopeId, ChunkPeerChunkStateSnapshot knownChunkSnapshot) {
        if (knownChunkSnapshot == null || knownChunkSnapshot.epoch() <= 0L) {
            return Math.max(currentScopeId, 0L);
        }
        return knownChunkSnapshot.epoch();
    }

    private static ChunkPatchBuilder.ChunkPatchBuildResult preferSmallerBeneficialPatchResult(
            ChunkPatchBuilder.ChunkPatchBuildResult primaryPatchBuildResult,
            ChunkPatchBuilder.ChunkPatchBuildResult fallbackPatchBuildResult
    ) {
        boolean primaryAvailable = primaryPatchBuildResult != null
                && primaryPatchBuildResult.patch() != null
                && primaryPatchBuildResult.beneficial();
        boolean fallbackAvailable = fallbackPatchBuildResult != null
                && fallbackPatchBuildResult.patch() != null
                && fallbackPatchBuildResult.beneficial();
        if (primaryAvailable && fallbackAvailable) {
            return primaryPatchBuildResult.encodedPatchBytesLength() <= fallbackPatchBuildResult.encodedPatchBytesLength()
                    ? primaryPatchBuildResult
                    : fallbackPatchBuildResult;
        }
        if (primaryAvailable) {
            return primaryPatchBuildResult;
        }
        if (fallbackAvailable) {
            return fallbackPatchBuildResult;
        }
        if (primaryPatchBuildResult != null && primaryPatchBuildResult.patch() != null) {
            return primaryPatchBuildResult;
        }
        if (fallbackPatchBuildResult != null && fallbackPatchBuildResult.patch() != null) {
            return fallbackPatchBuildResult;
        }
        return primaryPatchBuildResult == null
                ? ChunkPatchBuilder.ChunkPatchBuildResult.unavailable("patch_not_available")
                : primaryPatchBuildResult;
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
        decision = normalizeCrossScopeDecisionForCurrentScope(peerSnapshot, knownChunkSnapshot, decision);

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
        decision = normalizeCrossScopeDecisionForCurrentScope(peerSnapshot, knownChunkSnapshot, decision);

        return new RuntimeChunkPlanningResult(
                new RuntimeChunkTransportDecision(
                        mapOperation(decision.decisionKind()),
                        buildRuntimeFrame(descriptor, peerSnapshot, decision),
                        resolveTransportPayloadBytes(decision, patchBuildResult, originalPacketBytes)
                ),
                ""
        );
    }

    private static ChunkPlanDecision normalizeCrossScopeDecisionForCurrentScope(
            ChunkPeerStateSnapshot peerSnapshot,
            ChunkPeerChunkStateSnapshot knownChunkSnapshot,
            ChunkPlanDecision decision
    ) {
        if (peerSnapshot == null
                || knownChunkSnapshot == null
                || decision == null
                || peerSnapshot.epoch() <= 0L
                || knownChunkSnapshot.epoch() == peerSnapshot.epoch()) {
            return decision;
        }
        return new ChunkPlanDecision(
                decision.decisionKind(),
                decision.reason(),
                decision.knownSnapshotPublished(),
                1L,
                decision.mutationVersion(),
                decision.laneVersion(),
                decision.deltaPacketCountSinceFullSnapshot(),
                decision.deltaBytesSinceFullSnapshot(),
                decision.reusedGlobalSnapshot(),
                decision.globalObservationCount(),
                decision.knownSnapshotHash(),
                decision.knownSnapshotShortHash(),
                decision.currentPayloadHash(),
                decision.currentPayloadShortHash(),
                decision.laneName(),
                decision.chunkText(),
                decision.encodedBytes(),
                decision.bypassBytes(),
                decision.fullTransportBytes(),
                decision.refTransportBytes(),
                decision.patchTransportBytes(),
                decision.selectedTransportBytes()
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
        boolean matchingShadowFullBase = hasMatchingSnapshotFullBase(chunkSnapshot, envelope.frame());
        ChunkRuntimeReferenceStore.RuntimeFullSnapshot runtimeFullSnapshot = null;
        byte[] runtimeFullBasePacketBytes = null;
        if (!matchingShadowFullBase) {
            runtimeFullSnapshot = ChunkRuntimeReferenceStore.findFullSnapshot(
                    channelId,
                    envelope.frame().epoch(),
                    envelope.frame().coordinate()
            );
            runtimeFullBasePacketBytes = ChunkRuntimeReferenceStore.findPacketBytes(
                    channelId,
                    envelope.frame().baseSnapshotHash()
            );
        }
        if (!matchingShadowFullBase
                && !hasMatchingRuntimeFullSnapshot(runtimeFullSnapshot, envelope.frame(), runtimeFullBasePacketBytes)
                && !canUseContentAddressedPatchBase(envelope.frame(), runtimeFullBasePacketBytes)) {
            String trimmedBudgetIgnoreReason = resolveTrimmedBudgetIgnoreReason(context, envelope.frame(), "runtime_patch_missing_full_base");
            if (!trimmedBudgetIgnoreReason.isBlank()) {
                logTrimmedBudgetSuppressedRuntimeFrame(context, envelope.frame(), trimmedBudgetIgnoreReason);
                return null;
            }
            ChunkTransportControlFrameSender.sendNack(context, envelope.frame(), "runtime_patch_missing_full_base");
            return null;
        }

        ChunkLanePacketSnapshot basePacketSnapshot = matchingShadowFullBase
                ? resolvePatchBasePacketSnapshot(chunkSnapshot, envelope.frame(), chunkPatch)
                : null;
        byte[] basePacketBytes = basePacketSnapshot == null ? new byte[0] : basePacketSnapshot.copyOriginalPacketBytes();
        if (!chunkPatch.basePayloadHash().isBlank()
                && basePacketSnapshot == null
                && canUseRuntimeFullPacketAsPatchBase(envelope.frame(), runtimeFullBasePacketBytes)) {
            basePacketBytes = runtimeFullBasePacketBytes;
        }
        if (!chunkPatch.basePayloadHash().isBlank() && basePacketBytes.length == 0) {
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

    private static void acknowledgeWatchBoundaryRefreshPatchIfNeeded(
            ChannelHandlerContext context,
            ChunkHotspotFrame frame,
            byte[] restoredPacketBytes
    ) {
        if (context == null
                || frame == null
                || restoredPacketBytes == null
                || !isWatchBoundaryRefreshPatchFrame(frame)) {
            return;
        }

        String channelId = readChannelId(context);
        ChunkRuntimeReferenceStore.storePacketBytes(channelId, frame.payloadHash(), restoredPacketBytes);
        ChunkRuntimeReferenceStore.storeFullSnapshot(channelId, frame);
        ChunkTransportControlFrameSender.sendAck(context, frame, WATCH_BOUNDARY_REFRESH_PATCH_ACK_REASON);
    }

    private static Packet<ClientGamePacketListener> decodeClientboundPlayPacket(byte[] restoredPacketBytes) {
        return ClientboundPlayPacketCodec.decodePacket(restoredPacketBytes);
    }


    private static boolean hasMatchingSnapshotFullBase(ChunkShadowSnapshot chunkSnapshot, ChunkHotspotFrame frame) {
        return chunkSnapshot != null
                && frame != null
                && chunkSnapshot.hasFullSnapshot()
                && chunkSnapshot.fullSnapshotVersion() == resolveExpectedPatchBaseFullSnapshotVersion(frame)
                && chunkSnapshot.fullSnapshotHash() != null
                && chunkSnapshot.fullSnapshotHash().equals(frame.baseSnapshotHash());
    }

    private static long resolveExpectedPatchBaseFullSnapshotVersion(ChunkHotspotFrame frame) {
        if (isWatchBoundaryRefreshPatchFrame(frame)
                && frame != null
                && frame.hotspotKind() == ChunkHotspotKind.FULL_CHUNK
                && hasDistinctTargetFullSnapshot(frame)
                && frame.fullSnapshotVersion() > 1L) {
            return frame.fullSnapshotVersion() - 1L;
        }
        return frame == null ? 0L : frame.fullSnapshotVersion();
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
            ChunkHotspotFrame frame,
            byte[] runtimeFullBasePacketBytes
    ) {
        return runtimeFullSnapshot != null
                && frame != null
                && frame.coordinate() != null
                && frame.coordinate().present()
                && runtimeFullSnapshot.coordinate() != null
                && runtimeFullSnapshot.coordinate().present()
                && runtimeFullSnapshot.coordinate().chunkX() == frame.coordinate().chunkX()
                && runtimeFullSnapshot.coordinate().chunkZ() == frame.coordinate().chunkZ()
                && runtimeFullSnapshot.fullSnapshotVersion() == resolveExpectedPatchBaseFullSnapshotVersion(frame)
                && runtimeFullSnapshot.payloadHash() != null
                && runtimeFullSnapshot.payloadHash().equals(frame.baseSnapshotHash())
                && canUseRuntimeFullPacketAsPatchBase(frame, runtimeFullBasePacketBytes);
    }

    private static boolean canUseContentAddressedFullPacket(
            ChunkHotspotFrame frame,
            byte[] runtimeFullBasePacketBytes
    ) {
        if (frame == null
                || frame.operation() != ChunkHotspotFrameOp.PUBLISH_REF
                || frame.hotspotKind() != ChunkHotspotKind.FULL_CHUNK
                || frame.baseSnapshotHash() == null
                || frame.baseSnapshotHash().isBlank()
                || frame.payloadHash() == null
                || !frame.payloadHash().equals(frame.baseSnapshotHash())
                || runtimeFullBasePacketBytes == null
                || runtimeFullBasePacketBytes.length <= 0) {
            return false;
        }

        ChunkSnapshotFingerprint fingerprint =
                ChunkSnapshotFingerprintService.fingerprintOutboundPacket(runtimeFullBasePacketBytes);
        return fingerprint != null
                && fingerprint.hashHex() != null
                && fingerprint.hashHex().equals(frame.baseSnapshotHash());
    }

    private static boolean canUseContentAddressedPatchBase(
            ChunkHotspotFrame frame,
            byte[] runtimeFullBasePacketBytes
    ) {
        if (frame == null
                || frame.operation() != ChunkHotspotFrameOp.PUBLISH_PATCH
                || frame.hotspotKind() != ChunkHotspotKind.FULL_CHUNK
                || frame.baseSnapshotHash() == null
                || frame.baseSnapshotHash().isBlank()
                || runtimeFullBasePacketBytes == null
                || runtimeFullBasePacketBytes.length <= 0) {
            return false;
        }

        ChunkSnapshotFingerprint fingerprint =
                ChunkSnapshotFingerprintService.fingerprintOutboundPacket(runtimeFullBasePacketBytes);
        return fingerprint != null
                && fingerprint.hashHex() != null
                && fingerprint.hashHex().equals(frame.baseSnapshotHash());
    }

    private static boolean canUseRuntimeFullPacketAsPatchBase(
            ChunkHotspotFrame frame,
            byte[] runtimeFullBasePacketBytes
    ) {
        return frame != null
                && frame.hotspotKind() == ChunkHotspotKind.FULL_CHUNK
                && runtimeFullBasePacketBytes != null
                && runtimeFullBasePacketBytes.length > 0;
    }

    private static void rememberWatchBoundaryFallbackFrame(
            ChannelHandlerContext context,
            RuntimeChunkTransportDecision runtimeDecision,
            byte[] originalPacketBytes
    ) {
        if (context == null
                || runtimeDecision == null
                || runtimeDecision.frame() == null
                || !shouldRememberWatchBoundaryFallbackFrame(runtimeDecision.frame())) {
            return;
        }
        ChunkWatchBoundaryReusePendingStore.rememberProbe(
                readChannelId(context),
                runtimeDecision.frame(),
                originalPacketBytes
        );
    }

    private static boolean isWatchBoundaryReuseProbeFrame(ChunkHotspotFrame frame) {
        return frame != null
                && frame.operation() == ChunkHotspotFrameOp.PUBLISH_REF
                && ChunkTransportPlanner.WATCH_BOUNDARY_REUSE_PROBE_REASON.equals(frame.reason());
    }

    private static boolean isWatchBoundaryRefreshPatchFrame(ChunkHotspotFrame frame) {
        return frame != null
                && frame.operation() == ChunkHotspotFrameOp.PUBLISH_PATCH
                && ChunkTransportPlanner.WATCH_BOUNDARY_REFRESH_PATCH_REASON.equals(frame.reason());
    }

    private static boolean shouldRememberWatchBoundaryFallbackFrame(ChunkHotspotFrame frame) {
        return isWatchBoundaryReuseProbeFrame(frame) || isWatchBoundaryRefreshPatchFrame(frame);
    }

    private static boolean isWatchBoundaryReuseProbeMiss(ChunkHotspotFrame frame) {
        return frame != null
                && frame.operation() == ChunkHotspotFrameOp.NACK
                && WATCH_BOUNDARY_REUSE_PROBE_MISS_REASON.equals(frame.reason());
    }

    private static boolean isWatchBoundaryRefreshPatchMiss(ChunkHotspotFrame frame) {
        return frame != null
                && frame.operation() == ChunkHotspotFrameOp.NACK
                && frame.hotspotKind() == ChunkHotspotKind.FULL_CHUNK
                && hasDistinctTargetFullSnapshot(frame);
    }

    private static boolean tryHandleWatchBoundaryFallbackNack(
            ChannelHandlerContext context,
            ChunkHotspotFrame frame
    ) {
        if (!isWatchBoundaryReuseProbeMiss(frame) && !isWatchBoundaryRefreshPatchMiss(frame)) {
            return false;
        }

        ChunkPeerStateManager.negativeAcknowledgeOutboundChunk(context, frame);
        ChunkWatchBoundaryReusePendingStore.PendingFullReplay pendingReplay =
                ChunkWatchBoundaryReusePendingStore.takePendingFull(readChannelId(context), frame);
        if (pendingReplay != null
                && ChunkTransportControlFrameSender.sendReplayFullFrame(
                context.channel(),
                pendingReplay.probeFrame(),
                pendingReplay.copyOriginalPacketBytes(),
                resolveWatchBoundaryFallbackFullReason(frame)
        )) {
            return true;
        }

        ChunkTransportControlFrameSender.sendInvalidate(
                context,
                frame,
                resolveWatchBoundaryFallbackInvalidateReason(frame)
        );
        return true;
    }

    private static String resolveWatchBoundaryFallbackFullReason(ChunkHotspotFrame frame) {
        return isWatchBoundaryRefreshPatchMiss(frame)
                ? WATCH_BOUNDARY_REFRESH_PATCH_FALLBACK_FULL_REASON
                : WATCH_BOUNDARY_REUSE_PROBE_FALLBACK_FULL_REASON;
    }

    private static String resolveWatchBoundaryFallbackInvalidateReason(ChunkHotspotFrame frame) {
        return isWatchBoundaryRefreshPatchMiss(frame)
                ? WATCH_BOUNDARY_REFRESH_PATCH_FALLBACK_INVALIDATE_REASON
                : WATCH_BOUNDARY_REUSE_PROBE_FALLBACK_INVALIDATE_REASON;
    }

    private static String resolveRefMissingBaseReason(ChunkHotspotFrame frame) {
        return isWatchBoundaryReuseProbeFrame(frame)
                ? WATCH_BOUNDARY_REUSE_PROBE_MISS_REASON
                : "runtime_ref_missing_base";
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
        String expectedFullBaseHash = resolveReceiverControlFrameMatchHash(frame);
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

    private static boolean matchesCurrentChannelEpoch(ChannelHandlerContext context, ChunkHotspotFrame frame) {
        if (context == null || frame == null || frame.epoch() <= 0L) {
            return false;
        }

        ChunkPeerStateSnapshot channelSnapshot = ChunkPeerStateManager.snapshotOutboundChannel(context);
        return channelSnapshot != null
                && channelSnapshot.epoch() > 0L
                && channelSnapshot.epoch() == frame.epoch();
    }


    private static String resolveReceiverControlFrameMatchHash(ChunkHotspotFrame frame) {
        if (frame == null) {
            return "";
        }
        if (hasDistinctTargetFullSnapshot(frame)) {
            return frame.payloadHash() == null ? "" : frame.payloadHash();
        }
        if (frame.baseSnapshotHash() != null && !frame.baseSnapshotHash().isBlank()) {
            return frame.baseSnapshotHash();
        }
        return frame.payloadHash() == null ? "" : frame.payloadHash();
    }

    private static boolean hasDistinctTargetFullSnapshot(ChunkHotspotFrame frame) {
        return frame != null
                && frame.baseSnapshotHash() != null
                && !frame.baseSnapshotHash().isBlank()
                && frame.payloadHash() != null
                && !frame.payloadHash().isBlank()
                && !frame.baseSnapshotHash().equals(frame.payloadHash());
    }


    private static void logIgnoredReceiverControlFrame(
            String label,
            ChannelHandlerContext context,
            ChunkHotspotFrame frame,
            ChunkPeerChunkStateSnapshot currentChunkSnapshot
    ) {
        if (!shouldLogDiagnose()) {
            return;
        }
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
        if (!shouldLogDiagnose() || !shouldLogSample(frameCount)) {
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
        if (!shouldLogDiagnose() || !shouldLogSample(frameCount)) {
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
        if (!shouldLogDiagnose()) {
            return;
        }
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
        if (!shouldLogDiagnose()) {
            return;
        }
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

    private static boolean isRuntimeChunkDataFrame(ChunkHotspotFrame frame) {
        if (frame == null || frame.operation() == null) {
            return false;
        }
        return frame.operation() == ChunkHotspotFrameOp.PUBLISH_FULL
                || frame.operation() == ChunkHotspotFrameOp.PUBLISH_REF
                || frame.operation() == ChunkHotspotFrameOp.PUBLISH_PATCH;
    }

    private static boolean shouldLogSample(long frameCount) {
        return frameCount <= 5L || frameCount % 100L == 0L;
    }

    private static boolean shouldLogDiagnose() {
        return DebugRuntimeConfig.isDiagnoseEnabled();
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
