package com.PinkCats.bandwidthoptimizer.channel;

import com.PinkCats.bandwidthoptimizer.debug.DiagnosticLog;
import com.PinkCats.bandwidthoptimizer.debug.DiagnosticToolRegistry;
import com.PinkCats.bandwidthoptimizer.debug.PacketClassTraceDiagnostic;
import com.PinkCats.bandwidthoptimizer.connection.ConnectionDisconnectClassifier;

import com.PinkCats.bandwidthoptimizer.Bandwidthoptimizer;
import com.PinkCats.bandwidthoptimizer.Config;
import com.PinkCats.bandwidthoptimizer.channel.access.PacketDecoderFlowAccess;
import com.PinkCats.bandwidthoptimizer.channel.access.PacketEncoderFlowAccess;
import com.PinkCats.bandwidthoptimizer.channel.algorithm.KineticChannel;
import com.PinkCats.bandwidthoptimizer.channel.algorithm.batch.ChannelTransportBatchManager;
import com.PinkCats.bandwidthoptimizer.channel.algorithm.mes.Incomplete;
import com.PinkCats.bandwidthoptimizer.channel.algorithm.mes.ChannelTransportOperationTelemetry;
import com.PinkCats.bandwidthoptimizer.channel.capture.ChannelCaptureHooks;
import com.PinkCats.bandwidthoptimizer.channel.capture.ChannelCapturedFrame;
import com.PinkCats.bandwidthoptimizer.channel.capture.ChannelTransportTelemetry;
import com.PinkCats.bandwidthoptimizer.channel.debug.NettySpikeProbe;
import com.PinkCats.bandwidthoptimizer.channel.packet.ChannelTransportBypassPacketList;
import com.PinkCats.bandwidthoptimizer.chunk.classify.ChunkOutboundObservationService;
import com.PinkCats.bandwidthoptimizer.chunk.debug.ChunkLoadDelayProbe;
import com.PinkCats.bandwidthoptimizer.chunk.integration.ChunkInboundDecodeResult;
import com.PinkCats.bandwidthoptimizer.chunk.integration.transport.ChunkTransportBoundaryController;
import com.PinkCats.bandwidthoptimizer.chunk.integration.transport.ChunkTransportControlFrameSender;
import com.PinkCats.bandwidthoptimizer.chunk.integration.transport.ChunkTransportDispatcher;
import com.PinkCats.bandwidthoptimizer.chunk.integration.transport.ChunkTransportDispatcher.OutboundChunkEncodeResult;
import com.PinkCats.bandwidthoptimizer.chunk.persistent.ChunkPersistentOutboundGate;
import com.PinkCats.bandwidthoptimizer.gate.integration.create.CreateBlockEntityUpdateGate;
import com.PinkCats.bandwidthoptimizer.integration.sable.SableChunkSyncCompat;
import com.PinkCats.bandwidthoptimizer.debug.ChannelTransportHookDiagnosticProbe;
import com.PinkCats.bandwidthoptimizer.debug.HotpathCostProbe;
import com.PinkCats.bandwidthoptimizer.mixin.minecraft.ClientboundCustomPayloadPacketAccessor;
import com.PinkCats.bandwidthoptimizer.mixin.minecraft.ServerboundCustomPayloadPacketAccessor;
import com.PinkCats.bandwidthoptimizer.report.ChunkBoundaryBandwidthRecorder;
import com.PinkCats.bandwidthoptimizer.report.ChannelTransportPacketRankCaptureManager;
import com.PinkCats.bandwidthoptimizer.report.ChannelTransportPacketRankSourceResolver;
import com.PinkCats.bandwidthoptimizer.server.stat.ChannelBandwidthStats;
import com.PinkCats.bandwidthoptimizer.server.stat.ServerBandwidthStatsRegistry;
import io.netty.buffer.ByteBuf;
import io.netty.buffer.ByteBufUtil;
import io.netty.buffer.Unpooled;
import io.netty.channel.Channel;
import io.netty.channel.ChannelFuture;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.ChannelPromise;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.network.Connection;
import net.minecraft.network.ConnectionProtocol;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.PacketListener;
import net.minecraft.network.protocol.Packet;
import net.minecraft.network.protocol.PacketFlow;
import net.minecraft.network.protocol.game.ClientboundCustomPayloadPacket;
import net.minecraft.network.protocol.game.ClientboundLoginPacket;
import net.minecraft.network.protocol.game.ServerboundCustomPayloadPacket;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;

public final class ChannelTransportHooks {

    private static final ResourceLocation TRANSPORT_PAYLOAD_ID =
            ChannelTransportNetworkChannel.TRANSPORT_PAYLOAD_ID;
    private static final int CLIENTBOUND_CUSTOM_PAYLOAD_MAX_BYTES = 1_048_576;
    private static final int SERVERBOUND_CUSTOM_PAYLOAD_MAX_BYTES = 32767;
    private static final int SERVER_CACHE_SCOPE_MAX_RETRY_ATTEMPTS = 3;
    private static final long SERVER_CACHE_SCOPE_RETRY_DELAY_MILLIS = 50L;
    private static final AtomicLong OUTBOUND_TRANSPORT_TRACE_COUNTER = new AtomicLong();
    private static final AtomicLong OUTBOUND_CARRIER_TRACE_COUNTER = new AtomicLong();
    private static final AtomicLong INBOUND_CARRIER_TRACE_COUNTER = new AtomicLong();
    private static final AtomicLong INBOUND_RESTORED_PACKET_TRACE_COUNTER = new AtomicLong();

    private ChannelTransportHooks() {}

