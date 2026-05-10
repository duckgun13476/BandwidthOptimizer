package com.PinkCats.bandwidthoptimizer.channel;

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
import com.PinkCats.bandwidthoptimizer.channel.packet.ChannelTransportBypassPacketList;
import com.PinkCats.bandwidthoptimizer.chunk.classify.ChunkInboundObservationService;
import com.PinkCats.bandwidthoptimizer.chunk.classify.ChunkOutboundObservationService;
import com.PinkCats.bandwidthoptimizer.chunk.integration.ChunkInboundDecodeResult;
import com.PinkCats.bandwidthoptimizer.chunk.integration.transport.ChunkTransportBoundaryController;
import com.PinkCats.bandwidthoptimizer.chunk.integration.transport.ChunkTransportControlFrameSender;
import com.PinkCats.bandwidthoptimizer.chunk.integration.transport.ChunkTransportDispatcher;
import com.PinkCats.bandwidthoptimizer.chunk.integration.transport.ChunkTransportDispatcher.OutboundChunkEncodeResult;
import com.PinkCats.bandwidthoptimizer.debug.DebugRuntimeConfig;
import com.PinkCats.bandwidthoptimizer.mixin.minecraft.ClientboundCustomPayloadPacketAccessor;
import com.PinkCats.bandwidthoptimizer.mixin.minecraft.ServerboundCustomPayloadPacketAccessor;
import com.PinkCats.bandwidthoptimizer.report.ChunkBoundaryBandwidthRecorder;
import com.PinkCats.bandwidthoptimizer.report.ChannelTransportPacketRankCaptureManager;
import com.PinkCats.bandwidthoptimizer.server.stat.ChannelBandwidthStats;
import com.PinkCats.bandwidthoptimizer.server.stat.ServerBandwidthStatsRegistry;
import io.netty.buffer.ByteBuf;
import io.netty.buffer.ByteBufUtil;
import io.netty.buffer.Unpooled;
import io.netty.channel.ChannelHandlerContext;
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
import java.util.concurrent.atomic.AtomicLong;

public final class ChannelTransportHooks {

    private static final ResourceLocation TRANSPORT_PAYLOAD_ID =
            ChannelTransportNetworkChannel.TRANSPORT_PAYLOAD_ID;
    private static final int CLIENTBOUND_CUSTOM_PAYLOAD_MAX_BYTES = 1_048_576;
    private static final int SERVERBOUND_CUSTOM_PAYLOAD_MAX_BYTES = 32767;
    private static final int SERVERBOUND_CUSTOM_PAYLOAD_SAFE_INPUT_BYTES = 24000;
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

        int endIndexExclusive = out.writerIndex();
        if (endIndexExclusive <= startIndexInclusive) {
            return;
        }

        String protocolName = readProtocolName(context);
        byte[] originalPacketBytes = ByteBufUtil.getBytes(out, startIndexInclusive, endIndexExclusive - startIndexInclusive, false);
        PacketFlow outboundPacketFlow = resolvePacketFlow(packetEncoderFlowAccess, readConnectionProtocolOrNull(context), packet);


