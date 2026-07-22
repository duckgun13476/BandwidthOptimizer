package com.PinkCats.bandwidthoptimizer.chunk.integration.transport;

import com.PinkCats.bandwidthoptimizer.debug.DiagnosticLog;

import com.PinkCats.bandwidthoptimizer.Bandwidthoptimizer;
import com.PinkCats.bandwidthoptimizer.chunk.budget.ChunkClientCacheBudgetManager;
import com.PinkCats.bandwidthoptimizer.chunk.budget.ChunkClientTrimmedFullBaseStore;
import com.PinkCats.bandwidthoptimizer.chunk.classify.ChunkHotspotKind;
import com.PinkCats.bandwidthoptimizer.chunk.classify.packet.ChunkPacketClassifier;
import com.PinkCats.bandwidthoptimizer.chunk.classify.packet.ChunkPacketDescriptor;
import com.PinkCats.bandwidthoptimizer.chunk.debug.ChunkLoadDelayProbe;
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
import com.PinkCats.bandwidthoptimizer.chunk.persistent.ChunkLocalCacheReuseStats;
import com.PinkCats.bandwidthoptimizer.chunk.persistent.ChunkPersistentClientCache;
import com.PinkCats.bandwidthoptimizer.chunk.persistent.ChunkPersistentClientCacheManifestBatchCodec;
import com.PinkCats.bandwidthoptimizer.chunk.persistent.ChunkPersistentManifestGate;
import com.PinkCats.bandwidthoptimizer.chunk.persistent.ChunkPersistentServerScope;
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
import com.PinkCats.bandwidthoptimizer.chunk.store.blob.ChunkBlobHandle;
import com.PinkCats.bandwidthoptimizer.chunk.store.global.ChunkGlobalSnapshotStore;
import com.PinkCats.bandwidthoptimizer.chunk.verify.ChunkHotspotStats;
import com.PinkCats.bandwidthoptimizer.chunk.verify.ChunkHotspotVerifyHooks;
import com.PinkCats.bandwidthoptimizer.chunk.verify.ChunkServerOfflineReuseStats;
import com.PinkCats.bandwidthoptimizer.integration.sable.SableChunkSyncCompat;
import com.PinkCats.bandwidthoptimizer.debug.DiagnosticToolRegistry;
import com.PinkCats.bandwidthoptimizer.debug.HotpathCostProbe;
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
    private static final String RUNTIME_REF_FALLBACK_FULL_REASON =
            "fallback_full_after_runtime_ref_missing_base";
    private static final String RUNTIME_PATCH_FALLBACK_FULL_REASON =
            "fallback_full_after_runtime_patch_missing_full_base";
    private static final String RUNTIME_REF_FALLBACK_INVALIDATE_REASON =
            "runtime_ref_fallback_unavailable";
    private static final String RUNTIME_PATCH_FALLBACK_INVALIDATE_REASON =
            "runtime_patch_fallback_unavailable";
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
    private static final AtomicLong INBOUND_SERVER_CACHE_SCOPE_FRAME_COUNT = new AtomicLong();
    private static final AtomicLong INBOUND_CLIENT_CACHE_MANIFEST_FRAME_COUNT = new AtomicLong();

    private ChunkTransportDispatcher() {
    }

    // full/ref/patch -> chunk transport envelope.
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
            peerSnapshot = ChunkPeerStateManager.ensureOutboundChannelScope(context, "runtime_chunk_transport_missing_scope");
        }
        if (!hasActiveRuntimeScope(peerSnapshot)) {
            return null;
        }

        ChunkSnapshotFingerprint fingerprint = ChunkSnapshotFingerprintService.fingerprintOutboundPacket(originalPacketBytes);
        long scopeId = peerSnapshot.epoch();
        ChunkPeerChunkStateSnapshot knownChunkSnapshot =
                resolvePlanningChunkSnapshot(context, scopeId, descriptor, fingerprint);
        ChunkPatchBuilder.ChunkPatchBuildResult patchBuildResult = resolveOutboundPatchCandidate(
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
        ChunkServerOfflineReuseStats.recordSent(
                readChannelId(context),
                runtimeDecision.frame(),
                originalPacketBytes == null ? 0 : originalPacketBytes.length,
                encodedEnvelopeBytes.length
        );
        ChunkHotspotVerifyHooks.flushCurrentReport();
        ChunkLoadDelayProbe.logServerEncode(
                context,
                runtimeDecision.frame(),
                originalPacketBytes == null ? 0 : originalPacketBytes.length,
                encodedEnvelopeBytes.length,
                runtimeDecision.decision()
        );
        long frameCount = incrementOutboundFrameCount(runtimeDecision.operation());
        if (shouldLogDiagnose() && shouldLogSample(frameCount)) {
            DiagnosticLog.info(DiagnosticToolRegistry.Tool.CHUNK_TRANSPORT_FRAMES, "event=wrap, channel={}, op={}, count={}, epoch={}, observedPackets={}, chunk={}, payloadBytes={}, envelopeBytes={}, payloadHash={}",
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
        rememberRecoverableFullChunkFrame(context, runtimeDecision, originalPacketBytes);
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

        long hotpathStartNanos = HotpathCostProbe.start();
        long classifyStartNanos = ChunkLoadDelayProbe.isEnabled() ? System.nanoTime() : 0L;
        ChunkPacketDescriptor descriptor = ChunkPacketClassifier.classifyOutboundPlayPacket(protocolName, packet);
        HotpathCostProbe.end("chunk.classify", hotpathStartNanos);
        ChunkLoadDelayProbe.logStage(
                context,
                null,
                "server",
                "outbound_classify",
                ChunkLoadDelayProbe.elapsedMillisSince(classifyStartNanos),
                originalPacketBytes == null ? 0 : originalPacketBytes.length,
                packet == null ? "<null>" : packet.getClass().getName()
        );
        boolean chunkPacketCandidate = shouldUseRuntimeChunkTransport(descriptor);
        if (!chunkPacketCandidate)
            return OutboundChunkEncodeResult.bypass(false, "descriptor_not_chunk_candidate");

        hotpathStartNanos = HotpathCostProbe.start();
        if (ChunkPersistentManifestGate.shouldWaitForManifest(context, descriptor)) {
            HotpathCostProbe.end("chunk.manifestGate", hotpathStartNanos);
            return OutboundChunkEncodeResult.bypass(true, ChunkPersistentManifestGate.WAIT_REASON);
        }
        HotpathCostProbe.end("chunk.manifestGate", hotpathStartNanos);

        hotpathStartNanos = HotpathCostProbe.start();
        boolean forceSableInitialSyncFull =
                SableChunkSyncCompat.shouldForceFullChunkTransport(context, descriptor);
        HotpathCostProbe.end("chunk.sableGate", hotpathStartNanos);

        hotpathStartNanos = HotpathCostProbe.start();
        ChunkTransportBoundaryController.ChunkTransportPermit transportPermit =
                ChunkTransportBoundaryController.permitChunkTransport(context, descriptor);
        HotpathCostProbe.end("chunk.boundaryPermit", hotpathStartNanos);
        if (!transportPermit.allowed()) {
            return OutboundChunkEncodeResult.bypass(true, transportPermit.reason());
        }

        hotpathStartNanos = HotpathCostProbe.start();
        long peerStartNanos = ChunkLoadDelayProbe.isEnabled() ? System.nanoTime() : 0L;
        ChunkPeerStateSnapshot peerSnapshot = ChunkPeerStateManager.snapshotOutboundChannel(context);
        if (!hasActiveRuntimeScope(peerSnapshot)) {
            peerSnapshot = ChunkPeerStateManager.ensureOutboundChannelScope(context, "runtime_chunk_transport_missing_scope");
        }
        HotpathCostProbe.end("chunk.peerScope", hotpathStartNanos);
        ChunkLoadDelayProbe.logStage(
                context,
                null,
                "server",
                "outbound_peer_scope",
                ChunkLoadDelayProbe.elapsedMillisSince(peerStartNanos),
                originalPacketBytes == null ? 0 : originalPacketBytes.length,
                "active=" + hasActiveRuntimeScope(peerSnapshot)
        );
        if (!hasActiveRuntimeScope(peerSnapshot)) {
            return OutboundChunkEncodeResult.bypass(true, "missing_bound_chunk_scope");
        }

        hotpathStartNanos = HotpathCostProbe.start();
        long fingerprintStartNanos = ChunkLoadDelayProbe.isEnabled() ? System.nanoTime() : 0L;
        ChunkSnapshotFingerprint fingerprint = ChunkSnapshotFingerprintService.fingerprintOutboundPacket(originalPacketBytes);
        HotpathCostProbe.end("chunk.fingerprint", hotpathStartNanos);
        ChunkLoadDelayProbe.logStage(
                context,
                null,
                "server",
                "outbound_fingerprint",
                ChunkLoadDelayProbe.elapsedMillisSince(fingerprintStartNanos),
                originalPacketBytes == null ? 0 : originalPacketBytes.length,
                fingerprint == null ? "fingerprint=false" : "fingerprint=true"
        );
        if (fingerprint == null) {
            return OutboundChunkEncodeResult.bypass(true, "missing_snapshot_fingerprint");
        }

        long scopeId = peerSnapshot.epoch();
        hotpathStartNanos = HotpathCostProbe.start();
        long snapshotStartNanos = ChunkLoadDelayProbe.isEnabled() ? System.nanoTime() : 0L;
        ChunkPeerChunkStateSnapshot knownChunkSnapshot = forceSableInitialSyncFull
                ? null
                : resolvePlanningChunkSnapshot(context, scopeId, descriptor, fingerprint);
        HotpathCostProbe.end("chunk.resolveSnapshot", hotpathStartNanos);
        ChunkLoadDelayProbe.logStage(
                context,
                null,
                "server",
                "outbound_resolve_known_snapshot",
                ChunkLoadDelayProbe.elapsedMillisSince(snapshotStartNanos),
                originalPacketBytes == null ? 0 : originalPacketBytes.length,
                knownChunkSnapshot == null ? "known=false" : "known=true"
        );
        hotpathStartNanos = HotpathCostProbe.start();
        long patchStartNanos = ChunkLoadDelayProbe.isEnabled() ? System.nanoTime() : 0L;
        ChunkPatchBuilder.ChunkPatchBuildResult patchBuildResult = forceSableInitialSyncFull
                ? ChunkPatchBuilder.ChunkPatchBuildResult.unavailable("sable_initial_sync_force_full")
                : resolveOutboundPatchCandidate(
                        context,
                        scopeId,
                        descriptor,
                        packet,
                        originalPacketBytes,
                        fingerprint,
                        knownChunkSnapshot
                );
        HotpathCostProbe.end("chunk.patchCandidate", hotpathStartNanos);
        ChunkLoadDelayProbe.logStage(
                context,
                null,
                "server",
                "outbound_patch_candidate",
                ChunkLoadDelayProbe.elapsedMillisSince(patchStartNanos),
                originalPacketBytes == null ? 0 : originalPacketBytes.length,
                patchBuildResult == null ? "<null>" : patchBuildResult.reason()
        );
        hotpathStartNanos = HotpathCostProbe.start();
        long planStartNanos = ChunkLoadDelayProbe.isEnabled() ? System.nanoTime() : 0L;
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
        HotpathCostProbe.end("chunk.plan", hotpathStartNanos);
        ChunkLoadDelayProbe.logStage(
                context,
                runtimeDecision == null ? null : runtimeDecision.frame(),
                "server",
                "outbound_plan",
                ChunkLoadDelayProbe.elapsedMillisSince(planStartNanos),
                originalPacketBytes == null ? 0 : originalPacketBytes.length,
                runtimeDecision == null ? planningResult.bypassReason() : runtimeDecision.operation().logName()
        );
        if (runtimeDecision == null) {
            return OutboundChunkEncodeResult.bypass(true, planningResult.bypassReason());
        }

        hotpathStartNanos = HotpathCostProbe.start();
        long envelopeStartNanos = ChunkLoadDelayProbe.isEnabled() ? System.nanoTime() : 0L;
        byte[] encodedEnvelopeBytes = ChunkTransportEnvelopeCodec.encodeEnvelope(
                new ChunkTransportEnvelope(runtimeDecision.frame(), runtimeDecision.copyTransportPayloadBytes())
        );
        HotpathCostProbe.end("chunk.encodeEnvelope", hotpathStartNanos);
        ChunkLoadDelayProbe.logStage(
                context,
                runtimeDecision.frame(),
                "server",
                "outbound_encode_envelope",
                ChunkLoadDelayProbe.elapsedMillisSince(envelopeStartNanos),
                encodedEnvelopeBytes.length,
                ""
        );
        hotpathStartNanos = HotpathCostProbe.start();
        long statsStartNanos = ChunkLoadDelayProbe.isEnabled() ? System.nanoTime() : 0L;
        ChunkHotspotStats.recordOutboundFrame(
                runtimeDecision.frame(),
                originalPacketBytes == null ? 0 : originalPacketBytes.length,
                encodedEnvelopeBytes.length
        );
        ChunkServerOfflineReuseStats.recordSent(
                readChannelId(context),
                runtimeDecision.frame(),
                originalPacketBytes == null ? 0 : originalPacketBytes.length,
                encodedEnvelopeBytes.length
        );
        ChunkHotspotVerifyHooks.flushCurrentReport();
        HotpathCostProbe.end("chunk.statsFlush", hotpathStartNanos);
        ChunkLoadDelayProbe.logStage(
                context,
                runtimeDecision.frame(),
                "server",
                "outbound_stats_flush",
                ChunkLoadDelayProbe.elapsedMillisSince(statsStartNanos),
                encodedEnvelopeBytes.length,
                ""
        );
        long frameCount = incrementOutboundFrameCount(runtimeDecision.operation());
        hotpathStartNanos = HotpathCostProbe.start();
        if (shouldLogDiagnose() && shouldLogSample(frameCount)) {
            DiagnosticLog.info(DiagnosticToolRegistry.Tool.CHUNK_TRANSPORT_FRAMES, "event=wrap, channel={}, op={}, count={}, epoch={}, observedPackets={}, chunk={}, payloadBytes={}, envelopeBytes={}, payloadHash={}",
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
        HotpathCostProbe.end("chunk.diagnosticFrameLog", hotpathStartNanos);
        hotpathStartNanos = HotpathCostProbe.start();
        rememberRecoverableFullChunkFrame(context, runtimeDecision, originalPacketBytes);
        HotpathCostProbe.end("chunk.rememberRecoverableFull", hotpathStartNanos);
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

        long envelopeDecodeStartNanos = ChunkLoadDelayProbe.isEnabled() ? System.nanoTime() : 0L;
        ChunkTransportEnvelope envelope = ChunkTransportEnvelopeCodec.decodeEnvelope(packetBytes);
        ChunkLoadDelayProbe.logStage(
                context,
                envelope.frame(),
                "client",
                "inbound_decode_envelope",
                ChunkLoadDelayProbe.elapsedMillisSince(envelopeDecodeStartNanos),
                packetBytes == null ? 0 : packetBytes.length,
                ""
        );
        ChunkLoadDelayProbe.logClientEnvelopeDecode(context, envelope.frame(), packetBytes.length);
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
            long observeStartNanos = ChunkLoadDelayProbe.isEnabled() ? System.nanoTime() : 0L;
            observeInboundSnapshot(context, envelope.frame(), restoredPacketBytes);
            ChunkLoadDelayProbe.logStage(
                    context,
                    envelope.frame(),
                    "client",
                    "inbound_full_observe_snapshot",
                    ChunkLoadDelayProbe.elapsedMillisSince(observeStartNanos),
                    restoredPacketBytes.length,
                    ""
            );
            // Restored packets skip decoded observation, so persist full chunks from the transport frame.
            ChunkPersistentClientCache.storeInboundFullChunkAsync(
                    envelope.frame().protocolName(),
                    envelope.frame().epoch(),
                    envelope.frame().packetClassName(),
                    envelope.frame().hotspotKind(),
                    envelope.frame().laneKind(),
                    envelope.frame().coordinate(),
                    restoredPacketBytes,
                    "persistent_cache_from_restored_transport_full"
            );
            long runtimeStoreStartNanos = ChunkLoadDelayProbe.isEnabled() ? System.nanoTime() : 0L;
            ChunkRuntimeReferenceStore.storePacketBytes(
                    readChannelId(context),
                    envelope.frame().payloadHash(),
                    restoredPacketBytes
            );
            ChunkRuntimeReferenceStore.storeFullSnapshot(readChannelId(context), envelope.frame());
            ChunkTransportControlFrameSender.sendAck(context, envelope.frame(), "runtime_full_received");
            ChunkLoadDelayProbe.logStage(
                    context,
                    envelope.frame(),
                    "client",
                    "inbound_full_runtime_store_ack",
                    ChunkLoadDelayProbe.elapsedMillisSince(runtimeStoreStartNanos),
                    restoredPacketBytes.length,
                    ""
            );
            ChunkLoadDelayProbe.logClientRestored(
                    context,
                    envelope.frame(),
                    restoredPacketBytes.length,
                    "publish_full"
            );
            logInboundFrame(context, packetBytes, envelope, restoredPacketBytes, INBOUND_FULL_FRAME_COUNT);
            return ChunkInboundDecodeResult.passthrough(restoredPacketBytes);
        }

        if (envelope.frame().operation() == ChunkHotspotFrameOp.PUBLISH_REF) {
            String channelId = readChannelId(context);
            long shadowStartNanos = ChunkLoadDelayProbe.isEnabled() ? System.nanoTime() : 0L;
            byte[] restoredPacketBytes = ChunkShadowSnapshotManager.materializeFullChunkPacket(
                    channelId,
                    envelope.frame().epoch(),
                    envelope.frame().coordinate(),
                    envelope.frame().fullSnapshotVersion(),
                    envelope.frame().baseSnapshotHash()
            );
            ChunkLoadDelayProbe.logStage(
                    context,
                    envelope.frame(),
                    "client",
                    "inbound_ref_shadow_materialize",
                    ChunkLoadDelayProbe.elapsedMillisSince(shadowStartNanos),
                    restoredPacketBytes == null ? 0 : restoredPacketBytes.length,
                    "hit=" + (restoredPacketBytes != null)
            );
            boolean restoredFromSnapshot = restoredPacketBytes != null;
            ChunkLocalCacheReuseStats.ReuseSource reuseSource =
                    ChunkLocalCacheReuseStats.ReuseSource.TEMPORARY_RUNTIME_CACHE;
            ChunkRuntimeReferenceStore.RuntimeFullSnapshot runtimeFullSnapshot = null;
            boolean restoredFromPersistentCache = false;
            if (!restoredFromSnapshot) {
                long runtimeLookupStartNanos = ChunkLoadDelayProbe.isEnabled() ? System.nanoTime() : 0L;
                runtimeFullSnapshot = ChunkRuntimeReferenceStore.findFullSnapshot(
                        channelId,
                        envelope.frame().epoch(),
                        envelope.frame().coordinate()
                );
                restoredPacketBytes = ChunkRuntimeReferenceStore.findPacketBytes(
                        channelId,
                        envelope.frame().baseSnapshotHash()
                );
                ChunkLoadDelayProbe.logStage(
                        context,
                        envelope.frame(),
                        "client",
                        "inbound_ref_runtime_lookup",
                        ChunkLoadDelayProbe.elapsedMillisSince(runtimeLookupStartNanos),
                        restoredPacketBytes == null ? 0 : restoredPacketBytes.length,
                        "hit=" + (restoredPacketBytes != null)
                );
                if (restoredPacketBytes == null) {
                    long persistentFindStartNanos = ChunkLoadDelayProbe.isEnabled() ? System.nanoTime() : 0L;
                    restoredPacketBytes = ChunkPersistentClientCache.findLoadedPacketBytes(envelope.frame());
                    ChunkLoadDelayProbe.logStage(
                            context,
                            envelope.frame(),
                            "client",
                            "inbound_ref_loaded_snapshot_find",
                            ChunkLoadDelayProbe.elapsedMillisSince(persistentFindStartNanos),
                            restoredPacketBytes == null ? 0 : restoredPacketBytes.length,
                            "hit=" + (restoredPacketBytes != null)
                    );
                    if (restoredPacketBytes != null) {
                        reuseSource = ChunkLocalCacheReuseStats.ReuseSource.OFFLINE_PERSISTENT_CACHE;
                        restoredFromPersistentCache = true;
                        // Persistent manifest refs may not carry a base hash.
                        ChunkRuntimeReferenceStore.storePacketBytes(
                                channelId,
                                envelope.frame().payloadHash(),
                                restoredPacketBytes
                        );
                        ChunkRuntimeReferenceStore.storeFullSnapshot(channelId, envelope.frame());
                    }
                }
            }
            if (restoredPacketBytes == null
                    || (!restoredFromSnapshot
                    && !restoredFromPersistentCache
                    && !hasMatchingRuntimeFullSnapshot(runtimeFullSnapshot, envelope.frame(), restoredPacketBytes)
                    && !canUseContentAddressedFullPacket(envelope.frame(), restoredPacketBytes))) {
                String trimmedBudgetIgnoreReason = resolveTrimmedBudgetIgnoreReason(context, envelope.frame(), "runtime_ref_missing_base");
                if (!trimmedBudgetIgnoreReason.isBlank()) {
                    ChunkLoadDelayProbe.logClientRestoreMiss(context, envelope.frame(), trimmedBudgetIgnoreReason, false);
                    logTrimmedBudgetSuppressedRuntimeFrame(context, envelope.frame(), trimmedBudgetIgnoreReason);
                    return ChunkInboundDecodeResult.consumeControlFrame();
                }
                ChunkTransportControlFrameSender.sendNack(
                        context,
                        envelope.frame(),
                        resolveRefMissingBaseReason(envelope.frame())
                );
                ChunkLoadDelayProbe.logClientRestoreMiss(
                        context,
                        envelope.frame(),
                        resolveRefMissingBaseReason(envelope.frame()),
                        true
                );
                logInboundControlFrame(context, packetBytes, envelope.frame(), INBOUND_NACK_FRAME_COUNT);
                return ChunkInboundDecodeResult.consumeControlFrame();
            }
            long observeStartNanos = ChunkLoadDelayProbe.isEnabled() ? System.nanoTime() : 0L;
            observeInboundSnapshot(context, envelope.frame(), restoredPacketBytes);
            ChunkLoadDelayProbe.logStage(
                    context,
                    envelope.frame(),
                    "client",
                    "inbound_ref_observe_snapshot",
                    ChunkLoadDelayProbe.elapsedMillisSince(observeStartNanos),
                    restoredPacketBytes.length,
                    ""
            );
            ChunkLocalCacheReuseStats.recordReferenceReuse(
                    envelope.frame(),
                    restoredPacketBytes.length,
                    packetBytes == null ? 0 : packetBytes.length,
                    reuseSource
            );
            long storeStartNanos = ChunkLoadDelayProbe.isEnabled() ? System.nanoTime() : 0L;
            ChunkRuntimeReferenceStore.storePacketBytes(
                    channelId,
                    envelope.frame().payloadHash(),
                    restoredPacketBytes
            );
            ChunkRuntimeReferenceStore.storeFullSnapshot(channelId, envelope.frame());
            if (reuseSource == ChunkLocalCacheReuseStats.ReuseSource.OFFLINE_PERSISTENT_CACHE) {
                ChunkTransportControlFrameSender.sendAck(context, envelope.frame(), resolveReferenceOfflineReuseAckReason(envelope.frame()));
            } else if (isWatchBoundaryReuseProbeFrame(envelope.frame())) {
                ChunkTransportControlFrameSender.sendAck(context, envelope.frame(), WATCH_BOUNDARY_REUSE_PROBE_ACK_REASON);
            }
            ChunkLoadDelayProbe.logStage(
                    context,
                    envelope.frame(),
                    "client",
                    "inbound_ref_store_ack",
                    ChunkLoadDelayProbe.elapsedMillisSince(storeStartNanos),
                    restoredPacketBytes.length,
                    "source=" + reuseSource.name()
            );
            ChunkLoadDelayProbe.logClientRestored(
                    context,
                    envelope.frame(),
                    restoredPacketBytes.length,
                    restoredFromSnapshot ? "shadow_snapshot" : reuseSource.name()
            );
            logInboundFrame(context, packetBytes, envelope, restoredPacketBytes, INBOUND_REF_FRAME_COUNT);
            return ChunkInboundDecodeResult.passthrough(restoredPacketBytes);
        }

        if (envelope.frame().operation() == ChunkHotspotFrameOp.PUBLISH_PATCH) {
            long patchRestoreStartNanos = ChunkLoadDelayProbe.isEnabled() ? System.nanoTime() : 0L;
            byte[] restoredPacketBytes = tryRestorePatchedPacket(context, envelope);
            ChunkLoadDelayProbe.logStage(
                    context,
                    envelope.frame(),
                    "client",
                    "inbound_patch_restore",
                    ChunkLoadDelayProbe.elapsedMillisSince(patchRestoreStartNanos),
                    restoredPacketBytes == null ? 0 : restoredPacketBytes.length,
                    "hit=" + (restoredPacketBytes != null)
            );
            if (restoredPacketBytes == null) {
                return ChunkInboundDecodeResult.consumeControlFrame();
            }
            long observeStartNanos = ChunkLoadDelayProbe.isEnabled() ? System.nanoTime() : 0L;
            observeInboundSnapshot(context, envelope.frame(), restoredPacketBytes);
            ChunkLoadDelayProbe.logStage(
                    context,
                    envelope.frame(),
                    "client",
                    "inbound_patch_observe_snapshot",
                    ChunkLoadDelayProbe.elapsedMillisSince(observeStartNanos),
                    restoredPacketBytes.length,
                    ""
            );
            ChunkLocalCacheReuseStats.ReuseSource patchReuseSource = isPersistentManifestPatchFrame(envelope.frame())
                    ? ChunkLocalCacheReuseStats.ReuseSource.OFFLINE_PERSISTENT_CACHE
                    : ChunkLocalCacheReuseStats.ReuseSource.TEMPORARY_RUNTIME_CACHE;
            ChunkLocalCacheReuseStats.recordPatchReuse(
                    envelope.frame(),
                    restoredPacketBytes.length,
                    packetBytes == null ? 0 : packetBytes.length,
                    patchReuseSource
            );
            long storeStartNanos = ChunkLoadDelayProbe.isEnabled() ? System.nanoTime() : 0L;
            acknowledgeFullChunkPatchIfNeeded(context, envelope.frame(), restoredPacketBytes);
            ChunkLoadDelayProbe.logStage(
                    context,
                    envelope.frame(),
                    "client",
                    "inbound_patch_store_ack",
                    ChunkLoadDelayProbe.elapsedMillisSince(storeStartNanos),
                    restoredPacketBytes.length,
                    "source=" + patchReuseSource.name()
            );
            ChunkLoadDelayProbe.logClientRestored(
                    context,
                    envelope.frame(),
                    restoredPacketBytes.length,
                    patchReuseSource.name()
            );
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

        if (envelope.frame().operation() == ChunkHotspotFrameOp.SERVER_CACHE_SCOPE) {
            ChunkPersistentClientCache.applyServerCacheScope(context == null ? null : context.channel(), envelope.frame());
            ChunkPersistentClientCache.sendManifestOnceAsync(
                    context == null ? null : context.channel(),
                    "persistent_client_cache_after_server_scope"
            );
            logInboundControlFrame(context, packetBytes, envelope.frame(), INBOUND_SERVER_CACHE_SCOPE_FRAME_COUNT);
            return ChunkInboundDecodeResult.consumeControlFrame();
        }

        if (envelope.frame().operation() == ChunkHotspotFrameOp.ACK) {
            if (!matchesCurrentChannelEpoch(context, envelope.frame())) {
                logIgnoredReceiverControlFrame("Ack", context, envelope.frame(), null);
                logInboundControlFrame(context, packetBytes, envelope.frame(), INBOUND_ACK_FRAME_COUNT);
                return ChunkInboundDecodeResult.consumeControlFrame();
            }
            ChunkPeerStateManager.acknowledgeOutboundChunk(context, envelope.frame());
            ChunkServerOfflineReuseStats.recordConfirmed(readChannelId(context), envelope.frame());
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
            if (tryHandleRecoverableFullChunkNack(context, envelope.frame())) {
                logInboundControlFrame(context, packetBytes, envelope.frame(), INBOUND_NACK_FRAME_COUNT);
                return ChunkInboundDecodeResult.consumeControlFrame();
            }
            ChunkServerOfflineReuseStats.recordRejected(readChannelId(context), envelope.frame());
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

        if (envelope.frame().operation() == ChunkHotspotFrameOp.CLIENT_CACHE_MANIFEST) {
            handlePersistentClientCacheManifest(context, envelope.frame(), envelope.copyOriginalPacketBytes());
            logInboundControlFrame(context, packetBytes, envelope.frame(), INBOUND_CLIENT_CACHE_MANIFEST_FRAME_COUNT);
            return ChunkInboundDecodeResult.consumeControlFrame();
        }

        throw new IllegalStateException("Unsupported chunk transport operation in runtime MVP: " + envelope.frame().operation().logName());
    }

    public static boolean looksLikeChunkTransportEnvelope(byte[] packetBytes) {
        return ChunkTransportEnvelopeCodec.looksLikeEnvelope(packetBytes);
    }

    private static void handlePersistentClientCacheManifest(
            ChannelHandlerContext context,
            ChunkHotspotFrame frame,
            byte[] payloadBytes
    ) {
        if (context == null || frame == null || frame.operation() != ChunkHotspotFrameOp.CLIENT_CACHE_MANIFEST) {
            return;
        }

        if ((frame.coordinate() == null || !frame.coordinate().present()) && payloadBytes != null && payloadBytes.length > 0) {
            if (!matchesCurrentPersistentManifestScope(frame)) {
                logIgnoredPersistentManifestScope(context, frame, payloadBytes.length);
                return;
            }
            handlePersistentClientCacheManifestBatch(context, frame, payloadBytes);
            return;
        }

        if (frame.coordinate() == null || !frame.coordinate().present()) {
            if (!matchesCurrentPersistentManifestScope(frame)) {
                logIgnoredPersistentManifestScope(context, frame, 0);
                return;
            }
            ChunkPeerStateManager.ensureOutboundChannelScope(context, "persistent_manifest_complete_before_release");
            ChunkPersistentManifestGate.complete(context.channel(), frame.reason());
            logPersistentManifestComplete(context, frame);
            return;
        }

        applyPersistentClientCacheManifest(context, frame, true);
    }

    private static boolean matchesCurrentPersistentManifestScope(ChunkHotspotFrame frame) {
        if (frame == null || frame.payloadHash() == null || frame.payloadHash().isBlank()) {
            return true;
        }
        if (!ChunkPersistentServerScope.isSafeScopeHash(frame.payloadHash())) {
            return true;
        }
        return frame.payloadHash().equalsIgnoreCase(ChunkPersistentServerScope.currentScopeHash());
    }

    private static void logIgnoredPersistentManifestScope(
            ChannelHandlerContext context,
            ChunkHotspotFrame frame,
            int payloadBytes
    ) {
        if (!BO_Diag_cacheManifestSync()) {
            return;
        }
        DiagnosticLog.info(DiagnosticToolRegistry.Tool.CACHE_MANIFEST_SYNC, "event=ignore_stale_scope channel={}, frameScope={}, currentScope={}, bytes={}, reason={}",
                readChannelId(context),
                frame == null ? "<none>" : shortenHash(frame.payloadHash()),
                shortenHash(ChunkPersistentServerScope.currentScopeHash()),
                Math.max(payloadBytes, 0),
                frame == null ? "" : frame.reason()
        );
    }

    private static void handlePersistentClientCacheManifestBatch(
            ChannelHandlerContext context,
            ChunkHotspotFrame batchFrame,
            byte[] payloadBytes
    ) {
        long startNanos = ChunkLoadDelayProbe.isEnabled() ? System.nanoTime() : 0L;
        int appliedCount = 0;
        try {
            for (ChunkHotspotFrame manifestFrame : ChunkPersistentClientCacheManifestBatchCodec.decode(
                    payloadBytes,
                    batchFrame.reason()
            )) {
                if (applyPersistentClientCacheManifest(context, manifestFrame, false) != null) {
                    appliedCount++;
                }
            }
            ChunkLoadDelayProbe.logStage(
                    context,
                    batchFrame,
                    "server",
                    "manifest_batch_apply",
                    ChunkLoadDelayProbe.elapsedMillisSince(startNanos),
                    payloadBytes.length,
                    "applied=" + appliedCount
            );
            if (BO_Diag_cacheManifestSync()) {
                DiagnosticLog.info(DiagnosticToolRegistry.Tool.CACHE_MANIFEST_SYNC, "event=batch_recv channel={}, entries={}, applied={}, bytes={}, reason={}",
                        readChannelId(context),
                        batchFrame.fullSnapshotVersion(),
                        appliedCount,
                        payloadBytes.length,
                        batchFrame.reason() == null ? "" : batchFrame.reason()
                );
            }
        } catch (RuntimeException exception) {
            Bandwidthoptimizer.LOGGER.warn(
                    "[ChunkPersistentCache][Manifest][BatchFail] channel={}, bytes={}, reason={}",
                    readChannelId(context),
                    payloadBytes.length,
                    exception.toString()
            );
        }
    }

    private static ChunkPeerChunkStateSnapshot applyPersistentClientCacheManifest(
            ChannelHandlerContext context,
            ChunkHotspotFrame frame,
            boolean logEachEntry
    ) {
        if (frame.payloadHash() == null || frame.payloadHash().isBlank()) {
            return null;
        }

        ChunkPeerChunkStateSnapshot chunkSnapshot =
                ChunkPeerStateManager.recordPersistentClientManifest(context, frame);
        if (logEachEntry && BO_Diag_cacheManifestSync()) {
            DiagnosticLog.info(DiagnosticToolRegistry.Tool.CACHE_MANIFEST_SYNC, "event=recv channel={}, chunk={}, hash={}, state={}",
                    readChannelId(context),
                    frame.coordinate().logText(),
                    shortenHash(frame.payloadHash()),
                    chunkSnapshot == null ? "<ignored>" : chunkSnapshot.summaryText()
            );
        }
        return chunkSnapshot;
    }

    private static void logPersistentManifestComplete(ChannelHandlerContext context, ChunkHotspotFrame frame) {
        if (!BO_Diag_cacheManifestSync()) {
            return;
        }
        DiagnosticLog.info(DiagnosticToolRegistry.Tool.CACHE_MANIFEST_SYNC, "event=complete channel={}, reason={}",
                readChannelId(context),
                frame.reason() == null ? "" : frame.reason()
        );
    }

    private static ChunkPatchBuilder.ChunkPatchBuildResult resolveOutboundPatchCandidate(
            ChannelHandlerContext context,
            long scopeId,
            ChunkPacketDescriptor descriptor,
            Packet<?> packet,
            byte[] originalPacketBytes,
            ChunkSnapshotFingerprint fingerprint,
            ChunkPeerChunkStateSnapshot knownChunkSnapshot
    ) {
        if (!shouldBuildOutboundPatchCandidate(descriptor, fingerprint, knownChunkSnapshot)) {
            return ChunkPatchBuilder.ChunkPatchBuildResult.unavailable("patch_base_not_eligible");
        }
        return buildOutboundPatchCandidate(
                context,
                scopeId,
                descriptor,
                packet,
                originalPacketBytes,
                fingerprint,
                knownChunkSnapshot
        );
    }

    private static boolean shouldBuildOutboundPatchCandidate(
            ChunkPacketDescriptor descriptor,
            ChunkSnapshotFingerprint fingerprint,
            ChunkPeerChunkStateSnapshot knownChunkSnapshot
    ) {
        if (descriptor == null
                || fingerprint == null
                || fingerprint.hashHex() == null
                || fingerprint.hashHex().isBlank()
                || knownChunkSnapshot == null
                || !knownChunkSnapshot.knownSnapshotPublished()
                || knownChunkSnapshot.knownSnapshotHash() == null
                || knownChunkSnapshot.knownSnapshotHash().isBlank()) {
            return false;
        }
        return descriptor.hotspotKind() != ChunkHotspotKind.FULL_CHUNK
                || !knownChunkSnapshot.knownSnapshotHash().equals(fingerprint.hashHex());
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
                snapshot.lastInvalidatedAtMillis(),
                false,
                snapshot.persistentClientManifestCandidate(),
                snapshot.persistentClientManifestCandidateHash(),
                snapshot.persistentClientManifestCandidateShortHash(),
                snapshot.persistentClientManifestCandidateEncodedBytes()
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
            ChunkBlobHandle globalBlobHandle = ChunkGlobalSnapshotStore.findBlob(knownChunkSnapshot.knownSnapshotHash());
            storedFullBasePacketBytes = globalBlobHandle == null ? null : globalBlobHandle.copyBlobBytes();
        }
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
                1L,
                System.currentTimeMillis(),
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
                && (knownChunkSnapshot.fullReplayRequiredBeforeDelta()
                || knownChunkSnapshot.persistentClientManifestAcknowledged())
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
                resolveTransportPayloadBytes(decision, patchBuildResult, originalPacketBytes),
                decision
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
                        resolveTransportPayloadBytes(decision, patchBuildResult, originalPacketBytes),
                        decision
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
        long patchDecodeStartNanos = ChunkLoadDelayProbe.isEnabled() ? System.nanoTime() : 0L;
        try {
            chunkPatch = ChunkPatch.decode(envelope.copyOriginalPacketBytes());
        } catch (RuntimeException exception) {
            ChunkTransportControlFrameSender.sendNack(context, envelope.frame(), "runtime_patch_decode_failed");
            ChunkLoadDelayProbe.logClientRestoreMiss(context, envelope.frame(), "runtime_patch_decode_failed", true);
            return null;
        }
        ChunkLoadDelayProbe.logStage(
                context,
                envelope.frame(),
                "client",
                "patch_decode",
                ChunkLoadDelayProbe.elapsedMillisSince(patchDecodeStartNanos),
                envelope.copyOriginalPacketBytes().length,
                ""
        );

        long baseLookupStartNanos = ChunkLoadDelayProbe.isEnabled() ? System.nanoTime() : 0L;
        ChunkShadowSnapshot chunkSnapshot = ChunkShadowSnapshotManager.snapshotChunk(channelId, envelope.frame().epoch(), envelope.frame().coordinate());
        boolean matchingShadowFullBase = hasMatchingSnapshotFullBase(chunkSnapshot, envelope.frame());
        ChunkRuntimeReferenceStore.RuntimeFullSnapshot runtimeFullSnapshot = null;
        byte[] runtimeFullBasePacketBytes = null;
        ChunkLocalCacheReuseStats.ReuseSource patchBaseSource =
                ChunkLocalCacheReuseStats.ReuseSource.TEMPORARY_RUNTIME_CACHE;
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
            if (runtimeFullBasePacketBytes == null && isPersistentManifestPatchFrame(envelope.frame())) {
                runtimeFullBasePacketBytes = ChunkPersistentClientCache.findLoadedBasePacketBytes(envelope.frame());
                if (runtimeFullBasePacketBytes != null) {
                    patchBaseSource = ChunkLocalCacheReuseStats.ReuseSource.OFFLINE_PERSISTENT_CACHE;
                    ChunkRuntimeReferenceStore.storePacketBytes(
                            channelId,
                            envelope.frame().baseSnapshotHash(),
                            runtimeFullBasePacketBytes
                    );
                }
            }
        }
        ChunkLoadDelayProbe.logStage(
                context,
                envelope.frame(),
                "client",
                "patch_base_lookup",
                ChunkLoadDelayProbe.elapsedMillisSince(baseLookupStartNanos),
                runtimeFullBasePacketBytes == null ? 0 : runtimeFullBasePacketBytes.length,
                "matchingShadowFullBase=" + matchingShadowFullBase + ", source=" + patchBaseSource.name()
        );
        if (!matchingShadowFullBase
                && !hasMatchingRuntimeFullSnapshot(runtimeFullSnapshot, envelope.frame(), runtimeFullBasePacketBytes)
                && !canUseContentAddressedPatchBase(envelope.frame(), runtimeFullBasePacketBytes)) {
            String trimmedBudgetIgnoreReason = resolveTrimmedBudgetIgnoreReason(context, envelope.frame(), "runtime_patch_missing_full_base");
            if (!trimmedBudgetIgnoreReason.isBlank()) {
                ChunkLoadDelayProbe.logClientRestoreMiss(context, envelope.frame(), trimmedBudgetIgnoreReason, false);
                logTrimmedBudgetSuppressedRuntimeFrame(context, envelope.frame(), trimmedBudgetIgnoreReason);
                return null;
            }
            ChunkTransportControlFrameSender.sendNack(context, envelope.frame(), "runtime_patch_missing_full_base");
            ChunkLoadDelayProbe.logClientRestoreMiss(context, envelope.frame(), "runtime_patch_missing_full_base", true);
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
            ChunkLoadDelayProbe.logClientRestoreMiss(context, envelope.frame(), "runtime_patch_missing_lane_base", true);
            return null;
        }

        try {
            long applyStartNanos = ChunkLoadDelayProbe.isEnabled() ? System.nanoTime() : 0L;
            byte[] restoredPacketBytes = ChunkPatchApplier.applyPatch(chunkPatch, basePacketBytes, envelope.frame().payloadHash());
            ChunkLoadDelayProbe.logStage(
                    context,
                    envelope.frame(),
                    "client",
                    "patch_apply",
                    ChunkLoadDelayProbe.elapsedMillisSince(applyStartNanos),
                    restoredPacketBytes.length,
                    "baseBytes=" + basePacketBytes.length
            );
            if (patchBaseSource == ChunkLocalCacheReuseStats.ReuseSource.OFFLINE_PERSISTENT_CACHE) {
                ChunkRuntimeReferenceStore.storePacketBytes(channelId, envelope.frame().payloadHash(), restoredPacketBytes);
            }
            return restoredPacketBytes;
        } catch (RuntimeException exception) {
            ChunkTransportControlFrameSender.sendNack(context, envelope.frame(), "runtime_patch_apply_failed");
            ChunkLoadDelayProbe.logClientRestoreMiss(context, envelope.frame(), "runtime_patch_apply_failed", true);
            return null;
        }
    }

    // Inbound client
    private static void observeInboundSnapshot(
            ChannelHandlerContext context,
            ChunkHotspotFrame frame,
            byte[] restoredPacketBytes
    ) {
        if (context == null || frame == null || restoredPacketBytes == null) {
            return;
        }

        ChunkPacketDescriptor descriptor = descriptorFromTransportFrame(frame);
        Packet<ClientGamePacketListener> restoredPacket = null;
        if (descriptor == null) {
            restoredPacket = decodeClientboundPlayPacket(restoredPacketBytes);
            descriptor = ChunkPacketClassifier.classifyOutboundPlayPacket("PLAY", restoredPacket);
        }
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
        ChunkClientCacheBudgetManager.enforceInboundBudget(context, "client_chunk_transport_budget");
    }

    private static ChunkPacketDescriptor descriptorFromTransportFrame(ChunkHotspotFrame frame) {
        if (frame == null
                || frame.hotspotKind() != ChunkHotspotKind.FULL_CHUNK
                || frame.coordinate() == null
                || !frame.coordinate().present()) {
            return null;
        }
        return new ChunkPacketDescriptor(
                frame.protocolName() == null || frame.protocolName().isBlank() ? "PLAY" : frame.protocolName(),
                frame.packetClassName() == null ? "" : frame.packetClassName(),
                frame.hotspotKind(),
                frame.laneKind(),
                frame.coordinate()
        );
    }

    private static void acknowledgeFullChunkPatchIfNeeded(
            ChannelHandlerContext context,
            ChunkHotspotFrame frame,
            byte[] restoredPacketBytes
    ) {
        if (context == null
                || frame == null
                || restoredPacketBytes == null
                || (!isWatchBoundaryRefreshPatchFrame(frame) && !isPersistentManifestPatchFrame(frame))) {
            return;
        }

        String channelId = readChannelId(context);
        ChunkRuntimeReferenceStore.storePacketBytes(channelId, frame.payloadHash(), restoredPacketBytes);
        ChunkRuntimeReferenceStore.storeFullSnapshot(channelId, frame);
        ChunkTransportControlFrameSender.sendAck(context, frame, resolveFullChunkPatchAckReason(frame));
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

    private static void rememberRecoverableFullChunkFrame(
            ChannelHandlerContext context,
            RuntimeChunkTransportDecision runtimeDecision,
            byte[] originalPacketBytes
    ) {
        if (context == null
                || runtimeDecision == null
                || runtimeDecision.frame() == null
                || !shouldRememberRecoverableFullChunkFrame(runtimeDecision.frame())) {
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

    private static boolean isPersistentManifestRefFrame(ChunkHotspotFrame frame) {
        return frame != null
                && frame.operation() == ChunkHotspotFrameOp.PUBLISH_REF
                && ChunkLocalCacheReuseStats.PERSISTENT_MANIFEST_REF_REASON.equals(frame.reason());
    }

    private static boolean isPersistentManifestPatchFrame(ChunkHotspotFrame frame) {
        return frame != null
                && frame.operation() == ChunkHotspotFrameOp.PUBLISH_PATCH
                && ChunkLocalCacheReuseStats.PERSISTENT_MANIFEST_PATCH_REASON.equals(frame.reason());
    }

    private static String resolveFullChunkPatchAckReason(ChunkHotspotFrame frame) {
        return isPersistentManifestPatchFrame(frame)
                ? ChunkLocalCacheReuseStats.RUNTIME_PATCH_REUSED_AFTER_PERSISTENT_MANIFEST_REASON
                : WATCH_BOUNDARY_REFRESH_PATCH_ACK_REASON;
    }

    private static String resolveReferenceOfflineReuseAckReason(ChunkHotspotFrame frame) {
        return isPersistentManifestRefFrame(frame)
                ? ChunkLocalCacheReuseStats.RUNTIME_REF_REUSED_AFTER_PERSISTENT_MANIFEST_REASON
                : ChunkLocalCacheReuseStats.RUNTIME_REF_REUSED_FROM_PERSISTENT_CACHE_REASON;
    }

    private static boolean shouldRememberRecoverableFullChunkFrame(ChunkHotspotFrame frame) {
        return frame != null
                && frame.hotspotKind() == ChunkHotspotKind.FULL_CHUNK
                && (frame.operation() == ChunkHotspotFrameOp.PUBLISH_REF
                || frame.operation() == ChunkHotspotFrameOp.PUBLISH_PATCH);
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

    private static boolean tryHandleRecoverableFullChunkNack(
            ChannelHandlerContext context,
            ChunkHotspotFrame frame
    ) {
        if (!isRecoverableFullChunkMiss(frame)) {
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
                resolveRecoverableFallbackFullReason(frame)
        )) {
            ChunkLoadDelayProbe.logServerNackRecovery(
                    context,
                    frame,
                    true,
                    pendingReplay.copyOriginalPacketBytes().length,
                    resolveRecoverableFallbackFullReason(frame)
            );
            return true;
        }

        ChunkTransportControlFrameSender.sendInvalidate(
                context,
                frame,
                resolveRecoverableFallbackInvalidateReason(frame)
        );
        ChunkLoadDelayProbe.logServerNackRecovery(
                context,
                frame,
                false,
                0,
                resolveRecoverableFallbackInvalidateReason(frame)
        );
        return true;
    }

    private static boolean isRecoverableFullChunkMiss(ChunkHotspotFrame frame) {
        return isWatchBoundaryReuseProbeMiss(frame)
                || isWatchBoundaryRefreshPatchMiss(frame)
                || isRuntimeRefMissingBase(frame)
                || isRuntimePatchMissingFullBase(frame);
    }

    private static boolean isRuntimeRefMissingBase(ChunkHotspotFrame frame) {
        return frame != null
                && frame.operation() == ChunkHotspotFrameOp.NACK
                && frame.hotspotKind() == ChunkHotspotKind.FULL_CHUNK
                && "runtime_ref_missing_base".equals(frame.reason());
    }

    private static boolean isRuntimePatchMissingFullBase(ChunkHotspotFrame frame) {
        return frame != null
                && frame.operation() == ChunkHotspotFrameOp.NACK
                && frame.hotspotKind() == ChunkHotspotKind.FULL_CHUNK
                && "runtime_patch_missing_full_base".equals(frame.reason());
    }

    private static String resolveRecoverableFallbackFullReason(ChunkHotspotFrame frame) {
        return isWatchBoundaryRefreshPatchMiss(frame)
                ? WATCH_BOUNDARY_REFRESH_PATCH_FALLBACK_FULL_REASON
                : isWatchBoundaryReuseProbeMiss(frame)
                ? WATCH_BOUNDARY_REUSE_PROBE_FALLBACK_FULL_REASON
                : isRuntimePatchMissingFullBase(frame)
                ? RUNTIME_PATCH_FALLBACK_FULL_REASON
                : RUNTIME_REF_FALLBACK_FULL_REASON;
    }

    private static String resolveRecoverableFallbackInvalidateReason(ChunkHotspotFrame frame) {
        return isWatchBoundaryRefreshPatchMiss(frame)
                ? WATCH_BOUNDARY_REFRESH_PATCH_FALLBACK_INVALIDATE_REASON
                : isWatchBoundaryReuseProbeMiss(frame)
                ? WATCH_BOUNDARY_REUSE_PROBE_FALLBACK_INVALIDATE_REASON
                : isRuntimePatchMissingFullBase(frame)
                ? RUNTIME_PATCH_FALLBACK_INVALIDATE_REASON
                : RUNTIME_REF_FALLBACK_INVALIDATE_REASON;
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
        DiagnosticLog.info(DiagnosticToolRegistry.Tool.CHUNK_TRANSPORT_FRAMES, "event=control_ignore, action={}, channel={}, op={}, epoch={}, chunk={}, fullVersion={}, baseHash={}, payloadHash={}, state={}",
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
        if (envelope.frame().operation() == ChunkHotspotFrameOp.PUBLISH_REF
                || envelope.frame().operation() == ChunkHotspotFrameOp.PUBLISH_PATCH) {
            ChunkLocalCacheReuseStats.recordServerClassifiedReuse(
                    envelope.frame(),
                    restoredPacketBytes == null ? 0 : restoredPacketBytes.length,
                    packetBytes == null ? 0 : packetBytes.length
            );
        }
        ChunkHotspotVerifyHooks.flushCurrentReport();
        long frameCount = inboundCounter.incrementAndGet();
        if (!shouldLogDiagnose() || !shouldLogSample(frameCount)) {
            return;
        }

        DiagnosticLog.info(DiagnosticToolRegistry.Tool.CHUNK_TRANSPORT_FRAMES, "event=unwrap, channel={}, op={}, count={}, epoch={}, observedPackets={}, chunk={}, payloadBytes={}, envelopeBytes={}, payloadHash={}",
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

        DiagnosticLog.info(DiagnosticToolRegistry.Tool.CHUNK_TRANSPORT_FRAMES, "event=control_recv, channel={}, op={}, count={}, epoch={}, observedPackets={}, chunk={}, fullVersion={}, payloadHash={}, reason={}",
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
        DiagnosticLog.info(DiagnosticToolRegistry.Tool.CHUNK_TRANSPORT_FRAMES, "event=data_ignore, channel={}, op={}, epoch={}, chunk={}, fullVersion={}, baseHash={}, payloadHash={}, reason={}",
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
        DiagnosticLog.info(DiagnosticToolRegistry.Tool.CHUNK_TRANSPORT_FRAMES, "event=budget_ignore, channel={}, op={}, epoch={}, chunk={}, fullVersion={}, baseHash={}, payloadHash={}, reason={}",
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
        return DiagnosticToolRegistry.isEnabled(DiagnosticToolRegistry.Tool.CHUNK_TRANSPORT_FRAMES);
    }

    private static boolean BO_Diag_cacheManifestSync() {
        return DiagnosticToolRegistry.isEnabled(DiagnosticToolRegistry.Tool.CACHE_MANIFEST_SYNC);
    }

    private static String readChannelId(ChannelHandlerContext context) {
        if (context == null) {
            return "<null>";
        }
        return com.PinkCats.bandwidthoptimizer.channel.ChannelIdentity.longText(context.channel());
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
            byte[] transportPayloadBytes,
            ChunkPlanDecision decision
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