    // transport and chunk transport handle
    public static void tryToWrapOutboundPacket(
            ChannelHandlerContext context,
            Packet<?> packet,
            ByteBuf out,
            int startIndexInclusive,
            PacketEncoderFlowAccess packetEncoderFlowAccess
    ) {
        if (context == null || out == null) {
            return;
        }
        NettySpikeProbe.BO_Diag_nettyMSPT(context);

        int endIndexExclusive = out.writerIndex();
        if (endIndexExclusive <= startIndexInclusive) {
            return;
        }

        String protocolName = readProtocolName(context);
        byte[] originalPacketBytes = ByteBufUtil.getBytes(out, startIndexInclusive, endIndexExclusive - startIndexInclusive, false);
        PacketFlow outboundPacketFlow = resolvePacketFlow(packetEncoderFlowAccess, readConnectionProtocolOrNull(context), packet);
        if (ChunkPersistentOutboundGate.tryQueuePendingCoordinatePacket(context, protocolName, packet, originalPacketBytes.length)) {
            out.writerIndex(startIndexInclusive);
            return;
        }
        CreateBlockEntityUpdateGate.observeOutboundPacket(context, protocolName, outboundPacketFlow, packet);
        ChunkLoadDelayProbe.logVanillaLevelChunkPacketOutbound(context, packet, originalPacketBytes.length);
        if (!CreateBlockEntityUpdateGate.shouldBypassCreateGateDelay(context, protocolName, outboundPacketFlow, packet)
                && CreateBlockEntityUpdateGate.tryDelayOutboundPacket(
                        context,
                        protocolName,
                        outboundPacketFlow,
                        packet,
                        originalPacketBytes)) {
            out.writerIndex(startIndexInclusive);
            return;
        }


        if (!ChannelTransportRuntimeGuard.isTransportAvailable()
                || !shouldUseTransportForCurrentProtocol(protocolName)) {
            recordCommittedOutboundPacketStream(context, packet, originalPacketBytes);
            recordDirectPacketTrace(
                    context,
                    "transport_unavailable_or_protocol",
                    protocolName,
                    packet,
                    outboundPacketFlow,
                    originalPacketBytes
            );
            recordOutboundBypassStats(context, protocolName, originalPacketBytes.length, 1);
            ChannelTransportPacketRankCaptureManager.recordDirectPassthrough(
                    context,
                    protocolName,
                    packet,
                    originalPacketBytes
            );
            return;
        }

        ChannelTransportControlPlane.TransportControlDecision controlDecision =
                ChannelTransportControlPlane.beginOutboundPacket(context, protocolName, packet);
        ChunkTransportBoundaryController.OutboundBoundaryDecision boundaryDecision =
                ChunkTransportBoundaryController.beginOutboundPacket(context, protocolName, packet);
        boolean forceImmediateTransport = controlDecision.forceImmediateTransport();
        boolean pendingDirectTransport = controlDecision.forceDirectTransport() || boundaryDecision.forceDirectTransport();
        if (forceImmediateTransport && pendingDirectTransport && ChannelTransportHookDiagnosticProbe.BO_Diag_chunkTransportFrames()) {
            DiagnosticLog.info(DiagnosticToolRegistry.Tool.CHUNK_TRANSPORT_FRAMES, "event=immediate_policy_direct_override immediateReason={}, directReason={}, controlDirect={}, boundaryDirect={}, protocol={}, packetClass={}, channel={}",
                    controlDecision.reason(),
                    controlDecision.forceDirectTransport() ? controlDecision.reason() : boundaryDecision.reason(),
                    controlDecision.forceDirectTransport(),
                    boundaryDecision.forceDirectTransport(),
                    protocolName,
                    packetClassName(packet),
                    channelIdText(context)
            );
        }
        boolean forceDirectTransport = !forceImmediateTransport && pendingDirectTransport;
        if (forceDirectTransport) {
            long hotpathStartNanos = HotpathCostProbe.start();
            ChannelTransportBatchManager.flushOutboundBatchNow(context);
            HotpathCostProbe.end("hook.forceDirect.flushBatch", hotpathStartNanos);
            hotpathStartNanos = HotpathCostProbe.start();
            ChunkTransportBoundaryController.scheduleOutboundBarrier(context, boundaryDecision);
            HotpathCostProbe.end("hook.forceDirect.scheduleBarrier", hotpathStartNanos);
            OutboundChunkEncodeResult directChunkTrace = new OutboundChunkEncodeResult(
                    false,
                    false,
                    null,
                    controlDecision.forceDirectTransport() ? controlDecision.reason() : boundaryDecision.reason(),
                    null
            );
            hotpathStartNanos = HotpathCostProbe.start();
            ChunkBoundaryBandwidthRecorder.OutboundPacketTrace directBoundaryPacketTrace =
                    ChunkBoundaryBandwidthRecorder.beginOutboundTrace(
                            context,
                            protocolName,
                            packet,
                            originalPacketBytes,
                            originalPacketBytes,
                            directChunkTrace
                    );
            HotpathCostProbe.end("hook.forceDirect.beginBoundaryTrace", hotpathStartNanos);
            hotpathStartNanos = HotpathCostProbe.start();
            recordCommittedOutboundPacketStream(context, packet, originalPacketBytes);
            HotpathCostProbe.end("hook.forceDirect.commitStream", hotpathStartNanos);
            hotpathStartNanos = HotpathCostProbe.start();
            recordDirectPacketTrace(
                    context,
                    controlDecision.forceDirectTransport() ? controlDecision.reason() : boundaryDecision.reason(),
                    protocolName,
                    packet,
                    outboundPacketFlow,
                    originalPacketBytes
            );
            HotpathCostProbe.end("hook.forceDirect.directTrace", hotpathStartNanos);
            hotpathStartNanos = HotpathCostProbe.start();
            recordOutboundBypassStats(context, protocolName, originalPacketBytes.length, 1);
            HotpathCostProbe.end("hook.forceDirect.bypassStats", hotpathStartNanos);
            hotpathStartNanos = HotpathCostProbe.start();
            ChannelTransportPacketRankCaptureManager.recordDirectPassthrough(
                    context,
                    protocolName,
                    packet,
                    originalPacketBytes
            );
            HotpathCostProbe.end("hook.forceDirect.rankCapture", hotpathStartNanos);
            hotpathStartNanos = HotpathCostProbe.start();
            ChunkBoundaryBandwidthRecorder.completeOutboundTrace(
                    directBoundaryPacketTrace,
                    "DIRECT_PASSTHROUGH",
                    "DIRECT",
                    originalPacketBytes.length,
                    false,
                    1
            );
            HotpathCostProbe.end("hook.forceDirect.completeBoundaryTrace", hotpathStartNanos);
            hotpathStartNanos = HotpathCostProbe.start();
            sendServerCacheScopeAfterLoginBoundary(context, packet);
            HotpathCostProbe.end("hook.forceDirect.serverCacheScope", hotpathStartNanos);
            return;
        }
        if (forceImmediateTransport) {
            ChannelTransportBatchManager.flushOutboundBatchNow(context);
            if (ChannelTransportHookDiagnosticProbe.BO_Diag_chunkTransportFrames()) {
                DiagnosticLog.info(DiagnosticToolRegistry.Tool.CHUNK_TRANSPORT_FRAMES, "event=immediate_policy_start reason={}, protocol={}, packetClass={}, rawBytes={}, rawPacketId={}, channel={}",
                        controlDecision.reason(),
                        protocolName,
                        packetClassName(packet),
                        originalPacketBytes.length,
                        tryReadLeadingVarInt(originalPacketBytes),
                        channelIdText(context)
                );
            }
        }

        long chunkEncodeStartNanos = NettySpikeProbe.BO_Diag_nettyMSPT_timer(
                context,
                "outbound_chunk_encode",
                packetClassName(packet) + ", rawBytes=" + originalPacketBytes.length
        );
        OutboundChunkEncodeResult chunkEncodeResult;
        boolean statefulTransportAttempted = false;
        try {
            chunkEncodeResult = ChunkTransportDispatcher.tryEncodeOutboundPacketWithTrace(
                    context,
                    protocolName,
                    packet,
                    originalPacketBytes
            );
        } finally {
            NettySpikeProbe.BO_Diag_nettyMSPT_timer(
                    context,
                    "outbound_chunk_encode",
                    chunkEncodeStartNanos,
                    packetClassName(packet) + ", rawBytes=" + originalPacketBytes.length
            );
        }
        if (ChunkPersistentOutboundGate.tryQueueWaitingPacket(context, packet, chunkEncodeResult.traceReason())) {
            out.writerIndex(startIndexInclusive);
            return;
        }
        byte[] chunkTransportEncodedBytes = chunkEncodeResult.copyEncodedPacketBytes();
        if (chunkEncodeResult.chunkProtocolApplied()) {

            ChunkOutboundObservationService.observeOutboundPacket(
                    context,
                    protocolName,
                    packet,
                    originalPacketBytes
            );
        }
        byte[] transportInputPacketBytes = chunkTransportEncodedBytes == null ? originalPacketBytes : chunkTransportEncodedBytes;
        byte[] directFallbackPacketBytes = chunkTransportEncodedBytes == null ? transportInputPacketBytes : originalPacketBytes;
        boolean chunkProtocolApplied = chunkTransportEncodedBytes != null;
        ChunkBoundaryBandwidthRecorder.OutboundPacketTrace boundaryPacketTrace =
                ChunkBoundaryBandwidthRecorder.beginOutboundTrace(
                        context,
                        protocolName,
                        packet,
                        originalPacketBytes,
                        transportInputPacketBytes,
                        chunkEncodeResult
                );

        if (shouldBypassTransparentTransport(context, protocolName, packet, outboundPacketFlow)) {
            ChannelTransportBatchManager.flushOutboundBatchNow(context);
            recordCommittedOutboundPacketStream(context, packet, originalPacketBytes);
            recordDirectPacketTrace(
                    context,
                    "transparent_bypass",
                    protocolName,
                    packet,
                    outboundPacketFlow,
                    originalPacketBytes
            );
            recordOutboundBypassStats(context, protocolName, originalPacketBytes.length, 1);
            ChannelTransportPacketRankCaptureManager.recordDirectPassthrough(
                    context,
                    protocolName,
                    packet,
                    originalPacketBytes
            );
            ChunkBoundaryBandwidthRecorder.completeOutboundTrace(
                    boundaryPacketTrace,
                    "DIRECT_PASSTHROUGH",
                    "DIRECT",
                    originalPacketBytes.length,
                    false,
                    1
            );
            return;
        }

        ChannelTransportPacketRankCaptureManager.OutboundPacketCapture outboundPacketCapture =
                ChannelTransportPacketRankCaptureManager.beginOutboundPacketCapture(
                        context,
                        protocolName,
                        packet,
                        originalPacketBytes,
                        transportInputPacketBytes,
                        chunkTransportEncodedBytes != null
                );

        try {
            if (shouldBypassServerboundCarrierByInputSize(outboundPacketFlow, transportInputPacketBytes.length)) {
                out.writerIndex(startIndexInclusive);
                out.writeBytes(directFallbackPacketBytes);
                recordCommittedOutboundPacketStream(context, packet, directFallbackPacketBytes);
                if (forceImmediateTransport && ChannelTransportHookDiagnosticProbe.BO_Diag_chunkTransportFrames()) {
                    DiagnosticLog.info(DiagnosticToolRegistry.Tool.CHUNK_TRANSPORT_FRAMES, "event=immediate_policy_fallback reason=serverbound_carrier_size, packetClass={}, inputBytes={}, channel={}",
                            packetClassName(packet),
                            transportInputPacketBytes.length,
                            channelIdText(context)
                    );
                }
                recordDirectPacketTrace(
                        context,
                        "serverbound_carrier_size",
                        protocolName,
                        packet,
                        outboundPacketFlow,
                        directFallbackPacketBytes
                );
                recordOutboundBypassStats(context, protocolName, directFallbackPacketBytes.length, 1);
                ChannelTransportPacketRankCaptureManager.completeSingleDirectFallbackCapture(
                        outboundPacketCapture,
                        directFallbackPacketBytes.length
                );
                ChunkBoundaryBandwidthRecorder.completeOutboundTrace(
                        boundaryPacketTrace,
                        "DIRECT_PASSTHROUGH",
                        "DIRECT",
                        directFallbackPacketBytes.length,
                        chunkProtocolApplied,
                        1
                );
                return;
            }

            if (!forceImmediateTransport && ChannelTransportBatchManager.shouldBatchOutboundPacket(context)) {
                out.writerIndex(startIndexInclusive);
                if (ChannelTransportHookDiagnosticProbe.BO_Diag_transportTraceJournal()) {
                    ChannelTransportTraceJournal.record(
                            context,
                            "OutboundBatchEnqueue",
                            "batch:" + packetClassName(packet),
                            "channel=" + channelIdText(context)
                                    + ", flow=" + outboundPacketFlow
                                    + ", protocol=" + protocolName
                                    + ", packetClass=" + packetClassName(packet)
                                    + ", inputPacketId=" + tryReadLeadingVarInt(transportInputPacketBytes)
                                    + ", inputBytes=" + lengthOf(transportInputPacketBytes)
                                    + ", chunkApplied=" + (chunkTransportEncodedBytes != null)
                                    + ", inputPrefix=" + hexPrefix(transportInputPacketBytes)
                    );
                }
                ChannelTransportBatchManager.enqueueOutboundPacket(
                        context,
                        transportInputPacketBytes,
                        directFallbackPacketBytes,
                        outboundPacketFlow,
                        outboundPacketCapture,
                        boundaryPacketTrace,
                        PacketClassTraceDiagnostic.beginOutboundBatch(
                                context,
                                protocolName,
                                packet,
                                outboundPacketFlow,
                                originalPacketBytes
                        )
                );
                return;
            }

            if (!chunkProtocolApplied && ChannelTransportAdaptiveBypass.shouldBypassBeforeWrap(
                    protocolName,
                    outboundPacketFlow,
                    packet,
                    transportInputPacketBytes
            )) {
                ChannelTransportBatchManager.flushOutboundBatchNow(context);
                out.writerIndex(startIndexInclusive);
                out.writeBytes(directFallbackPacketBytes);
                recordCommittedOutboundPacketStream(context, packet, directFallbackPacketBytes);
                recordDirectPacketTrace(
                        context,
                        "adaptive_unprofitable_carrier",
                        protocolName,
                        packet,
                        outboundPacketFlow,
                        directFallbackPacketBytes
                );
                recordOutboundBypassStats(context, protocolName, directFallbackPacketBytes.length, 1);
                ChannelTransportPacketRankCaptureManager.completeSingleDirectFallbackCapture(
                        outboundPacketCapture,
                        directFallbackPacketBytes.length
                );
                ChunkBoundaryBandwidthRecorder.completeOutboundTrace(
                        boundaryPacketTrace,
                        "DIRECT_PASSTHROUGH",
                        "DIRECT",
                        directFallbackPacketBytes.length,
                        false,
                        1
                );
                return;
            }

            ChannelTransportSession transportSession = ChannelTransportStateManager.getOrCreateSession(context.channel());
            long wrapStartNanos = NettySpikeProbe.BO_Diag_nettyMSPT_timer(
                    context,
                    "outbound_transport_wrap",
                    packetClassName(packet) + ", inputBytes=" + transportInputPacketBytes.length
            );
            ChannelTransportPacketCodec.WrappedTransportFrame wrappedFrame;
            try {
                statefulTransportAttempted = true;
                wrappedFrame = transportSession.isOutboundStreamingEpochClosed()
                        ? ChannelTransportPacketCodec.wrapIndependentBatchPackets(
                                transportSession,
                                List.of(transportInputPacketBytes)
                        )
                        : KineticChannel.processOutboundPacket(transportSession, transportInputPacketBytes);
            } finally {
                NettySpikeProbe.BO_Diag_nettyMSPT_timer(
                        context,
                        "outbound_transport_wrap",
                        wrapStartNanos,
                        packetClassName(packet) + ", inputBytes=" + transportInputPacketBytes.length
                );
            }
            if (wrappedFrame == null) {
                throw new IllegalStateException("Stateful transport wrap returned no carrier");
            }
            logOutboundTransportTrace(
                    context,
                    protocolName,
                    packet,
                    outboundPacketFlow,
                    originalPacketBytes,
                    transportInputPacketBytes,
                    wrappedFrame,
                    chunkTransportEncodedBytes != null
            );

            ChannelTransportSession.StreamingEpochBoundary epochBoundary =
                    wrappedFrame.frameKind() == ChannelTransportPacketCodec.FrameKind.STREAM_BATCH
                            ? transportSession.outboundStreamingEpochBoundary()
                            : null;
            if (epochBoundary != null) {
                ChannelTransportStreamingEpochGate.closeForEpoch(
                        context.channel(),
                        epochBoundary.epoch(),
                        epochBoundary.lastSequence()
                );
            }

            out.writerIndex(startIndexInclusive);
            recordCommittedOutboundPacketStream(context, packet, originalPacketBytes);
            if (!writeTransportCarrierPacket(context, outboundPacketFlow, out, wrappedFrame.transportFrameBytes())) {
                throw new IllegalStateException("Stateful transport carrier was not committed");
            }
            if (epochBoundary != null) {
                // Commit the encoded boundary before releasing deferred raw writes.
                int carrierLength = out.writerIndex() - startIndexInclusive;
                byte[] encodedCarrier = ByteBufUtil.getBytes(out, startIndexInclusive, carrierLength, false);
                out.writerIndex(startIndexInclusive);
                ChannelOutboundBurstWarning.recordWireFrame(context, outboundPacketFlow, encodedCarrier.length, true);
                ChannelFuture boundaryWrite = context.write(Unpooled.wrappedBuffer(encodedCarrier));
                boundaryWrite.addListener(future -> {
                    if (!future.isSuccess()) {
                        Bandwidthoptimizer.LOGGER.warn(
                                "[Transport][StreamingEpoch] boundary carrier write failed",
                                future.cause()
                        );
                        ConnectionDisconnectClassifier.markBoInitiatedClose(
                                context.channel(),
                                "streaming-boundary-carrier-write",
                                future.cause()
                        );
                        context.close();
                    }
                });
                context.flush();
                ChannelTransportStreamingEpochCoordinator.boundaryQueued(context.channel());
            }
            if (forceImmediateTransport && ChannelTransportHookDiagnosticProbe.BO_Diag_chunkTransportFrames()) {
                DiagnosticLog.info(DiagnosticToolRegistry.Tool.CHUNK_TRANSPORT_FRAMES, "event=immediate_policy_result path=single_transport, reason={}, protocol={}, packetClass={}, rawBytes={}, inputBytes={}, frameKind={}, frameBytes={}, channel={}",
                        controlDecision.reason(),
                        protocolName,
                        packetClassName(packet),
                        originalPacketBytes.length,
                        transportInputPacketBytes.length,
                        wrappedFrame.frameKind(),
                        wrappedFrame.transportFrameLength(),
                        channelIdText(context)
                );
            }
            recordOutboundTransportStats(context, readProtocolName(context), wrappedFrame);
            ChannelTransportPacketRankCaptureManager.completeSingleTransportCapture(outboundPacketCapture, wrappedFrame);
            ChunkBoundaryBandwidthRecorder.completeOutboundTrace(
                    boundaryPacketTrace,
                    "SINGLE_TRANSPORT",
                    wrappedFrame.frameKind().name(),
                    wrappedFrame.transportFrameLength(),
                    false,
                    Math.max(wrappedFrame.originalPacketCount(), 1)
            );
            if (!chunkProtocolApplied) {
                ChannelTransportAdaptiveBypass.recordCarrierResult(
                        protocolName,
                        outboundPacketFlow,
                        packet,
                        transportInputPacketBytes,
                        wrappedFrame
                );
            }
        } catch (Throwable throwable) {
            if (statefulTransportAttempted) {
                out.writerIndex(startIndexInclusive);
                throw failStatefulCarrierCommit(context, throwable);
            }
            out.writerIndex(startIndexInclusive);
            throw failTransportConnection(context, "outbound-wrap", throwable);
        }
    }