        if (!ChannelTransportRuntimeGuard.isTransportAvailable()
                || !shouldUseTransportForCurrentProtocol(protocolName)) {
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
        if (forceImmediateTransport && pendingDirectTransport && DebugRuntimeConfig.isDiagnoseEnabled()) {
            Bandwidthoptimizer.LOGGER.info(
                    "[Transport][ImmediatePolicy][DirectOverride] immediateReason={}, directReason={}, controlDirect={}, boundaryDirect={}, protocol={}, packetClass={}, channel={}",
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
            ChannelTransportBatchManager.flushOutboundBatchNow(context);
            ChunkTransportBoundaryController.scheduleOutboundBarrier(context, boundaryDecision);
            OutboundChunkEncodeResult directChunkTrace = new OutboundChunkEncodeResult(
                    false,
                    false,
                    null,
                    controlDecision.forceDirectTransport() ? controlDecision.reason() : boundaryDecision.reason(),
                    null
            );
            ChunkBoundaryBandwidthRecorder.OutboundPacketTrace directBoundaryPacketTrace =
                    ChunkBoundaryBandwidthRecorder.beginOutboundTrace(
                            context,
                            protocolName,
                            packet,
                            originalPacketBytes,
                            originalPacketBytes,
                            directChunkTrace
                    );
            recordDirectPacketTrace(
                    context,
                    controlDecision.forceDirectTransport() ? controlDecision.reason() : boundaryDecision.reason(),
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
                    directBoundaryPacketTrace,
                    "DIRECT_PASSTHROUGH",
                    "DIRECT",
                    originalPacketBytes.length,
                    false,
                    1
            );
            sendServerCacheScopeAfterLoginBoundary(context, packet);
            return;
        }
        if (forceImmediateTransport) {
            ChannelTransportBatchManager.flushOutboundBatchNow(context);
            if (DebugRuntimeConfig.isDiagnoseEnabled()) {
                Bandwidthoptimizer.LOGGER.info(
                        "[Transport][ImmediatePolicy][Start] reason={}, protocol={}, packetClass={}, rawBytes={}, rawPacketId={}, channel={}",
                        controlDecision.reason(),
                        protocolName,
                        packetClassName(packet),
                        originalPacketBytes.length,
                        tryReadLeadingVarInt(originalPacketBytes),
                        channelIdText(context)
                );
            }
        }

        OutboundChunkEncodeResult chunkEncodeResult = ChunkTransportDispatcher.tryEncodeOutboundPacketWithTrace(
                context,
                protocolName,
                packet,
                originalPacketBytes
        );
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
                out.writeBytes(transportInputPacketBytes);
                if (forceImmediateTransport && DebugRuntimeConfig.isDiagnoseEnabled()) {
                    Bandwidthoptimizer.LOGGER.info(
                            "[Transport][ImmediatePolicy][Fallback] reason=serverbound_carrier_size, packetClass={}, inputBytes={}, channel={}",
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
                        transportInputPacketBytes
                );
                recordOutboundBypassStats(context, protocolName, transportInputPacketBytes.length, 1);
                ChannelTransportPacketRankCaptureManager.completeSingleDirectFallbackCapture(
                        outboundPacketCapture,
                        transportInputPacketBytes.length
                );
                ChunkBoundaryBandwidthRecorder.completeOutboundTrace(
                        boundaryPacketTrace,
                        "DIRECT_PASSTHROUGH",
                        "DIRECT",
                        transportInputPacketBytes.length,
                        chunkTransportEncodedBytes != null,
                        1
                );
                return;
            }

            if (!forceImmediateTransport && ChannelTransportBatchManager.shouldBatchOutboundPacket(context)) {
                out.writerIndex(startIndexInclusive);
                if (DebugRuntimeConfig.isDiagnoseEnabled()) {
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
                        outboundPacketFlow,
                        outboundPacketCapture,
                        boundaryPacketTrace
                );
                return;
            }

            ChannelTransportSession transportSession = ChannelTransportStateManager.getOrCreateSession(context.channel());
            ChannelTransportPacketCodec.WrappedTransportFrame wrappedFrame =
                    KineticChannel.processOutboundPacket(transportSession, transportInputPacketBytes);
            if (wrappedFrame == null) {
                if (forceImmediateTransport) {
                    if (DebugRuntimeConfig.isDiagnoseEnabled()) {
                        Bandwidthoptimizer.LOGGER.info(
                                "[Transport][ImmediatePolicy][Fallback] reason=wrap_unavailable, packetClass={}, inputBytes={}, channel={}",
                                packetClassName(packet),
                                transportInputPacketBytes.length,
                                channelIdText(context)
                        );
                    }
                    recordDirectPacketTrace(
                            context,
                            "immediate_wrap_unavailable",
                            protocolName,
                            packet,
                            outboundPacketFlow,
                            transportInputPacketBytes
                    );
                    recordOutboundBypassStats(context, protocolName, transportInputPacketBytes.length, 1);
                    ChannelTransportPacketRankCaptureManager.completeSingleDirectFallbackCapture(
                            outboundPacketCapture,
                            transportInputPacketBytes.length
                    );
                    ChunkBoundaryBandwidthRecorder.completeOutboundTrace(
                            boundaryPacketTrace,
                            "DIRECT_PASSTHROUGH",
                            "DIRECT",
                            transportInputPacketBytes.length,
                            chunkTransportEncodedBytes != null,
                            1
                    );
                }
                return;
            }
            if (shouldBypassUnprofitableCarrier(wrappedFrame)) {
                out.writerIndex(startIndexInclusive);
                out.writeBytes(transportInputPacketBytes);
                recordOutboundBypassStats(context, protocolName, transportInputPacketBytes.length, 1);
                ChannelTransportPacketRankCaptureManager.completeSingleDirectFallbackCapture(
                        outboundPacketCapture,
                        transportInputPacketBytes.length
                );
                ChunkBoundaryBandwidthRecorder.completeOutboundTrace(
                        boundaryPacketTrace,
                        "DIRECT_PASSTHROUGH",
                        "DIRECT",
                        transportInputPacketBytes.length,
                        chunkTransportEncodedBytes != null,
                        1
                );
                return;
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

            out.writerIndex(startIndexInclusive);
            if (!writeTransportCarrierPacket(context, outboundPacketFlow, out, wrappedFrame.transportFrameBytes())) {
                out.writeBytes(transportInputPacketBytes);
                if (forceImmediateTransport && DebugRuntimeConfig.isDiagnoseEnabled()) {
                    Bandwidthoptimizer.LOGGER.info(
                            "[Transport][ImmediatePolicy][Fallback] reason=carrier_write_failed, packetClass={}, inputBytes={}, channel={}",
                            packetClassName(packet),
                            transportInputPacketBytes.length,
                            channelIdText(context)
                    );
                }
                recordDirectPacketTrace(
                        context,
                        "carrier_write_failed",
                        protocolName,
                        packet,
                        outboundPacketFlow,
                        transportInputPacketBytes
                );
                recordOutboundBypassStats(context, protocolName, transportInputPacketBytes.length, 1);
                ChannelTransportPacketRankCaptureManager.completeSingleDirectFallbackCapture(
                        outboundPacketCapture,
                        transportInputPacketBytes.length
                );
                ChunkBoundaryBandwidthRecorder.completeOutboundTrace(
                        boundaryPacketTrace,
                        "DIRECT_PASSTHROUGH",
                        "DIRECT",
                        transportInputPacketBytes.length,
                        chunkTransportEncodedBytes != null,
                        1
                );
                return;
            }
            if (forceImmediateTransport && DebugRuntimeConfig.isDiagnoseEnabled()) {
                Bandwidthoptimizer.LOGGER.info(
                        "[Transport][ImmediatePolicy][Result] path=single_transport, reason={}, protocol={}, packetClass={}, rawBytes={}, inputBytes={}, frameKind={}, frameBytes={}, channel={}",
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
        } catch (Throwable throwable) {
            if (forceImmediateTransport) {
                if (DebugRuntimeConfig.isDiagnoseEnabled()) {
                    Bandwidthoptimizer.LOGGER.info(
                            "[Transport][ImmediatePolicy][Fallback] reason=wrap_exception, packetClass={}, inputBytes={}, channel={}, exception={}: {}",
                            packetClassName(packet),
                            transportInputPacketBytes.length,
                            channelIdText(context),
                            throwable.getClass().getName(),
                            throwable.getMessage()
                    );
                }
                recordDirectPacketTrace(
                        context,
                        "immediate_wrap_exception",
                        protocolName,
                        packet,
                        outboundPacketFlow,
                        transportInputPacketBytes
                );
                recordOutboundBypassStats(context, protocolName, transportInputPacketBytes.length, 1);
                ChannelTransportPacketRankCaptureManager.completeSingleDirectFallbackCapture(
                        outboundPacketCapture,
                        transportInputPacketBytes.length
                );
                ChunkBoundaryBandwidthRecorder.completeOutboundTrace(
                        boundaryPacketTrace,
                        "DIRECT_PASSTHROUGH",
                        "DIRECT",
                        transportInputPacketBytes.length,
                        chunkTransportEncodedBytes != null,
                        1
                );
            }
            ChannelTransportRuntimeGuard.disableTransport("outbound-wrap", throwable);
        }
    }

    private static void sendServerCacheScopeAfterLoginBoundary(ChannelHandlerContext context, Packet<?> packet) {
        if (context == null || !(packet instanceof ClientboundLoginPacket)) {
            return;
        }
        ChunkTransportControlFrameSender.sendServerCacheScope(context.channel(), "server_cache_scope_after_login_boundary");
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

    // Receive handle
    public static <T extends PacketListener> boolean tryDecodeInboundTransportFrame(
            ChannelHandlerContext context,
            ByteBuf in,
            List<Object> out,
            PacketDecoderFlowAccess packetDecoderFlowAccess
    ) throws Exception {
        if (context == null
                || in == null
                || out == null
                || packetDecoderFlowAccess == null
                || !in.isReadable()
                || !ChannelTransportRuntimeGuard.isTransportAvailable()
                || !shouldUseTransportForCurrentProtocol(readProtocolName(context))) {
            return false;
        }

        byte[] inboundPacketBytes = ByteBufUtil.getBytes(in, in.readerIndex(), in.readableBytes(), false);
        ChannelTransportSession transportSession = ChannelTransportStateManager.getOrCreateSession(context.channel());
        ChannelTransportPacketCodec.UnwrappedTransportFrame unwrappedFrame =
                KineticChannel.tryUnpackInboundPacket(transportSession, inboundPacketBytes);
        if (unwrappedFrame == null) {
            return false;
        }

        decodeInboundPacketsIntoOutput(context, unwrappedFrame, out, packetDecoderFlowAccess);
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
        if (context == null
                || out == null
                || packetDecoderFlowAccess == null
                || out.size() <= outputSizeBeforeDecode
                || !ChannelTransportRuntimeGuard.isTransportAvailable()
                || !shouldUseTransportForCurrentProtocol(readProtocolName(context))) {
            return false;
        }

        boolean expandedAny = false;
        for (int index = outputSizeBeforeDecode; index < out.size(); index++) {
            byte[] transportFrameBytes = copyTransportPayloadBytes(out.get(index));
            if (transportFrameBytes == null) {
                continue;
            }

            ChannelTransportSession transportSession = ChannelTransportStateManager.getOrCreateSession(context.channel());
            ChannelTransportPacketCodec.UnwrappedTransportFrame unwrappedFrame;
            try {
                unwrappedFrame = KineticChannel.tryUnpackInboundPacket(transportSession, transportFrameBytes);
            } catch (RuntimeException exception) {
                logInboundCarrierFailure(context, transportFrameBytes, exception);
                throw exception;
            }
            if (unwrappedFrame == null) {
                continue;
            }
            logInboundCarrierTrace(context, packetDecoderFlowAccess, transportFrameBytes, unwrappedFrame);

            List<Object> restoredPackets = new ArrayList<>();
            decodeInboundPacketsIntoOutput(context, unwrappedFrame, restoredPackets, packetDecoderFlowAccess);
            recordInboundTransportStats(context, readProtocolName(context), unwrappedFrame);
            expandedAny = true;
            index = replaceDecodedCarrierWithRestoredPackets(out, index, restoredPackets);
        }
        return expandedAny;
    }

    // Fix decode handshake problem (not support for index)
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
            List<Object> out,
            PacketDecoderFlowAccess packetDecoderFlowAccess
    ) throws Exception {
        for (byte[] transportRestoredPacketBytes : unwrappedFrame.restoredPacketBytesList()) {
            ChunkInboundDecodeResult inboundDecodeResult =
                    ChunkTransportDispatcher.tryDecodeInboundPacket(context, transportRestoredPacketBytes);
            if (!inboundDecodeResult.shouldDecodeVanillaPacket()) {
                continue;
            }
            byte[] restoredPacketBytes = inboundDecodeResult.restoredPacketBytes();
            ChannelCapturedFrame pendingInboundFrame = beginInboundCapture(context, restoredPacketBytes);
            int outputSizeBeforeDecode = out.size();
            Packet<? super T> restoredPacket;
            try {
                restoredPacket = decodeRestoredPacket(context, restoredPacketBytes, packetDecoderFlowAccess);
            } catch (Exception exception) {
                logRestoredPacketDecodeFailure(context, packetDecoderFlowAccess, restoredPacketBytes, exception);
                throw exception;
            }
            logRestoredPacketTrace(context, packetDecoderFlowAccess, restoredPacketBytes, restoredPacket);
            out.add(restoredPacket);

            ChunkInboundObservationService.observeInboundDecodedPackets(
                    context,
                    pendingInboundFrame,
                    out,
                    outputSizeBeforeDecode
            );
            ChannelCaptureHooks.finishInboundDecode(pendingInboundFrame, out, outputSizeBeforeDecode);
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
        if (packetFlow == PacketFlow.CLIENTBOUND && transportFrameBytes.length > CLIENTBOUND_CUSTOM_PAYLOAD_MAX_BYTES) {
            return false;
        }
        if (packetFlow == PacketFlow.SERVERBOUND && transportFrameBytes.length > SERVERBOUND_CUSTOM_PAYLOAD_MAX_BYTES) {
            return false;
        }

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
            payloadBuffer.release();
        }
    }


    public static boolean shouldBypassServerboundCarrierByInputSize(PacketFlow packetFlow, int transportInputBytes) {
        return packetFlow == PacketFlow.SERVERBOUND
                && transportInputBytes > SERVERBOUND_CUSTOM_PAYLOAD_SAFE_INPUT_BYTES;
    }

    // Mapping packet can't bypass
    public static boolean shouldBypassUnprofitableCarrier(ChannelTransportPacketCodec.WrappedTransportFrame wrappedFrame) {
        if (wrappedFrame == null
                || wrappedFrame.originalPacketBytes() <= 0
                || wrappedFrame.transportFrameLength() < wrappedFrame.originalPacketBytes()) {
            return false;
        }
        ChannelTransportOperationTelemetry telemetry = wrappedFrame.telemetry();
        return telemetry != null
                && telemetry.exactAdditionCount() == 0
                && telemetry.templateAdditionCount() == 0
                && telemetry.exactRemovalCount() == 0
                && telemetry.templateRemovalCount() == 0;
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
        if (!DebugRuntimeConfig.isDiagnoseEnabled()) {
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

        if (!DebugRuntimeConfig.isDiagnoseEnabled())
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
        Bandwidthoptimizer.LOGGER.info(
                "[Transport][Trace][OutboundPacket] index={}, channel={}, flow={}, protocol={}, packetClass={}, tablePacketId={}, rawPacketId={}, inputPacketId={}, rawBytes={}, inputBytes={}, chunkApplied={}, frameKind={}, frameBytes={}, bodyBytes={}, entry={}, mappingStageBytes={}, exactRef={}, templateRef={}, exactAdd={}, templateAdd={}, exactRemove={}, templateRemove={}, rawPrefix={}, inputPrefix={}, framePrefix={}",
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
        if (!DebugRuntimeConfig.isDiagnoseEnabled()) {
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
        Bandwidthoptimizer.LOGGER.info(
                "[Transport][Trace][OutboundCarrier] index={}, channel={}, flow={}, protocol={}, carrierPacketId={}, frameBytes={}, framePrefix={}",
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

        if (!DebugRuntimeConfig.isDiagnoseEnabled())
            return;

        ChannelTransportOperationTelemetry telemetry = unwrappedFrame.telemetry();
        PacketFlow packetFlow = packetDecoderFlowAccess == null ? null : packetDecoderFlowAccess.bandwidthoptimizer$getPacketFlow();

        if (!DebugRuntimeConfig.isDiagnoseEnabled())
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
        Bandwidthoptimizer.LOGGER.info(
                "[Transport][Trace][InboundCarrier] index={}, channel={}, flow={}, protocol={}, frameKind={}, frameBytes={}, bodyBytes={}, restoredPackets={}, restoredBytes={}, entry={}, mappingStageBytes={}, exactRef={}, templateRef={}, exactAdd={}, templateAdd={}, exactRemove={}, templateRemove={}, framePrefix={}",
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
        Bandwidthoptimizer.LOGGER.error(
                "[Transport][Trace][InboundCarrierFailure] channel={}, protocol={}, frameBytes={}, framePrefix={}",
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

        if (!DebugRuntimeConfig.isDiagnoseEnabled()) {
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
                        + ", rawPacketId=" + tryReadLeadingVarInt(restoredPacketBytes)
                        + ", packetBytes=" + lengthOf(restoredPacketBytes)
                        + ", packetPrefix=" + hexPrefix(restoredPacketBytes)
        );
        long traceIndex = nextTransportTraceIndex(INBOUND_RESTORED_PACKET_TRACE_COUNTER);
        if (traceIndex < 0L) {
            return;
        }
        Bandwidthoptimizer.LOGGER.info(
                "[Transport][Trace][RestoredPacket] index={}, channel={}, flow={}, protocol={}, packetClass={}, rawPacketId={}, packetBytes={}, packetPrefix={}",
                traceIndex,
                channelIdText(context),
                packetFlow,
                readProtocolName(context),
                packetClassName(restoredPacket),
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
        Bandwidthoptimizer.LOGGER.error(
                "[Transport][Trace][RestoredPacketFailure] channel={}, flow={}, protocol={}, rawPacketId={}, packetBytes={}, packetPrefix={}",
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
        if (!DebugRuntimeConfig.isDiagnoseEnabled()) {
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
        return context == null || context.channel() == null ? "<null>" : context.channel().id().asShortText();
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