    private static void sendServerCacheScopeAfterLoginBoundary(ChannelHandlerContext context, Packet<?> packet) {
        if (context == null || !(packet instanceof ClientboundLoginPacket)) {
            return;
        }
        // ensure server scope after login barrier
        context.channel().eventLoop().execute(
                () -> sendServerCacheScopeWithRetry(context, "server_cache_scope_after_login_boundary", 0)
        );
    }

    private static void sendServerCacheScopeWithRetry(ChannelHandlerContext context, String reason, int attempt) {
        if (context == null || context.channel() == null || !context.channel().isOpen() || !context.channel().isActive()) {
            return;
        }
        String resolvedReason = attempt <= 0 ? reason : reason + "_retry_" + attempt;
        if (ChunkTransportControlFrameSender.sendServerCacheScope(context.channel(), resolvedReason)) {
            return;
        }
        if (attempt >= SERVER_CACHE_SCOPE_MAX_RETRY_ATTEMPTS) {
            return;
        }
        context.channel().eventLoop().schedule(
                () -> sendServerCacheScopeWithRetry(context, reason, attempt + 1),
                SERVER_CACHE_SCOPE_RETRY_DELAY_MILLIS,
                TimeUnit.MILLISECONDS
        );
    }


    // bypass
    private static void recordOutboundBypassStats(
            ChannelHandlerContext context,
            String protocolName,
            int byteLength,
            int packetCount
    ) {
        ChannelTransportTelemetry.recordOutboundBypass(protocolName, byteLength);
        ChannelBandwidthStats stats = ServerBandwidthStatsRegistry.getOrCreate(context);
        if (stats != null) {
            stats.recordOutboundBypass(byteLength, packetCount);
        }
    }

    private static void recordOutboundTransportStats(
            ChannelHandlerContext context,
            String protocolName,
            ChannelTransportPacketCodec.WrappedTransportFrame wrappedFrame
    ) {
        ChannelTransportTelemetry.recordOutboundWrap(protocolName, wrappedFrame);
        ChannelBandwidthStats stats = ServerBandwidthStatsRegistry.getOrCreate(context);
        if (stats != null && wrappedFrame != null) {
            stats.recordOutboundTransportFrame(wrappedFrame.transportFrameLength(), 1);
        }
    }


    // in
    private static void recordInboundTransportStats(
            ChannelHandlerContext context,
            String protocolName,
            ChannelTransportPacketCodec.UnwrappedTransportFrame unwrappedFrame
    ) {
        ChannelTransportTelemetry.recordInboundUnwrap(protocolName, unwrappedFrame);
        ChannelBandwidthStats stats = ServerBandwidthStatsRegistry.getOrCreate(context);
        if (stats != null && unwrappedFrame != null) {
            stats.recordInboundTransportFrame(unwrappedFrame.inboundFrameBytes(), 1);
        }
    }

    // Decode direct transport frames before vanilla packet id decoding.
    public static <T extends PacketListener> boolean tryDecodeInboundTransportFrame(
            ChannelHandlerContext context,
            ByteBuf in,
            List<Object> out,
            PacketDecoderFlowAccess packetDecoderFlowAccess
    ) throws Exception {
        NettySpikeProbe.BO_Diag_nettyMSPT(context);
        if (context == null
                || in == null
                || out == null
                || packetDecoderFlowAccess == null
                || !in.isReadable()) {
            return false;
        }

        byte[] inboundPacketBytes = ByteBufUtil.getBytes(in, in.readerIndex(), in.readableBytes(), false);
        if (!ChannelTransportRuntimeGuard.isTransportAvailable()
                || !shouldUseTransportForCurrentProtocol(readProtocolName(context))) {
            return false;
        }

        if (ChunkTransportDispatcher.looksLikeChunkTransportEnvelope(inboundPacketBytes)) {
            ChannelTransportPacketCodec.UnwrappedTransportFrame directEnvelopeFrame =
                    new ChannelTransportPacketCodec.UnwrappedTransportFrame(
                            ChannelTransportPacketCodec.FrameKind.SINGLE,
                            List.of(inboundPacketBytes),
                            inboundPacketBytes.length,
                            1,
                            inboundPacketBytes.length,
                            inboundPacketBytes.length,
                            null
                    );
            decodeInboundPacketsIntoOutput(context, directEnvelopeFrame, inboundPacketBytes, out, packetDecoderFlowAccess);
            in.readerIndex(in.writerIndex());
            recordInboundTransportStats(context, readProtocolName(context), directEnvelopeFrame);
            return true;
        }

        ChannelTransportSession transportSession = ChannelTransportStateManager.getOrCreateSession(context.channel());
        if (handleInboundStreamingControlFrame(context, packetDecoderFlowAccess, transportSession, inboundPacketBytes)) {
            in.readerIndex(in.writerIndex());
            return true;
        }
        ChannelTransportPacketCodec.UnwrappedTransportFrame unwrappedFrame;
        try {
            unwrappedFrame = KineticChannel.tryUnpackInboundPacket(transportSession, inboundPacketBytes);
        } catch (ChannelTransportPacketCodec.StreamingRecoveryException exception) {
            requestInboundStreamingRecovery(context, packetDecoderFlowAccess, transportSession, exception);
            in.readerIndex(in.writerIndex());
            return true;
        }
        if (unwrappedFrame == null) {
            return false;
        }

        acknowledgeEmbeddedStreamingBoundary(context, packetDecoderFlowAccess, transportSession, unwrappedFrame);
        decodeInboundPacketsIntoOutput(context, unwrappedFrame, inboundPacketBytes, out, packetDecoderFlowAccess);
        in.readerIndex(in.writerIndex());
        recordInboundTransportStats(context, readProtocolName(context), unwrappedFrame);
        return true;
    }

    public static <T extends PacketListener> boolean expandDecodedTransportCarrierPackets(
            ChannelHandlerContext context,
            List<Object> out,
            int outputSizeBeforeDecode,
            PacketDecoderFlowAccess packetDecoderFlowAccess
    ) throws Exception {
        NettySpikeProbe.BO_Diag_nettyMSPT(context);
        if (context == null
                || out == null
                || packetDecoderFlowAccess == null
                || out.size() <= outputSizeBeforeDecode) {
            return false;
        }
        if (!ChannelTransportRuntimeGuard.isTransportAvailable()
                || !shouldUseTransportForCurrentProtocol(readProtocolName(context))) {
            return false;
        }

        boolean expandedAny = false;
        for (int index = outputSizeBeforeDecode; index < out.size(); index++) {
            // Some carriers are decoded by vanilla first; replace them at RETURN.
            byte[] transportFrameBytes = copyTransportPayloadBytes(out.get(index));
            if (transportFrameBytes == null) {
                continue;
            }

            ChannelTransportFragmentReassembler.ReceiveResult fragmentResult =
                    ChannelTransportStateManager.acceptInboundFragment(context.channel(), transportFrameBytes);
            if (fragmentResult.kind() == ChannelTransportFragmentReassembler.ReceiveResult.Kind.INCOMPLETE) {
                expandedAny = true;
                index = replaceDecodedCarrierWithRestoredPackets(out, index, List.of());
                continue;
            }
            if (fragmentResult.kind() == ChannelTransportFragmentReassembler.ReceiveResult.Kind.COMPLETE) {
                transportFrameBytes = fragmentResult.transportFrameBytes();
            }

            ChannelTransportSession transportSession = ChannelTransportStateManager.getOrCreateSession(context.channel());
            ChannelTransportPacketCodec.UnwrappedTransportFrame unwrappedFrame;
            try {
                if (handleInboundStreamingControlFrame(
                        context,
                        packetDecoderFlowAccess,
                        transportSession,
                        transportFrameBytes
                )) {
                    expandedAny = true;
                    index = replaceDecodedCarrierWithRestoredPackets(out, index, List.of());
                    continue;
                }
                unwrappedFrame = KineticChannel.tryUnpackInboundPacket(transportSession, transportFrameBytes);
            } catch (ChannelTransportPacketCodec.StreamingRecoveryException exception) {
                requestInboundStreamingRecovery(context, packetDecoderFlowAccess, transportSession, exception);
                expandedAny = true;
                index = replaceDecodedCarrierWithRestoredPackets(out, index, List.of());
                continue;
            } catch (RuntimeException exception) {
                logInboundCarrierFailure(context, transportFrameBytes, exception);
                throw exception;
            }
            if (unwrappedFrame == null) {
                continue;
            }
            logInboundCarrierTrace(context, packetDecoderFlowAccess, transportFrameBytes, unwrappedFrame);
            acknowledgeEmbeddedStreamingBoundary(context, packetDecoderFlowAccess, transportSession, unwrappedFrame);

            List<Object> restoredPackets = new ArrayList<>();
            decodeInboundPacketsIntoOutput(context, unwrappedFrame, transportFrameBytes, restoredPackets, packetDecoderFlowAccess);
            recordInboundTransportStats(context, readProtocolName(context), unwrappedFrame);
            expandedAny = true;
            index = replaceDecodedCarrierWithRestoredPackets(out, index, restoredPackets);
        }
        return expandedAny;
    }

    private static void acknowledgeEmbeddedStreamingBoundary(
            ChannelHandlerContext context,
            PacketDecoderFlowAccess packetDecoderFlowAccess,
            ChannelTransportSession transportSession,
            ChannelTransportPacketCodec.UnwrappedTransportFrame unwrappedFrame
    ) {
        if (unwrappedFrame == null || !unwrappedFrame.streamingEpochComplete()) {
            return;
        }
        ChannelTransportStreamingEpochCoordinator.boundaryReceived(
                context.channel(),
                unwrappedFrame.streamingEpoch(),
                unwrappedFrame.streamingSequence()
        );
        PacketFlow inboundFlow = packetDecoderFlowAccess.bandwidthoptimizer$getPacketFlow();
        PacketFlow responseFlow = inboundFlow == PacketFlow.CLIENTBOUND
                ? PacketFlow.SERVERBOUND
                : PacketFlow.CLIENTBOUND;
        ChannelFuture acknowledgement = writeTransportCarrierPacketToPipeline(
                context.channel(),
                responseFlow,
                ChannelTransportStreamingControlCodec.encodeEpochOk(
                        unwrappedFrame.streamingEpoch(),
                        unwrappedFrame.streamingSequence()
                )
        );
        observeStreamingEpochAcknowledgement(context.channel(), unwrappedFrame, acknowledgement);
        transportSession.resetInboundStreamingEpoch();
    }

    private static void observeStreamingEpochAcknowledgement(
            Channel channel,
            ChannelTransportPacketCodec.UnwrappedTransportFrame frame,
            ChannelFuture acknowledgement
    ) {
        if (acknowledgement == null) {
            Bandwidthoptimizer.LOGGER.warn("[Transport] Failed to submit streaming epoch acknowledgement epoch={} sequence={}", frame.streamingEpoch(), frame.streamingSequence());
            channel.close();
            return;
        }
        acknowledgement.addListener(future -> {
            if (!future.isSuccess()) {
                Bandwidthoptimizer.LOGGER.warn("[Transport] Streaming epoch acknowledgement write failed epoch={} sequence={}", frame.streamingEpoch(), frame.streamingSequence(), future.cause());
                channel.close();
            } else {
                ChannelTransportStreamingEpochCoordinator.acknowledgementSent(
                        channel,
                        frame.streamingEpoch(),
                        frame.streamingSequence()
                );
            }
        });
    }

    private static boolean handleInboundStreamingControlFrame(
            ChannelHandlerContext context,
            PacketDecoderFlowAccess packetDecoderFlowAccess,
            ChannelTransportSession transportSession,
            byte[] transportFrameBytes
    ) {
        ChannelTransportStreamingControlCodec.ControlMessage message =
                ChannelTransportStreamingControlCodec.tryDecodeControlMessage(transportFrameBytes);
        if (message == null) {
            return false;
        }
        PacketFlow flow = packetDecoderFlowAccess.bandwidthoptimizer$getPacketFlow();
        PacketFlow responseFlow = flow == PacketFlow.CLIENTBOUND
                ? PacketFlow.SERVERBOUND
                : PacketFlow.CLIENTBOUND;
        if (message instanceof ChannelTransportStreamingControlCodec.EpochComplete complete) {
            if (!transportSession.acceptInboundStreamingEpochComplete(complete.epoch(), complete.lastSequence())) {
                requestInboundStreamingRecovery(
                        context,
                        transportSession,
                        transportSession.inboundStreamingRecoveryPoint(),
                        responseFlow
                );
                return true;
            }
            writeTransportCarrierPacketToPipeline(context.channel(), responseFlow,
                    ChannelTransportStreamingControlCodec.encodeEpochOk(complete.epoch(), complete.lastSequence()));
            transportSession.resetInboundStreamingEpoch();
            return true;
        }
        if (message instanceof ChannelTransportStreamingControlCodec.EpochOk ok) {
            ChannelTransportStreamingEpochCoordinator.acknowledgementReceived(context.channel(), ok.epoch(), ok.lastSequence());
            if (ChannelTransportStreamingEpochCoordinator.consumeLateAcknowledgement(
                    context.channel(),
                    transportSession,
                    ok.epoch(),
                    ok.lastSequence()
            )) {
                return true;
            }
            transportSession.acceptOutboundStreamingEpochOk(ok.epoch(), ok.lastSequence());
            transportSession.restartOutboundStreamingEpoch();
            ChannelTransportStreamingEpochCoordinator.releasedByAcknowledgement(
                    context.channel(),
                    ok.epoch(),
                    ok.lastSequence()
            );
            return true;
        }
        if (message instanceof ChannelTransportStreamingControlCodec.EpochReset reset) {
            transportSession.resetInboundStreamingEpoch();
            if (ChannelTransportStateManager.isTestStreamingEnabled()) {
                Bandwidthoptimizer.LOGGER.info(
                        "[StreamingRecoveryTest] Applied inbound epoch reset, nextEpoch={}",
                        reset.epoch()
                );
            }
            return true;
        }
        ChannelTransportStreamingControlCodec.RecoveryRequest request =
                (ChannelTransportStreamingControlCodec.RecoveryRequest) message;
        List<ChannelTransportSession.StreamingFallbackBatch> fallbackBatches =
                transportSession.fallbackOutboundStreamingBatches(request.epoch(), request.expectedSequence());
        if (ChannelTransportStateManager.isTestStreamingEnabled()) {
            Bandwidthoptimizer.LOGGER.info(
                    "[StreamingRecoveryTest] Received recovery request, epoch={}, expectedSequence={}, fallbackBatches={}",
                    request.epoch(),
                    request.expectedSequence(),
                    fallbackBatches.size()
            );
        }
        if (fallbackBatches.isEmpty()) {
            ChannelTransportRuntimeGuard.reportStreamingRecoveryFailure(
                    "streaming-recovery-retention-missing",
                    context.channel(),
                    responseFlow.name(),
                    request.epoch(),
                    request.expectedSequence(),
                    0,
                    new IllegalStateException("No retained streaming suffix for recovery request")
            );
            context.close();
            return true;
        }
        ChannelTransportStreamingEpochGate.closeForEpoch(
                context.channel(),
                request.epoch(),
                fallbackBatches.get(fallbackBatches.size() - 1).sequence()
        );
        for (ChannelTransportSession.StreamingFallbackBatch fallbackBatch : fallbackBatches) {
            ChannelTransportPacketCodec.WrappedTransportFrame fallbackFrame =
                    ChannelTransportPacketCodec.wrapStreamingFallbackBatch(transportSession, fallbackBatch);
            writeTransportCarrierPacketToPipeline(
                    context.channel(),
                    responseFlow,
                    fallbackFrame.transportFrameBytes()
            );
        }
        transportSession.restartOutboundStreamingEpoch();
        writeTransportCarrierPacketToPipeline(context.channel(), responseFlow,
                ChannelTransportStreamingControlCodec.encodeEpochReset(transportSession.outboundStreamingEpoch()));
        ChannelTransportStreamingEpochGate.release(context.channel());
        if (ChannelTransportStateManager.isTestStreamingEnabled()) {
            Bandwidthoptimizer.LOGGER.info(
                    "[StreamingRecoveryTest] Completed recovery output, previousEpoch={}, nextEpoch={}, fallbackBatches={}",
                    request.epoch(),
                    transportSession.outboundStreamingEpoch(),
                    fallbackBatches.size()
            );
        }
        return true;
    }

    private static void requestInboundStreamingRecovery(
            ChannelHandlerContext context,
            PacketDecoderFlowAccess packetDecoderFlowAccess,
            ChannelTransportSession transportSession,
            ChannelTransportPacketCodec.StreamingRecoveryException exception
    ) {
        PacketFlow inboundFlow = packetDecoderFlowAccess.bandwidthoptimizer$getPacketFlow();
        requestInboundStreamingRecovery(
                context,
                transportSession,
                exception.recoveryRequest(),
                inboundFlow == PacketFlow.CLIENTBOUND ? PacketFlow.SERVERBOUND : PacketFlow.CLIENTBOUND
        );
    }

    private static void requestInboundStreamingRecovery(
            ChannelHandlerContext context,
            ChannelTransportSession transportSession,
            ChannelTransportStreamingControlCodec.RecoveryRequest request,
            PacketFlow responseFlow
    ) {
        if (!transportSession.beginInboundStreamingRecovery(request)) {
            return;
        }
        if (ChannelTransportStateManager.isTestStreamingEnabled()) {
            Bandwidthoptimizer.LOGGER.info(
                    "[StreamingRecoveryTest] Requested recovery, epoch={}, expectedSequence={}",
                    request.epoch(),
                    request.expectedSequence()
            );
        }
        ChannelFuture recoveryRequestWrite = writeTransportCarrierPacketToPipeline(
                context.channel(),
                responseFlow,
                ChannelTransportStreamingControlCodec.encodeRecoveryRequest(request.epoch(), request.expectedSequence())
        );
        if (recoveryRequestWrite == null) {
            ChannelTransportRuntimeGuard.reportStreamingRecoveryFailure(
                    "streaming-recovery-request-not-submitted",
                    context.channel(), responseFlow.name(), request.epoch(), request.expectedSequence(), 0,
                    new IllegalStateException("Recovery request was not submitted")
            );
            context.close();
            return;
        }
        recoveryRequestWrite.addListener(future -> {
            if (!future.isSuccess()) {
                ChannelTransportRuntimeGuard.reportStreamingRecoveryFailure(
                        "streaming-recovery-request-write",
                        context.channel(), responseFlow.name(), request.epoch(), request.expectedSequence(), 0,
                        future.cause()
                );
                context.close();
            }
        });
    }

    // Preserve output order when replacing a carrier with restored packets.
    private static int replaceDecodedCarrierWithRestoredPackets(List<Object> out, int index, List<Object> restoredPackets) {
        Object carrierPacket = out.remove(index);
        releaseTransportPayloadBuffer(carrierPacket);
        List<Object> tailPackets = new ArrayList<>();
        while (index < out.size()) {
            tailPackets.add(out.remove(index));
        }
        for (Object restoredPacket : restoredPackets) {
            out.add(restoredPacket);
        }
        for (Object tailPacket : tailPackets) {
            out.add(tailPacket);
        }
        return index + restoredPackets.size() - 1;
    }

    private static <T extends PacketListener> void decodeInboundPacketsIntoOutput(
            ChannelHandlerContext context,
            ChannelTransportPacketCodec.UnwrappedTransportFrame unwrappedFrame,
            byte[] transportFrameBytes,
            List<Object> out,
            PacketDecoderFlowAccess packetDecoderFlowAccess
    ) throws Exception {
        long decodeStartNanos = NettySpikeProbe.BO_Diag_nettyMSPT_timer(
                context,
                "inbound_decode_restored_output",
                "frameKind=" + (unwrappedFrame == null ? "<null>" : unwrappedFrame.frameKind())
                        + ", packets=" + (unwrappedFrame == null ? 0 : unwrappedFrame.restoredPacketBytesList().size())
        );
        try {
            for (byte[] transportRestoredPacketBytes : unwrappedFrame.restoredPacketBytesList()) {
                long packetStartNanos = ChunkLoadDelayProbe.isEnabled() ? System.nanoTime() : 0L;
                long chunkRestoreStartNanos = ChunkLoadDelayProbe.isEnabled() ? System.nanoTime() : 0L;
                // Chunk envelopes restore before vanilla packet decoding.
                ChunkInboundDecodeResult inboundDecodeResult =
                        ChunkTransportDispatcher.tryDecodeInboundPacket(context, transportRestoredPacketBytes);
                ChunkLoadDelayProbe.logStage(
                        context,
                        null,
                        "client",
                        "transport_chunk_restore",
                        ChunkLoadDelayProbe.elapsedMillisSince(chunkRestoreStartNanos),
                        transportRestoredPacketBytes.length,
                        "shouldDecodeVanilla=" + inboundDecodeResult.shouldDecodeVanillaPacket()
                );
                if (!inboundDecodeResult.shouldDecodeVanillaPacket()) {
                    continue;
                }
                byte[] restoredPacketBytes = inboundDecodeResult.restoredPacketBytes();
                long captureStartNanos = ChunkLoadDelayProbe.isEnabled() ? System.nanoTime() : 0L;
                ChannelCapturedFrame pendingInboundFrame = beginInboundCapture(context, restoredPacketBytes);
                ChunkLoadDelayProbe.logStage(
                        context,
                        null,
                        "client",
                        "inbound_capture_begin",
                        ChunkLoadDelayProbe.elapsedMillisSince(captureStartNanos),
                        restoredPacketBytes.length,
                        ""
                );
                int outputSizeBeforeDecode = out.size();
                Packet<? super T> restoredPacket;
                long vanillaDecodeStartNanos = ChunkLoadDelayProbe.isEnabled() ? System.nanoTime() : 0L;
                try {
                    restoredPacket = decodeRestoredPacket(context, restoredPacketBytes, packetDecoderFlowAccess);
                } catch (Exception exception) {
                    logRestoredPacketDecodeFailure(context, packetDecoderFlowAccess, restoredPacketBytes, exception);
                    throw exception;
                }
                ChunkLoadDelayProbe.logStage(
                        context,
                        null,
                        "client",
                        "vanilla_packet_decode",
                        ChunkLoadDelayProbe.elapsedMillisSince(vanillaDecodeStartNanos),
                        restoredPacketBytes.length,
                        restoredPacket == null ? "<null>" : restoredPacket.getClass().getName()
                );
            logRestoredPacketTrace(context, packetDecoderFlowAccess, restoredPacketBytes, restoredPacket);
            out.add(restoredPacket);
            PacketClassTraceDiagnostic.recordInboundTransport(
                    context,
                    readProtocolName(context),
                    packetDecoderFlowAccess.bandwidthoptimizer$getPacketFlow(),
                    restoredPacket,
                    transportFrameBytes,
                    restoredPacketBytes,
                    unwrappedFrame.frameKind(),
                    unwrappedFrame.restoredPacketCount()
            );

                long observeStartNanos = ChunkLoadDelayProbe.isEnabled() ? System.nanoTime() : 0L;
                ChannelCaptureHooks.finishInboundDecode(context, pendingInboundFrame, out, outputSizeBeforeDecode);
                ChunkLoadDelayProbe.logStage(
                        context,
                        null,
                        "client",
                        "inbound_observe_capture_finish",
                        ChunkLoadDelayProbe.elapsedMillisSince(observeStartNanos),
                        restoredPacketBytes.length,
                        restoredPacket == null ? "<null>" : restoredPacket.getClass().getName()
                );
                ChunkLoadDelayProbe.logStage(
                        context,
                        null,
                        "client",
                        "restored_packet_total",
                        ChunkLoadDelayProbe.elapsedMillisSince(packetStartNanos),
                        restoredPacketBytes.length,
                        restoredPacket == null ? "<null>" : restoredPacket.getClass().getName()
                );
            }
        } finally {
            NettySpikeProbe.BO_Diag_nettyMSPT_timer(
                    context,
                    "inbound_decode_restored_output",
                    decodeStartNanos,
                    "frameKind=" + (unwrappedFrame == null ? "<null>" : unwrappedFrame.frameKind())
                            + ", packets=" + (unwrappedFrame == null ? 0 : unwrappedFrame.restoredPacketBytesList().size())
            );
        }
    }

    private static ChannelCapturedFrame beginInboundCapture(ChannelHandlerContext context, byte[] restoredPacketBytes) {
        ByteBuf restoredBuffer = Unpooled.wrappedBuffer(restoredPacketBytes);
        try {
            return ChannelCaptureHooks.beginInboundPreDecode(context, restoredBuffer);
        } finally {
            restoredBuffer.release();
        }
    }


    public static boolean writeTransportCarrierPacket(
            ChannelHandlerContext context,
            PacketFlow packetFlow,
            ByteBuf out,
            byte[] transportFrameBytes
    ) {
        if (context == null || packetFlow == null || out == null || transportFrameBytes == null) {
            return false;
        }
        int payloadLimitBytes = payloadLimitBytes(packetFlow);
        List<byte[]> carrierPayloads = ChannelTransportFragmentCodec.fragmentTransportFrame(
                transportFrameBytes,
                payloadLimitBytes,
                ChannelTransportStateManager.nextOutboundFragmentStreamId(context.channel())
        );
        return ChannelTransportInlineFrameWriter.writeIndependentPacketBodies(
                context,
                out,
                carrierPayloads,
                (packetBody, carrierPayload) -> writeSingleTransportCarrierPacket(
                        context,
                        packetFlow,
                        packetBody,
                        carrierPayload
                )
        );
    }

    private static boolean writeSingleTransportCarrierPacket(
            ChannelHandlerContext context,
            PacketFlow packetFlow,
            ByteBuf out,
            byte[] transportFrameBytes
    ) {
        long carrierWriteStartNanos = NettySpikeProbe.BO_Diag_nettyMSPT_timer(
                context,
                "transport_carrier_write",
                "flow=" + packetFlow + ", frameBytes=" + transportFrameBytes.length
        );
        ConnectionProtocol protocol = readConnectionProtocol(context);
        FriendlyByteBuf payloadBuffer = new FriendlyByteBuf(Unpooled.wrappedBuffer(transportFrameBytes));
        FriendlyByteBuf outputBuffer = new FriendlyByteBuf(out);
        try {
            Packet<?> carrierPacket = packetFlow == PacketFlow.CLIENTBOUND
                    ? new ClientboundCustomPayloadPacket(TRANSPORT_PAYLOAD_ID, payloadBuffer)
                    : new ServerboundCustomPayloadPacket(TRANSPORT_PAYLOAD_ID, payloadBuffer);
            int packetId = protocol.getPacketId(packetFlow, carrierPacket);
            if (packetId < 0) {
                return false;
            }
            logOutboundCarrierTrace(context, packetFlow, packetId, transportFrameBytes);
            outputBuffer.writeVarInt(packetId);
            carrierPacket.write(outputBuffer);
            return true;
        } finally {
            NettySpikeProbe.BO_Diag_nettyMSPT_timer(
                    context,
                    "transport_carrier_write",
                    carrierWriteStartNanos,
                    "flow=" + packetFlow + ", frameBytes=" + transportFrameBytes.length
            );
            payloadBuffer.release();
        }
    }


    public static ChannelFuture writeTransportCarrierPacketToPipeline(
            Channel channel,
            PacketFlow packetFlow,
            byte[] transportFrameBytes
    ) {
        if (channel == null || packetFlow == null || transportFrameBytes == null) {
            return null;
        }
        if (transportFrameBytes.length <= payloadLimitBytes(packetFlow)
                && ChannelTransportStateManager.consumeTestClientboundStreamingDrop(
                channel,
                packetFlow,
                transportFrameBytes
        )) {
            return channel.newSucceededFuture();
        }
        List<byte[]> carrierPayloads = ChannelTransportFragmentCodec.fragmentTransportFrame(
                transportFrameBytes,
                payloadLimitBytes(packetFlow),
                ChannelTransportStateManager.nextOutboundFragmentStreamId(channel)
        );

        ChannelHandlerContext encoderContext = channel.pipeline().context("encoder");
        long pipelineWriteStartNanos = NettySpikeProbe.BO_Diag_nettyMSPT_timer(
                encoderContext,
                "transport_pipeline_write",
                "flow=" + packetFlow + ", frameBytes=" + transportFrameBytes.length
        );
        ChannelPromise aggregatePromise = channel.newPromise();
        AtomicInteger remainingWrites = new AtomicInteger(carrierPayloads.size());
        AtomicBoolean failed = new AtomicBoolean();
        try {
            for (byte[] carrierPayload : carrierPayloads) {
                FriendlyByteBuf payloadBuffer = new FriendlyByteBuf(Unpooled.wrappedBuffer(carrierPayload));
                try {
                    Packet<?> carrierPacket = packetFlow == PacketFlow.CLIENTBOUND
                            ? new ClientboundCustomPayloadPacket(TRANSPORT_PAYLOAD_ID, payloadBuffer)
                            : new ServerboundCustomPayloadPacket(TRANSPORT_PAYLOAD_ID, payloadBuffer);
                    logOutboundCarrierTrace(encoderContext, packetFlow, -1, carrierPayload);
                    ChannelFuture writeFuture = channel.write(carrierPacket);
                    writeFuture.addListener(future -> {
                        payloadBuffer.release();
                        if (!future.isSuccess()) {
                            if (failed.compareAndSet(false, true)) {
                                Throwable cause = future.cause();
                                aggregatePromise.tryFailure(cause != null ? cause : new IllegalStateException("Fragment carrier write failed"));
                            }
                        } else if (remainingWrites.decrementAndGet() == 0 && !failed.get()) {
                            aggregatePromise.trySuccess();
                        }
                    });
                } catch (RuntimeException exception) {
                    payloadBuffer.release();
                    throw exception;
                }
            }
            channel.flush();
            if (ChannelTransportPacketCodec.isCompletedStreamingFrame(transportFrameBytes)) {
                ChannelTransportStreamingEpochCoordinator.boundaryQueued(channel);
            }
            return aggregatePromise;
        } finally {
            NettySpikeProbe.BO_Diag_nettyMSPT_timer(
                    encoderContext,
                    "transport_pipeline_write",
                    pipelineWriteStartNanos,
                    "flow=" + packetFlow + ", frameBytes=" + transportFrameBytes.length
            );
        }
    }

    private static int payloadLimitBytes(PacketFlow packetFlow) {
        return packetFlow == PacketFlow.CLIENTBOUND
                ? CLIENTBOUND_CUSTOM_PAYLOAD_MAX_BYTES
                : SERVERBOUND_CUSTOM_PAYLOAD_MAX_BYTES;
    }


    public static boolean shouldBypassServerboundCarrierByInputSize(PacketFlow packetFlow, int transportInputBytes) {
        // Bounded outer fragments replace the old custom-payload size guard.
        return false;
    }

    // Stateful wraps must be committed.
    public static boolean shouldBypassUnprofitableCarrier(ChannelTransportPacketCodec.WrappedTransportFrame wrappedFrame) {
        return false;
    }

    private static IllegalStateException failStatefulCarrierCommit(ChannelHandlerContext context, Throwable throwable) {
        IllegalStateException failure = throwable instanceof IllegalStateException
                ? (IllegalStateException) throwable
                : new IllegalStateException("Stateful transport output was not committed", throwable);
        ChannelTransportRuntimeGuard.reportRuntimeFailure("outbound-carrier-commit", failure);
        if (context != null) {
            ConnectionDisconnectClassifier.markBoInitiatedClose(
                    context.channel(),
                    "outbound-carrier-commit",
                    failure
            );
            context.close();
        }
        return failure;
    }

    private static IllegalStateException failTransportConnection(
            ChannelHandlerContext context,
            String stageName,
            Throwable throwable
    ) {
        IllegalStateException failure = throwable instanceof IllegalStateException
                ? (IllegalStateException) throwable
                : new IllegalStateException("Transport output failed", throwable);
        ChannelTransportRuntimeGuard.reportRuntimeFailure(stageName, failure);
        if (context != null) {
            ConnectionDisconnectClassifier.markBoInitiatedClose(context.channel(), stageName, failure);
            context.close();
        }
        return failure;
    }

    public static void recordCommittedOutboundPacketStream(
            ChannelHandlerContext context,
            String packetClassName,
            int packetId,
            byte[] encodedPacketBytes
    ) {
        ChannelCaptureHooks.captureOutboundPacketStream(context, packetClassName, packetId, encodedPacketBytes);
    }

    public static void recordCommittedOutboundPacketStream(
            ChannelHandlerContext context,
            Packet<?> packet,
            byte[] encodedPacketBytes
    ) {
        if (isInternalTransportCarrierPacket(packet)) {
            return;
        }
        ChannelCaptureHooks.captureOutboundPacketStream(context, packet, encodedPacketBytes);
    }

    public static boolean isInternalTransportCarrierPacket(Packet<?> packet) {
        if (packet instanceof ClientboundCustomPayloadPacket customPayloadPacket) {
            return TRANSPORT_PAYLOAD_ID.equals(customPayloadPacket.getIdentifier());
        }
        if (packet instanceof ServerboundCustomPayloadPacket customPayloadPacket) {
            return TRANSPORT_PAYLOAD_ID.equals(customPayloadPacket.getIdentifier());
        }
        return false;
    }


    private static PacketFlow resolvePacketFlow(
            PacketEncoderFlowAccess packetEncoderFlowAccess,
            ConnectionProtocol protocol,
            Packet<?> packet
    ) {
        if (packetEncoderFlowAccess != null) {
            PacketFlow packetFlow = packetEncoderFlowAccess.bandwidthoptimizer$getPacketFlow();
            if (packetFlow != null) {
                return packetFlow;
            }
        }
        if (protocol == null || packet == null) {
            return null;
        }
        if (protocol.getPacketId(PacketFlow.CLIENTBOUND, packet) >= 0) {
            return PacketFlow.CLIENTBOUND;
        }
        if (protocol.getPacketId(PacketFlow.SERVERBOUND, packet) >= 0) {
            return PacketFlow.SERVERBOUND;
        }
        return null;
    }

    private static byte[] copyTransportPayloadBytes(Object decodedPacket) {
        FriendlyByteBuf payloadBuffer = null;
        if (decodedPacket instanceof ClientboundCustomPayloadPacket packet) {
            if (!TRANSPORT_PAYLOAD_ID.equals(packet.getIdentifier())) {
                return null;
            }
            payloadBuffer = ((ClientboundCustomPayloadPacketAccessor) packet).bandwidthoptimizer$data();
        } else if (decodedPacket instanceof ServerboundCustomPayloadPacket packet) {
            if (!TRANSPORT_PAYLOAD_ID.equals(packet.getIdentifier())) {
                return null;
            }
            payloadBuffer = ((ServerboundCustomPayloadPacketAccessor) packet).bandwidthoptimizer$data();
        } else {
            return null;
        }

        byte[] payloadBytes = new byte[payloadBuffer.readableBytes()];
        payloadBuffer.getBytes(payloadBuffer.readerIndex(), payloadBytes);
        return payloadBytes;
    }

    private static void releaseTransportPayloadBuffer(Object decodedPacket) {
        FriendlyByteBuf payloadBuffer = null;
        boolean shouldReleasePayloadBuffer = false;
        if (decodedPacket instanceof ClientboundCustomPayloadPacket packet) {
            ClientboundCustomPayloadPacketAccessor accessor = (ClientboundCustomPayloadPacketAccessor) packet;
            payloadBuffer = accessor.bandwidthoptimizer$data();
            shouldReleasePayloadBuffer = true;
        } else if (decodedPacket instanceof ServerboundCustomPayloadPacket packet) {
            payloadBuffer = ((ServerboundCustomPayloadPacketAccessor) packet).bandwidthoptimizer$data();
            shouldReleasePayloadBuffer = true;
        }

        if (shouldReleasePayloadBuffer && payloadBuffer != null) {
            try {
                payloadBuffer.release();
            } catch (RuntimeException ignored) {}
        }
    }

    private static <T extends PacketListener> Packet<? super T> decodeRestoredPacket(
            ChannelHandlerContext context,
            byte[] restoredPacketBytes,
            PacketDecoderFlowAccess packetDecoderFlowAccess
    ) throws IOException {
        ByteBuf restoredBuffer = Unpooled.wrappedBuffer(restoredPacketBytes);
        try {
            FriendlyByteBuf friendlyBuffer = new FriendlyByteBuf(restoredBuffer);
            int readableBytes = friendlyBuffer.readableBytes();
            int packetId = friendlyBuffer.readVarInt();
            ConnectionProtocol protocol = readConnectionProtocol(context);
            PacketFlow packetFlow = packetDecoderFlowAccess.bandwidthoptimizer$getPacketFlow();
            @SuppressWarnings("unchecked")
            Packet<? super T> packet = (Packet<? super T>) protocol.createPacket(packetFlow, packetId, friendlyBuffer);
            if (packet == null) {
                throw new IOException("Bad packet id " + packetId);
            }

            if (friendlyBuffer.readableBytes() > 0) {
                throw new IOException(
                        "Restored transport packet left "
                                + friendlyBuffer.readableBytes()
                                + " extra bytes after decode. packetClass="
                                + packet.getClass().getName()
                                + ", readableBytesBeforeDecode="
                                + readableBytes
                );
            }

            return packet;
        } finally {
            restoredBuffer.release();
        }
    }

    private static String readProtocolName(ChannelHandlerContext context) {
        Object protocol = context.channel().attr(Connection.ATTRIBUTE_PROTOCOL).get();
        return protocol == null ? "null" : String.valueOf(protocol);
    }

    private static ConnectionProtocol readConnectionProtocol(ChannelHandlerContext context) {
        ConnectionProtocol protocol = context.channel().attr(Connection.ATTRIBUTE_PROTOCOL).get();
        if (protocol == null) {
            throw new IllegalStateException("Missing ConnectionProtocol on inbound transport decode");
        }
        return protocol;
    }

    // protocol check
    private static ConnectionProtocol readConnectionProtocolOrNull(ChannelHandlerContext context) {
        if (context == null || context.channel() == null) {
            return null;
        }
        return context.channel().attr(Connection.ATTRIBUTE_PROTOCOL).get();
    }

    private static void recordDirectPacketTrace(
            ChannelHandlerContext context,
            String reason,
            String protocolName,
            Packet<?> packet,
            PacketFlow packetFlow,
            byte[] packetBytes
    ) {
        ChannelTransportBypassRankLogger.recordPacket(
                context,
                reason,
                protocolName,
                packet,
                packetFlow,
                packetBytes
        );
        PacketClassTraceDiagnostic.recordOutboundDirect(
                context,
                protocolName,
                packet,
                packetFlow,
                packetBytes,
                reason
        );
        if (!ChannelTransportHookDiagnosticProbe.BO_Diag_transportTraceJournal()) {
            return;
        }
        ChannelTransportTraceJournal.record(
                context,
                "DirectPacket",
                "direct:" + packetClassName(packet),
                "channel=" + channelIdText(context)
                        + ", flow=" + packetFlow
                        + ", protocol=" + protocolName
                        + ", reason=" + reason
                        + ", packetClass=" + packetClassName(packet)
                        + ", rawPacketId=" + tryReadLeadingVarInt(packetBytes)
                        + ", packetBytes=" + lengthOf(packetBytes)
                        + ", packetPrefix=" + hexPrefix(packetBytes)
        );
    }

    private static void logOutboundTransportTrace(
            ChannelHandlerContext context,
            String protocolName,
            Packet<?> packet,
            PacketFlow packetFlow,
            byte[] originalPacketBytes,
            byte[] transportInputPacketBytes,
            ChannelTransportPacketCodec.WrappedTransportFrame wrappedFrame,
            boolean chunkProtocolApplied
    ) {
        if (wrappedFrame == null)
            return;

        PacketClassTraceDiagnostic.recordOutboundTransport(
                context,
                protocolName,
                packet,
                packetFlow,
                originalPacketBytes,
                wrappedFrame
        );

        if (!ChannelTransportHookDiagnosticProbe.BO_Diag_transportTraceJournal())
            return;

        ChannelTransportOperationTelemetry telemetry = wrappedFrame.telemetry();
        ChannelTransportTraceJournal.record(
                context,
                "OutboundPacket",
                "outbound:" + packetClassName(packet),
                "channel=" + channelIdText(context)
                        + ", flow=" + packetFlow
                        + ", protocol=" + protocolName
                        + ", packetClass=" + packetClassName(packet)
                        + ", tablePacketId=" + lookupProtocolPacketId(context, packetFlow, packet)
                        + ", rawPacketId=" + tryReadLeadingVarInt(originalPacketBytes)
                        + ", inputPacketId=" + tryReadLeadingVarInt(transportInputPacketBytes)
                        + ", rawBytes=" + lengthOf(originalPacketBytes)
                        + ", inputBytes=" + lengthOf(transportInputPacketBytes)
                        + ", chunkApplied=" + chunkProtocolApplied
                        + ", frameKind=" + wrappedFrame.frameKind()
                        + ", frameBytes=" + wrappedFrame.transportFrameLength()
                        + ", entry=" + entryKindText(telemetry)
                        + ", rawPrefix=" + hexPrefix(originalPacketBytes)
                        + ", inputPrefix=" + hexPrefix(transportInputPacketBytes)
                        + ", framePrefix=" + hexPrefix(wrappedFrame.transportFrameBytes())
        );
        long traceIndex = nextTransportTraceIndex(OUTBOUND_TRANSPORT_TRACE_COUNTER);
        if (traceIndex < 0L) {
            return;
        }
        DiagnosticLog.info(DiagnosticToolRegistry.Tool.TRANSPORT_TRACE_JOURNAL, "event=outbound_packet index={}, channel={}, flow={}, protocol={}, packetClass={}, tablePacketId={}, rawPacketId={}, inputPacketId={}, rawBytes={}, inputBytes={}, chunkApplied={}, frameKind={}, frameBytes={}, bodyBytes={}, entry={}, mappingStageBytes={}, exactRef={}, templateRef={}, exactAdd={}, templateAdd={}, exactRemove={}, templateRemove={}, rawPrefix={}, inputPrefix={}, framePrefix={}",
                traceIndex,
                channelIdText(context),
                packetFlow,
                protocolName,
                packetClassName(packet),
                lookupProtocolPacketId(context, packetFlow, packet),
                tryReadLeadingVarInt(originalPacketBytes),
                tryReadLeadingVarInt(transportInputPacketBytes),
                lengthOf(originalPacketBytes),
                lengthOf(transportInputPacketBytes),
                chunkProtocolApplied,
                wrappedFrame.frameKind(),
                wrappedFrame.transportFrameLength(),
                wrappedFrame.zstdBodyBytes(),
                entryKindText(telemetry),
                telemetry == null ? -1 : telemetry.mappingStageBytes(),
                telemetry == null ? -1 : telemetry.exactReferenceCount(),
                telemetry == null ? -1 : telemetry.templateReferenceCount(),
                telemetry == null ? -1 : telemetry.exactAdditionCount(),
                telemetry == null ? -1 : telemetry.templateAdditionCount(),
                telemetry == null ? -1 : telemetry.exactRemovalCount(),
                telemetry == null ? -1 : telemetry.templateRemovalCount(),
                hexPrefix(originalPacketBytes),
                hexPrefix(transportInputPacketBytes),
                hexPrefix(wrappedFrame.transportFrameBytes())
        );
    }

    private static void logOutboundCarrierTrace(
            ChannelHandlerContext context,
            PacketFlow packetFlow,
            int carrierPacketId,
            byte[] transportFrameBytes
    ) {
        if (!ChannelTransportHookDiagnosticProbe.BO_Diag_transportTraceJournal()) {
            return;
        }
        ChannelTransportTraceJournal.record(
                context,
                "OutboundCarrier",
                "carrier:" + packetFlow,
                "channel=" + channelIdText(context)
                        + ", flow=" + packetFlow
                        + ", protocol=" + readProtocolName(context)
                        + ", carrierPacketId=" + carrierPacketId
                        + ", frameBytes=" + lengthOf(transportFrameBytes)
                        + ", framePrefix=" + hexPrefix(transportFrameBytes)
        );
        long traceIndex = nextTransportTraceIndex(OUTBOUND_CARRIER_TRACE_COUNTER);
        if (traceIndex < 0L) {
            return;
        }
        DiagnosticLog.info(DiagnosticToolRegistry.Tool.TRANSPORT_TRACE_JOURNAL, "event=outbound_carrier index={}, channel={}, flow={}, protocol={}, carrierPacketId={}, frameBytes={}, framePrefix={}",
                traceIndex,
                channelIdText(context),
                packetFlow,
                readProtocolName(context),
                carrierPacketId,
                lengthOf(transportFrameBytes),
                hexPrefix(transportFrameBytes)
        );
    }

    private static void logInboundCarrierTrace(
            ChannelHandlerContext context,
            PacketDecoderFlowAccess packetDecoderFlowAccess,
            byte[] transportFrameBytes,
            ChannelTransportPacketCodec.UnwrappedTransportFrame unwrappedFrame
    ) {
        if (unwrappedFrame == null)
            return;

        if (!ChannelTransportHookDiagnosticProbe.BO_Diag_transportTraceJournal())
            return;

        ChannelTransportOperationTelemetry telemetry = unwrappedFrame.telemetry();
        PacketFlow packetFlow = packetDecoderFlowAccess == null ? null : packetDecoderFlowAccess.bandwidthoptimizer$getPacketFlow();

        if (!ChannelTransportHookDiagnosticProbe.BO_Diag_transportTraceJournal())
            return;

        ChannelTransportTraceJournal.record(
                context,
                "InboundCarrier",
                "inbound_carrier:" + packetFlow,
                "channel=" + channelIdText(context)
                        + ", flow=" + packetFlow
                        + ", protocol=" + readProtocolName(context)
                        + ", frameKind=" + unwrappedFrame.frameKind()
                        + ", frameBytes=" + unwrappedFrame.inboundFrameBytes()
                        + ", restoredPackets=" + unwrappedFrame.restoredPacketCount()
                        + ", restoredBytes=" + unwrappedFrame.restoredPacketBytes()
                        + ", entry=" + entryKindText(telemetry)
                        + ", framePrefix=" + hexPrefix(transportFrameBytes)
        );
        long traceIndex = nextTransportTraceIndex(INBOUND_CARRIER_TRACE_COUNTER);
        if (traceIndex < 0L) {
            return;
        }
        DiagnosticLog.info(DiagnosticToolRegistry.Tool.TRANSPORT_TRACE_JOURNAL, "event=inbound_carrier index={}, channel={}, flow={}, protocol={}, frameKind={}, frameBytes={}, bodyBytes={}, restoredPackets={}, restoredBytes={}, entry={}, mappingStageBytes={}, exactRef={}, templateRef={}, exactAdd={}, templateAdd={}, exactRemove={}, templateRemove={}, framePrefix={}",
                traceIndex,
                channelIdText(context),
                packetFlow,
                readProtocolName(context),
                unwrappedFrame.frameKind(),
                unwrappedFrame.inboundFrameBytes(),
                unwrappedFrame.zstdBodyBytes(),
                unwrappedFrame.restoredPacketCount(),
                unwrappedFrame.restoredPacketBytes(),
                entryKindText(telemetry),
                telemetry == null ? -1 : telemetry.mappingStageBytes(),
                telemetry == null ? -1 : telemetry.exactReferenceCount(),
                telemetry == null ? -1 : telemetry.templateReferenceCount(),
                telemetry == null ? -1 : telemetry.exactAdditionCount(),
                telemetry == null ? -1 : telemetry.templateAdditionCount(),
                telemetry == null ? -1 : telemetry.exactRemovalCount(),
                telemetry == null ? -1 : telemetry.templateRemovalCount(),
                hexPrefix(transportFrameBytes)
        );
    }

    private static void logInboundCarrierFailure(
            ChannelHandlerContext context,
            byte[] transportFrameBytes,
            RuntimeException exception
    ) {
        ChannelTransportTraceJournal.record(
                context,
                "InboundCarrierFailure",
                "failure:inbound_carrier",
                "channel=" + channelIdText(context)
                        + ", protocol=" + readProtocolName(context)
                        + ", frameBytes=" + lengthOf(transportFrameBytes)
                        + ", exception=" + exception.getClass().getName()
                        + ", framePrefix=" + hexPrefix(transportFrameBytes)
        );
        DiagnosticLog.error(DiagnosticToolRegistry.Tool.TRANSPORT_TRACE_JOURNAL, "event=inbound_carrier_failure channel={}, protocol={}, frameBytes={}, framePrefix={}",
                channelIdText(context),
                readProtocolName(context),
                lengthOf(transportFrameBytes),
                hexPrefix(transportFrameBytes),
                exception
        );
    }

    private static void logRestoredPacketTrace(
            ChannelHandlerContext context,
            PacketDecoderFlowAccess packetDecoderFlowAccess,
            byte[] restoredPacketBytes,
            Packet<?> restoredPacket
    ) {

        if (!ChannelTransportHookDiagnosticProbe.BO_Diag_transportTraceJournal()) {
            return;
        }
        PacketFlow packetFlow = packetDecoderFlowAccess == null ? null : packetDecoderFlowAccess.bandwidthoptimizer$getPacketFlow();
        ChannelTransportTraceJournal.record(
                context,
                "RestoredPacket",
                "restored:" + packetClassName(restoredPacket),
                "channel=" + channelIdText(context)
                        + ", flow=" + packetFlow
                        + ", protocol=" + readProtocolName(context)
                        + ", packetClass=" + packetClassName(restoredPacket)
                        + ", source=" + ChannelTransportPacketRankSourceResolver.resolveSourceKey(restoredPacket)
                        + ", rawPacketId=" + tryReadLeadingVarInt(restoredPacketBytes)
                        + ", packetBytes=" + lengthOf(restoredPacketBytes)
                        + ", packetPrefix=" + hexPrefix(restoredPacketBytes)
        );
        long traceIndex = nextTransportTraceIndex(INBOUND_RESTORED_PACKET_TRACE_COUNTER);
        if (traceIndex < 0L) {
            return;
        }
        DiagnosticLog.info(DiagnosticToolRegistry.Tool.TRANSPORT_TRACE_JOURNAL, "event=restored_packet index={}, channel={}, flow={}, protocol={}, packetClass={}, source={}, rawPacketId={}, packetBytes={}, packetPrefix={}",
                traceIndex,
                channelIdText(context),
                packetFlow,
                readProtocolName(context),
                packetClassName(restoredPacket),
                ChannelTransportPacketRankSourceResolver.resolveSourceKey(restoredPacket),
                tryReadLeadingVarInt(restoredPacketBytes),
                lengthOf(restoredPacketBytes),
                hexPrefix(restoredPacketBytes)
        );
    }

    private static void logRestoredPacketDecodeFailure(
            ChannelHandlerContext context,
            PacketDecoderFlowAccess packetDecoderFlowAccess,
            byte[] restoredPacketBytes,
            Exception exception
    ) {
        PacketFlow packetFlow = packetDecoderFlowAccess == null ? null : packetDecoderFlowAccess.bandwidthoptimizer$getPacketFlow();
        ChannelTransportTraceJournal.record(
                context,
                "RestoredPacketFailure",
                "failure:restored_packet",
                "channel=" + channelIdText(context)
                        + ", flow=" + packetFlow
                        + ", protocol=" + readProtocolName(context)
                        + ", rawPacketId=" + tryReadLeadingVarInt(restoredPacketBytes)
                        + ", packetBytes=" + lengthOf(restoredPacketBytes)
                        + ", exception=" + exception.getClass().getName()
                        + ", packetPrefix=" + hexPrefix(restoredPacketBytes)
        );
        DiagnosticLog.error(DiagnosticToolRegistry.Tool.TRANSPORT_TRACE_JOURNAL, "event=restored_packet_failure channel={}, flow={}, protocol={}, rawPacketId={}, packetBytes={}, packetPrefix={}",
                channelIdText(context),
                packetFlow,
                readProtocolName(context),
                tryReadLeadingVarInt(restoredPacketBytes),
                lengthOf(restoredPacketBytes),
                hexPrefix(restoredPacketBytes),
                exception
        );
    }


    private static long nextTransportTraceIndex(AtomicLong counter) {
        if (!ChannelTransportHookDiagnosticProbe.BO_Diag_transportTraceJournal()) {
            return -1L;
        }
        int sampleLimit = transportTraceSampleLimit();
        if (sampleLimit <= 0) {
            return -1L;
        }
        long traceIndex = counter.incrementAndGet();
        return traceIndex <= sampleLimit ? traceIndex : -1L;
    }


    private static int transportTraceSampleLimit() {
        return readIntegerRuntimeProperty(
                Config.RuntimeProperty.Transport.DEBUG_TRACE_SAMPLE_LIMIT,
                Config.RuntimeProperty.Transport.DEFAULT_DEBUG_TRACE_SAMPLE_LIMIT,
                0
        );
    }


    private static int transportTracePrefixBytes() {
        return readIntegerRuntimeProperty(
                Config.RuntimeProperty.Transport.DEBUG_TRACE_PREFIX_BYTES,
                Config.RuntimeProperty.Transport.DEFAULT_DEBUG_TRACE_PREFIX_BYTES,
                0
        );
    }


    private static int readIntegerRuntimeProperty(String propertyName, int defaultValue, int minValue) {
        String rawValue = System.getProperty(propertyName);
        if (rawValue == null || rawValue.isBlank()) {
            return Math.max(defaultValue, minValue);
        }
        try {
            return Math.max(Integer.parseInt(rawValue.trim()), minValue);
        } catch (NumberFormatException ignored) {
            return Math.max(defaultValue, minValue);
        }
    }


    private static int tryReadLeadingVarInt(byte[] packetBytes) {
        if (packetBytes == null || packetBytes.length == 0) {
            return -1;
        }
        int result = 0;
        int shift = 0;
        for (int index = 0; index < Math.min(packetBytes.length, 5); index++) {
            int value = packetBytes[index] & 0xFF;
            result |= (value & 0x7F) << shift;
            if ((value & 0x80) == 0) {
                return result;
            }
            shift += 7;
        }
        return -1;
    }


    private static int lookupProtocolPacketId(ChannelHandlerContext context, PacketFlow packetFlow, Packet<?> packet) {
        if (context == null || packetFlow == null || packet == null) {
            return -1;
        }
        try {
            return readConnectionProtocol(context).getPacketId(packetFlow, packet);
        } catch (RuntimeException ignored) {
            return -1;
        }
    }


    private static String entryKindText(ChannelTransportOperationTelemetry telemetry) {
        if (telemetry == null) {
            return "none";
        }
        if (telemetry.templateReferenceCount() > 0) {
            return "template_reference";
        }
        if (telemetry.exactReferenceCount() > 0) {
            return "exact_reference";
        }
        if (telemetry.literalEntryCount() > 0) {
            return "literal";
        }
        return "none";
    }


    private static String hexPrefix(byte[] bytes) {
        if (bytes == null) {
            return "<null>";
        }
        int prefixBytes = Math.min(bytes.length, transportTracePrefixBytes());
        StringBuilder builder = new StringBuilder(prefixBytes * 2 + 3);
        for (int index = 0; index < prefixBytes; index++) {
            int value = bytes[index] & 0xFF;
            if (value < 0x10) {
                builder.append('0');
            }
            builder.append(Integer.toHexString(value));
        }
        if (bytes.length > prefixBytes) {
            builder.append("...");
        }
        return builder.toString();
    }


    private static int lengthOf(byte[] bytes) {
        return bytes == null ? 0 : bytes.length;
    }


    private static String channelIdText(ChannelHandlerContext context) {
        return context == null || context.channel() == null ? "<null>" : com.PinkCats.bandwidthoptimizer.channel.ChannelIdentity.shortText(context.channel());
    }


    private static String packetClassName(Packet<?> packet) {
        return packet == null ? "<null>" : packet.getClass().getName();
    }

    // prevent queue mistake
    private static boolean shouldBypassTransparentTransport(
            ChannelHandlerContext context,
            String protocolName,
            Packet<?> packet,
            PacketFlow packetFlow
    ) {
        if (!ChannelTransportRuntimeGuard.isTransportAvailable()
                || !shouldUseTransportForCurrentProtocol(protocolName)) {
            ChannelTransportBatchManager.flushOutboundBatchNow(context);
            return true;
        }
        if (shouldBypassServerboundTransparentTransport(packetFlow)) {
            ChannelTransportBatchManager.flushOutboundBatchNow(context);
            return true;
        }
        //Sable compat
        if (SableChunkSyncCompat.shouldBypassTransparentTransport(context, protocolName, packet)) {
            ChannelTransportBatchManager.flushOutboundBatchNow(context);
            return true;
        }
        if (CreateBlockEntityUpdateGate.shouldBypassTransparentTransport(context, protocolName, packetFlow, packet)) {
            ChannelTransportBatchManager.flushOutboundBatchNow(context);
            return true;
        }
        if (!ChannelTransportBypassPacketList.shouldBypassTransparentTransport(packet)) {
            return false;
        }
        ChannelTransportBatchManager.flushOutboundBatchNow(context);
        return true;
    }


    private static boolean shouldBypassServerboundTransparentTransport(PacketFlow packetFlow) {
        return packetFlow == PacketFlow.SERVERBOUND
                && !Boolean.parseBoolean(System.getProperty(
                Config.RuntimeProperty.Transport.SERVERBOUND_TRANSPARENT_ENABLED,
                Boolean.toString(Config.RuntimeProperty.Transport.DEFAULT_SERVERBOUND_TRANSPARENT_ENABLED)
        ));
    }

    @Incomplete("Only PLAY packet now")
    private static boolean shouldUseTransportForCurrentProtocol(String protocolName) {
        return "PLAY".equalsIgnoreCase(protocolName);
    }
}
