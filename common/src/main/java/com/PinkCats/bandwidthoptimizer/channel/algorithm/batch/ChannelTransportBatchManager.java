package com.PinkCats.bandwidthoptimizer.channel.algorithm.batch;

import com.PinkCats.bandwidthoptimizer.channel.ChannelTransportHooks;
import com.PinkCats.bandwidthoptimizer.channel.ChannelTransportBypassRankLogger;
import com.PinkCats.bandwidthoptimizer.channel.ChannelTransportPacketCodec;
import com.PinkCats.bandwidthoptimizer.channel.ChannelTransportRuntimeGuard;
import com.PinkCats.bandwidthoptimizer.channel.ChannelTransportSession;
import com.PinkCats.bandwidthoptimizer.channel.ChannelTransportStateManager;
import com.PinkCats.bandwidthoptimizer.channel.capture.ChannelCaptureHooks;
import com.PinkCats.bandwidthoptimizer.channel.capture.ChannelCapturedFrame;
import com.PinkCats.bandwidthoptimizer.channel.capture.ChannelTransportTelemetry;
import com.PinkCats.bandwidthoptimizer.chunk.classify.ChunkInboundObservationService;
import com.PinkCats.bandwidthoptimizer.compat.minecraft.ConnectionProtocolNameCompat;
import com.PinkCats.bandwidthoptimizer.report.ChunkBoundaryBandwidthRecorder;
import com.PinkCats.bandwidthoptimizer.report.ChannelTransportPacketRankCaptureManager;
import com.PinkCats.bandwidthoptimizer.server.stat.ChannelBandwidthStats;
import com.PinkCats.bandwidthoptimizer.server.stat.ServerBandwidthStatsRegistry;
import io.netty.buffer.ByteBuf;
import io.netty.buffer.Unpooled;
import io.netty.channel.Channel;
import io.netty.channel.ChannelHandlerContext;
import io.netty.util.AttributeKey;
import net.minecraft.network.protocol.Packet;
import net.minecraft.network.protocol.PacketFlow;

import java.io.IOException;
import java.nio.channels.ClosedChannelException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

public final class ChannelTransportBatchManager {

    private static final int LIGHT_BATCH_PACKET_THRESHOLD = 32;
    private static final int LIGHT_BATCH_BYTES_THRESHOLD = 64 * 1024;
    private static final int CLIENTBOUND_BATCH_CARRIER_MAX_BYTES = 1_048_576;
    private static final int SERVERBOUND_BATCH_CARRIER_MAX_BYTES = 32_767;
    private static final int CLIENTBOUND_BATCH_PREWRAP_BYTES_BUDGET = CLIENTBOUND_BATCH_CARRIER_MAX_BYTES / 2;
    private static final int SERVERBOUND_BATCH_PREWRAP_BYTES_BUDGET = SERVERBOUND_BATCH_CARRIER_MAX_BYTES / 2;
    private static final int CLIENTBOUND_BATCH_PREWRAP_PACKET_BUDGET = 2_048;
    private static final int SERVERBOUND_BATCH_PREWRAP_PACKET_BUDGET = 256;

    private static final AttributeKey<OutboundBatchState> OUTBOUND_BATCH_STATE_KEY =
            AttributeKey.valueOf("bandwidthoptimizer:channel_transport_outbound_batch_state");

    private static final AttributeKey<InboundBatchState> INBOUND_BATCH_STATE_KEY =
            AttributeKey.valueOf("bandwidthoptimizer:channel_transport_inbound_batch_state");

    private static final AttributeKey<BatchApplicabilityState> BATCH_APPLICABILITY_STATE_KEY =
            AttributeKey.valueOf("bandwidthoptimizer:channel_transport_batch_applicability_state");

    private static final AttributeKey<Boolean> BATCH_CLOSE_CLEANUP_ATTACHED_KEY =
            AttributeKey.valueOf("bandwidthoptimizer:channel_transport_batch_close_cleanup_attached");

    private ChannelTransportBatchManager() {}



    public static boolean enqueueOutboundPacket(
            ChannelHandlerContext context,
            byte[] transportPacketBytes,
            byte[] directFallbackPacketBytes,
            PacketFlow packetFlow,
            ChannelTransportPacketRankCaptureManager.OutboundPacketCapture outboundPacketCapture,
            ChunkBoundaryBandwidthRecorder.OutboundPacketTrace boundaryPacketTrace
    ) {
        if (context == null || transportPacketBytes == null || !isChannelUsable(context.channel())) {
            return false;
        }

        OutboundBatchState batchState = getOrCreateOutboundBatchState(context.channel());
        batchState.addPacket(
                context,
                copyBytesOrEmpty(transportPacketBytes),
                copyBytesOrEmpty(directFallbackPacketBytes),
                packetFlow,
                outboundPacketCapture,
                boundaryPacketTrace
        );
        batchState.scheduleFlushIfNeeded(context.channel());
        return true;
    }

    // prevent Delay packet queue problem
    public static void flushOutboundBatchNow(ChannelHandlerContext context) {
        if (context == null || !ChannelTransportBatchRuntimeConfig.isBatchEnabled()) {
            return;
        }
        flushOutboundBatch(context.channel(), OutboundBatchFlushMode.SENSITIVE_BOUNDARY);
    }

    public static void clearChannelState(Channel channel, String reason) {
        clearBatchState(channel, false);
    }

    // Warm up outbound batching after protocol boundaries.
    public static boolean shouldBatchOutboundPacket(ChannelHandlerContext context) {
        if (context == null || !ChannelTransportBatchRuntimeConfig.isBatchEnabled()) {
            return false;
        }
        return getOrCreateBatchApplicabilityState(context.channel()).touchAndIsBatchReady();
    }

    public static boolean shouldReplayInboundAsBatch(ChannelTransportPacketCodec.UnwrappedTransportFrame unwrappedFrame) {
        return unwrappedFrame != null && unwrappedFrame.frameKind() == ChannelTransportPacketCodec.FrameKind.BATCH;
    }

    // Batch replay can add delay that chunk envelope replacement does not.
    public static void replayInboundBatch(ChannelHandlerContext context, List<InboundReplayEntry> replayEntries) {
        if (context == null || replayEntries == null || replayEntries.isEmpty()) {
            return;
        }

        InboundBatchState batchState = getOrCreateInboundBatchState(context.channel());
        List<ScheduledReplayEntry> scheduledReplayEntries = batchState.schedule(replayEntries);
        for (ScheduledReplayEntry scheduledReplayEntry : scheduledReplayEntries) {
            long delayNanos = Math.max(scheduledReplayEntry.replayAtNanos() - System.nanoTime(), 0L);
            context.executor().schedule(
                    () -> replayInboundPacket(context, scheduledReplayEntry.entry()),
                    delayNanos,
                    TimeUnit.NANOSECONDS
            );
        }
    }


    private static void flushOutboundBatch(Channel channel) {
        flushOutboundBatch(channel, OutboundBatchFlushMode.NORMAL_WINDOW);
    }

    private static void flushOutboundBatch(Channel channel, OutboundBatchFlushMode flushMode) {
        if (channel == null)
            return;

        OutboundBatchState batchState = channel.attr(OUTBOUND_BATCH_STATE_KEY).get();
        if (batchState == null) {
            return;
        }
        if (!isChannelUsable(channel)) {
            batchState.clearPending();
            return;
        }
        OutboundBatchDrain drainedBatch = batchState.drain();
        if (drainedBatch == null || drainedBatch.packetBytesList().isEmpty() || drainedBatch.context() == null) {
            return;
        }

        try {
            PacketFlow packetFlow = drainedBatch.packetFlow();
            if (packetFlow == null
                    || ChannelTransportHooks.shouldBypassServerboundCarrierByInputSize(packetFlow, drainedBatch.totalPacketBytes())) {
                writePendingPacketsDirectly(drainedBatch, "batch_carrier_precheck_direct");
                return;
            }

            ChannelTransportSession transportSession = ChannelTransportStateManager.getOrCreateSession(channel);
            writeBatchCarrierOrSplit(channel, drainedBatch, packetFlow, transportSession, flushMode);
        } catch (Throwable throwable) {
            if (shouldIgnoreBatchFlushFailure(channel, throwable)) {
                return;
            }
            failConnection(channel, "outbound-batch-flush", throwable);
        }
    }

    private static void writeBatchCarrierOrSplit(
            Channel channel,
            OutboundBatchDrain drainedBatch,
            PacketFlow packetFlow,
            ChannelTransportSession transportSession,
            OutboundBatchFlushMode flushMode
    ) {
        if (shouldPreSplitBatch(packetFlow, drainedBatch)) {
            if (drainedBatch.pendingPackets().size() <= 1) {
                writePendingPacketsDirectly(drainedBatch, "batch_carrier_payload_budget_direct");
                return;
            }
            for (OutboundBatchDrain splitDrain : splitDrainInHalf(drainedBatch)) {
                writeBatchCarrierOrSplit(channel, splitDrain, packetFlow, transportSession, flushMode);
            }
            return;
        }

        ChannelTransportPacketCodec.WrappedTransportFrame wrappedFrame = wrapBatchFrame(
                drainedBatch,
                transportSession,
                flushMode
        );
        if (wrappedFrame == null) {
            writePendingPacketsDirectly(drainedBatch, "batch_carrier_wrap_direct");
            return;
        }

        int payloadLimitBytes = carrierPayloadLimitBytes(packetFlow);
        if (wrappedFrame.transportFrameLength() > payloadLimitBytes) {
            failConnection(
                    channel,
                    "outbound-batch-carrier-size",
                    new IllegalStateException("Transport batch carrier exceeds payload limit after pre-split: "
                            + wrappedFrame.transportFrameLength()
                            + " > "
                            + payloadLimitBytes)
            );
            return;
        }

        writeWrappedBatchCarrier(channel, drainedBatch, packetFlow, wrappedFrame);
    }

    private static ChannelTransportPacketCodec.WrappedTransportFrame wrapBatchFrame(
            OutboundBatchDrain drainedBatch,
            ChannelTransportSession transportSession,
            OutboundBatchFlushMode flushMode
    ) {
        return shouldUseLightBatchEncoding(flushMode, drainedBatch)
                ? ChannelTransportPacketCodec.wrapBatchPacketsLight(transportSession, drainedBatch.packetBytesList())
                : ChannelTransportPacketCodec.wrapBatchPackets(transportSession, drainedBatch.packetBytesList());
    }

    private static void writeWrappedBatchCarrier(
            Channel channel,
            OutboundBatchDrain drainedBatch,
            PacketFlow packetFlow,
            ChannelTransportPacketCodec.WrappedTransportFrame wrappedFrame
    ) {
        var writeFuture = ChannelTransportHooks.writeTransportCarrierPacketToPipeline(
                channel,
                packetFlow,
                wrappedFrame.transportFrameBytes()
        );
        if (writeFuture == null) {
            writePendingPacketsDirectly(drainedBatch, "batch_carrier_pipeline_direct");
            return;
        }

        recordOutboundBatchPacketStream(drainedBatch.context(), drainedBatch.pendingPackets());
        writeFuture.addListener(future -> {
            if (!future.isSuccess()) {
                Throwable failure = future.cause() == null ? new IllegalStateException("Unknown outbound batch flush failure") : future.cause();
                if (shouldIgnoreBatchFlushFailure(channel, failure)) {
                    return;
                }
                failStatefulBatchCommit(channel, "outbound-batch-flush", failure);
                return;
            }
            recordOutboundBatchTransportStats(drainedBatch.context(), readProtocolName(drainedBatch.context()), wrappedFrame);
            ChannelTransportPacketRankCaptureManager.completeBatchTransportCapture(
                    drainedBatch.packetCaptures(),
                    wrappedFrame
            );
            completeBatchBoundaryTrace(drainedBatch.pendingPackets(), wrappedFrame);
        });
    }

    private static List<OutboundBatchDrain> splitDrainInHalf(OutboundBatchDrain drainedBatch) {
        int midpoint = Math.max(drainedBatch.pendingPackets().size() / 2, 1);
        return List.of(
                new OutboundBatchDrain(
                        drainedBatch.context(),
                        List.copyOf(drainedBatch.pendingPackets().subList(0, midpoint))
                ),
                new OutboundBatchDrain(
                        drainedBatch.context(),
                        List.copyOf(drainedBatch.pendingPackets().subList(midpoint, drainedBatch.pendingPackets().size()))
                )
        );
    }

    private static int carrierPayloadLimitBytes(PacketFlow packetFlow) {
        return packetFlow == PacketFlow.SERVERBOUND
                ? SERVERBOUND_BATCH_CARRIER_MAX_BYTES
                : CLIENTBOUND_BATCH_CARRIER_MAX_BYTES;
    }

    private static boolean shouldPreSplitBatch(PacketFlow packetFlow, OutboundBatchDrain drainedBatch) {
        if (drainedBatch == null) {
            return false;
        }
        return drainedBatch.totalPacketBytes() > prewrapBytesBudget(packetFlow)
                || drainedBatch.pendingPackets().size() > prewrapPacketBudget(packetFlow);
    }

    private static int prewrapBytesBudget(PacketFlow packetFlow) {
        return packetFlow == PacketFlow.SERVERBOUND
                ? SERVERBOUND_BATCH_PREWRAP_BYTES_BUDGET
                : CLIENTBOUND_BATCH_PREWRAP_BYTES_BUDGET;
    }

    private static int prewrapPacketBudget(PacketFlow packetFlow) {
        return packetFlow == PacketFlow.SERVERBOUND
                ? SERVERBOUND_BATCH_PREWRAP_PACKET_BUDGET
                : CLIENTBOUND_BATCH_PREWRAP_PACKET_BUDGET;
    }

    private static void failStatefulBatchCommit(Channel channel, String stageName, Throwable throwable) {
        Throwable failure = throwable == null
                ? new IllegalStateException("Stateful batch carrier was not committed")
                : throwable;
        failConnection(channel, stageName, failure);
    }

    private static void failConnection(Channel channel, String stageName, Throwable throwable) {
        ChannelTransportRuntimeGuard.reportRuntimeFailure(stageName, throwable);
        if (channel != null) {
            channel.close();
        }
    }

    private static boolean shouldUseLightBatchEncoding(OutboundBatchFlushMode flushMode, OutboundBatchDrain drainedBatch) {
        if (drainedBatch == null) {
            return false;
        }
        if (flushMode == OutboundBatchFlushMode.SENSITIVE_BOUNDARY) {
            return true;
        }
        return drainedBatch.pendingPackets().size() >= LIGHT_BATCH_PACKET_THRESHOLD
                || drainedBatch.totalPacketBytes() >= LIGHT_BATCH_BYTES_THRESHOLD;
    }

    // Direct when velocity
    private static void writePendingPacketsDirectly(OutboundBatchDrain drainedBatch, String reason) {
        writePendingPacketsDirectly(drainedBatch, reason, true);
    }

    private static void writePendingPacketsDirectly(OutboundBatchDrain drainedBatch, String reason, boolean recordRankLog) {
        if (drainedBatch == null || drainedBatch.context() == null || drainedBatch.pendingPackets().isEmpty()) {
            return;
        }

        ChannelHandlerContext context = drainedBatch.context();
        String protocolName = readProtocolName(context);
        for (PendingOutboundPacket pendingPacket : drainedBatch.pendingPackets()) {
            byte[] directPacketBytes = pendingPacket.copyDirectFallbackPacketBytes();
            ChannelTransportHooks.recordCommittedOutboundPacketStream(
                    context,
                    packetClassNameOf(pendingPacket),
                    packetIdOf(pendingPacket, directPacketBytes),
                    directPacketBytes
            );
            context.write(Unpooled.wrappedBuffer(directPacketBytes));
            if (recordRankLog) {
                ChannelTransportBypassRankLogger.recordEncodedPacket(
                        context,
                        reason,
                        protocolName,
                        pendingPacket.packetFlow(),
                        packetClassNameOf(pendingPacket),
                        null,
                        packetIdOf(pendingPacket),
                        directPacketBytes.length
                );
            }
            recordOutboundBatchBypassStats(context, protocolName, directPacketBytes.length);
        }
        context.flush();
        ChannelTransportPacketRankCaptureManager.completeDirectFallbackCapture(drainedBatch.packetCaptures());
        completeDirectBatchBoundaryTrace(drainedBatch.pendingPackets(), reason);
    }

    private static void recordOutboundBatchTransportStats(
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

    private static void recordOutboundBatchBypassStats(
            ChannelHandlerContext context,
            String protocolName,
            int byteLength
    ) {
        ChannelTransportTelemetry.recordOutboundBypass(protocolName, byteLength);
        ChannelBandwidthStats stats = ServerBandwidthStatsRegistry.getOrCreate(context);
        if (stats != null) {
            stats.recordOutboundBypass(byteLength, 1);
        }
    }

    private static void completeDirectBatchBoundaryTrace(List<PendingOutboundPacket> pendingPackets, String reason) {
        if (pendingPackets == null || pendingPackets.isEmpty()) {
            return;
        }

        String actualPath = reason == null || reason.isBlank()
                ? "BATCH_DIRECT_FALLBACK"
                : "BATCH_DIRECT_FALLBACK:" + reason;
        for (PendingOutboundPacket pendingPacket : pendingPackets) {
            byte[] directPacketBytes = pendingPacket.copyDirectFallbackPacketBytes();
            ChunkBoundaryBandwidthRecorder.completeOutboundTrace(
                    pendingPacket.boundaryPacketTrace(),
                    actualPath,
                    "DIRECT",
                    directPacketBytes.length,
                    false,
                    1
            );
        }
    }

    private static String packetClassNameOf(PendingOutboundPacket pendingPacket) {
        if (pendingPacket == null || pendingPacket.packetCapture() == null) {
            return "<encoded-batch-packet>";
        }
        return pendingPacket.packetCapture().packetClassName();
    }


    private static int packetIdOf(PendingOutboundPacket pendingPacket) {
        if (pendingPacket == null) {
            return -1;
        }
        if (pendingPacket.packetCapture() != null) {
            return pendingPacket.packetCapture().packetId();
        }
        return tryReadLeadingVarInt(pendingPacket.packetBytes());
    }

    private static int packetIdOf(PendingOutboundPacket pendingPacket, byte[] fallbackPacketBytes) {
        if (pendingPacket != null && pendingPacket.packetCapture() != null) {
            return pendingPacket.packetCapture().packetId();
        }
        return tryReadLeadingVarInt(fallbackPacketBytes);
    }

    private static void recordOutboundBatchPacketStream(
            ChannelHandlerContext context,
            List<PendingOutboundPacket> pendingPackets
    ) {
        if (context == null || pendingPackets == null || pendingPackets.isEmpty()) {
            return;
        }
        for (PendingOutboundPacket pendingPacket : pendingPackets) {
            byte[] directPacketBytes = pendingPacket.copyDirectFallbackPacketBytes();
            ChannelTransportHooks.recordCommittedOutboundPacketStream(
                    context,
                    packetClassNameOf(pendingPacket),
                    packetIdOf(pendingPacket, directPacketBytes),
                    directPacketBytes
            );
        }
    }


    private static int tryReadLeadingVarInt(byte[] encodedBytes) {
        if (encodedBytes == null || encodedBytes.length == 0) {
            return -1;
        }
        int value = 0;
        int position = 0;
        for (int index = 0; index < encodedBytes.length && index < 5; index++) {
            int current = encodedBytes[index] & 0xFF;
            value |= (current & 0x7F) << position;
            if ((current & 0x80) == 0) {
                return value;
            }
            position += 7;
        }
        return -1;
    }

    private static void completeBatchBoundaryTrace(
            List<PendingOutboundPacket> pendingPackets,
            ChannelTransportPacketCodec.WrappedTransportFrame wrappedFrame
    ) {
        if (pendingPackets == null || pendingPackets.isEmpty() || wrappedFrame == null) {
            return;
        }

        if (pendingPackets.size() == 1) {
            ChunkBoundaryBandwidthRecorder.completeOutboundTrace(
                    pendingPackets.get(0).boundaryPacketTrace(),
                    "BATCH_TRANSPORT_SINGLE_ENTRY",
                    wrappedFrame.frameKind().name(),
                    wrappedFrame.transportFrameLength(),
                    false,
                    1
            );
            return;
        }

        long totalWeight = 0L;
        for (PendingOutboundPacket pendingPacket : pendingPackets) {
            totalWeight += Math.max(pendingPacket.packetBytes().length, 1);
        }

        long remainingFrameBytes = wrappedFrame.transportFrameLength();
        long remainingWeight = Math.max(totalWeight, pendingPackets.size());
        for (int index = 0; index < pendingPackets.size(); index++) {
            PendingOutboundPacket pendingPacket = pendingPackets.get(index);
            int allocatedFrameBytes;
            if (index == pendingPackets.size() - 1) {
                allocatedFrameBytes = (int) Math.max(remainingFrameBytes, 0L);
            } else {
                long weight = Math.max(pendingPacket.packetBytes().length, 1);
                allocatedFrameBytes = (int) Math.max((remainingFrameBytes * weight) / Math.max(remainingWeight, 1L), 0L);
                remainingFrameBytes -= allocatedFrameBytes;
                remainingWeight -= weight;
            }
            ChunkBoundaryBandwidthRecorder.completeOutboundTrace(
                    pendingPacket.boundaryPacketTrace(),
                    "BATCH_TRANSPORT_SHARE",
                    wrappedFrame.frameKind().name(),
                    allocatedFrameBytes,
                    true,
                    pendingPackets.size()
            );
        }
    }

    private static void replayInboundPacket(ChannelHandlerContext context, InboundReplayEntry replayEntry) {
        try {
            ChannelHandlerContext decoderContext = readDecoderContext(context);
            if (decoderContext == null) {
                return;
            }
            captureInboundReplay(context, replayEntry.packetBytes(), replayEntry.packet());
            decoderContext.fireChannelRead(replayEntry.packet());
        } catch (Throwable throwable) {
            ChannelTransportRuntimeGuard.reportRuntimeFailure("inbound-batch-replay", throwable);
            if (context != null) {
                context.close();
            }
        }
    }

    private static boolean shouldIgnoreBatchFlushFailure(Channel channel, Throwable throwable) {
        return isChannelClosing(channel) || isExpectedShutdownFailure(throwable);
    }

    private static boolean isChannelClosing(Channel channel) {
        return channel == null || !channel.isOpen() || !channel.isActive();
    }

    private static boolean isExpectedShutdownFailure(Throwable throwable) {
        Throwable current = throwable;
        while (current != null) {
            if (current instanceof ClosedChannelException) {
                return true;
            }
            if (current instanceof IOException && hasExpectedShutdownMessage(current.getMessage())) {
                return true;
            }
            current = current.getCause();
        }
        return false;
    }

    private static boolean hasExpectedShutdownMessage(String message) {
        if (message == null || message.isBlank()) {
            return false;
        }
        String lowerCaseMessage = message.toLowerCase(Locale.ROOT);
        return lowerCaseMessage.contains("connection reset")
                || lowerCaseMessage.contains("broken pipe")
                || lowerCaseMessage.contains("forcibly closed")
                || lowerCaseMessage.contains("existing connection was forcibly closed");
    }

    private static void captureInboundReplay(ChannelHandlerContext context, byte[] packetBytes, Packet<?> packet) {
        ByteBuf restoredBuffer = Unpooled.wrappedBuffer(copyBytesOrEmpty(packetBytes));
        try {
            int outputSizeBeforeDecode = 0;
            List<Object> decodedPackets = new ArrayList<>(1);
            decodedPackets.add(packet);
            ChannelCapturedFrame pendingInboundFrame = ChannelCaptureHooks.beginInboundPreDecode(context, restoredBuffer);

            ChunkInboundObservationService.observeInboundDecodedPackets(
                    context,
                    pendingInboundFrame,
                    decodedPackets,
                    outputSizeBeforeDecode
            );
            ChannelCaptureHooks.finishInboundDecode(
                    context,
                    pendingInboundFrame,
                    decodedPackets,
                    outputSizeBeforeDecode
            );
        } finally {
            restoredBuffer.release();
        }
    }

    private static String readProtocolName(ChannelHandlerContext context) {
        // Keep protocol lookup behind the version compatibility layer.
        return ConnectionProtocolNameCompat.readProtocolName(context == null ? null : context.channel());
    }

    private static ChannelHandlerContext readDecoderContext(ChannelHandlerContext context) {
        if (context == null || context.channel() == null || !context.channel().isActive()) {
            return null;
        }
        return context.channel().pipeline().context("decoder");
    }

    private static OutboundBatchState getOrCreateOutboundBatchState(Channel channel) {
        ensureBatchCloseCleanup(channel);
        OutboundBatchState existingState = channel.attr(OUTBOUND_BATCH_STATE_KEY).get();
        if (existingState != null) {
            return existingState;
        }

        OutboundBatchState newState = new OutboundBatchState();
        OutboundBatchState racedState = channel.attr(OUTBOUND_BATCH_STATE_KEY).setIfAbsent(newState);
        return racedState != null ? racedState : newState;
    }

    private static InboundBatchState getOrCreateInboundBatchState(Channel channel) {
        ensureBatchCloseCleanup(channel);
        InboundBatchState existingState = channel.attr(INBOUND_BATCH_STATE_KEY).get();
        if (existingState != null) {
            return existingState;
        }

        InboundBatchState newState = new InboundBatchState();
        InboundBatchState racedState = channel.attr(INBOUND_BATCH_STATE_KEY).setIfAbsent(newState);
        return racedState != null ? racedState : newState;
    }

    private static BatchApplicabilityState getOrCreateBatchApplicabilityState(Channel channel) {
        ensureBatchCloseCleanup(channel);
        BatchApplicabilityState existingState = channel.attr(BATCH_APPLICABILITY_STATE_KEY).get();
        if (existingState != null) {
            return existingState;
        }

        BatchApplicabilityState newState = new BatchApplicabilityState();
        BatchApplicabilityState racedState = channel.attr(BATCH_APPLICABILITY_STATE_KEY).setIfAbsent(newState);
        return racedState != null ? racedState : newState;
    }


    private static void ensureBatchCloseCleanup(Channel channel) {
        if (channel == null) {
            return;
        }
        Boolean alreadyAttached = channel.attr(BATCH_CLOSE_CLEANUP_ATTACHED_KEY).get();
        if (Boolean.TRUE.equals(alreadyAttached)) {
            return;
        }

        Boolean raced = channel.attr(BATCH_CLOSE_CLEANUP_ATTACHED_KEY).setIfAbsent(Boolean.TRUE);
        if (Boolean.TRUE.equals(raced)) {
            return;
        }

        channel.closeFuture().addListener(future -> clearBatchState(channel));
    }

    private static void clearBatchState(Channel channel) {
        clearBatchState(channel, true);
    }

    private static void clearBatchState(Channel channel, boolean clearCloseCleanupMarker) {
        if (channel == null) {
            return;
        }

        OutboundBatchState outboundBatchState = channel.attr(OUTBOUND_BATCH_STATE_KEY).get();
        if (outboundBatchState != null) {
            outboundBatchState.clearPending();
        }

        channel.attr(OUTBOUND_BATCH_STATE_KEY).set(null);
        channel.attr(INBOUND_BATCH_STATE_KEY).set(null);
        channel.attr(BATCH_APPLICABILITY_STATE_KEY).set(null);
        if (clearCloseCleanupMarker) {
            channel.attr(BATCH_CLOSE_CLEANUP_ATTACHED_KEY).set(null);
        }
    }

    private static boolean isChannelUsable(Channel channel) {
        return channel != null && channel.isOpen() && channel.isActive();
    }

    private static byte[] copyBytesOrEmpty(byte[] sourceBytes) {
        return sourceBytes == null ? new byte[0] : Arrays.copyOf(sourceBytes, sourceBytes.length);
    }

    public record InboundReplayEntry(byte[] packetBytes, Packet<?> packet) {
        public InboundReplayEntry {
            packetBytes = copyBytesOrEmpty(packetBytes);
        }
    }

    private record ScheduledReplayEntry(InboundReplayEntry entry, long replayAtNanos) { }

    private record PendingOutboundPacket(
            byte[] packetBytes,
            byte[] directFallbackPacketBytes,
            PacketFlow packetFlow,
            ChannelTransportPacketRankCaptureManager.OutboundPacketCapture packetCapture,
            ChunkBoundaryBandwidthRecorder.OutboundPacketTrace boundaryPacketTrace
    ) {
        private PendingOutboundPacket {
            packetBytes = copyBytesOrEmpty(packetBytes);
            directFallbackPacketBytes = directFallbackPacketBytes == null || directFallbackPacketBytes.length == 0
                    ? copyBytesOrEmpty(packetBytes)
                    : copyBytesOrEmpty(directFallbackPacketBytes);
        }

        private byte[] copyDirectFallbackPacketBytes() {
            return copyBytesOrEmpty(this.directFallbackPacketBytes);
        }
    }

    private record OutboundBatchDrain(
            ChannelHandlerContext context,
            List<PendingOutboundPacket> pendingPackets
    ) {
        private List<byte[]> packetBytesList() {
            List<byte[]> packetBytesList = new ArrayList<>(this.pendingPackets.size());
            for (PendingOutboundPacket pendingPacket : this.pendingPackets) {
                packetBytesList.add(copyBytesOrEmpty(pendingPacket.packetBytes()));
            }
            return List.copyOf(packetBytesList);
        }

        private int totalPacketBytes() {
            int totalPacketBytes = 0;
            for (PendingOutboundPacket pendingPacket : this.pendingPackets) {
                totalPacketBytes += pendingPacket.packetBytes().length;
            }
            return totalPacketBytes;
        }

        private PacketFlow packetFlow() {
            PacketFlow packetFlow = null;
            for (PendingOutboundPacket pendingPacket : this.pendingPackets) {
                if (pendingPacket.packetFlow() == null) {
                    return null;
                }
                if (packetFlow == null) {
                    packetFlow = pendingPacket.packetFlow();
                } else if (packetFlow != pendingPacket.packetFlow()) {
                    return null;
                }
            }
            return packetFlow;
        }

        private List<ChannelTransportPacketRankCaptureManager.OutboundPacketCapture> packetCaptures() {
            List<ChannelTransportPacketRankCaptureManager.OutboundPacketCapture> packetCaptures =
                    new ArrayList<>(this.pendingPackets.size());
            for (PendingOutboundPacket pendingPacket : this.pendingPackets) {
                if (pendingPacket.packetCapture() != null) {
                    packetCaptures.add(pendingPacket.packetCapture());
                }
            }
            return List.copyOf(packetCaptures);
        }
    }

    private static final class OutboundBatchState {
        private final List<PendingOutboundPacket> pendingPackets = new ArrayList<>();
        private final AtomicBoolean flushScheduled = new AtomicBoolean();
        private ChannelHandlerContext lastContext;

        private void addPacket(
                ChannelHandlerContext context,
                byte[] packetBytes,
                byte[] directFallbackPacketBytes,
                PacketFlow packetFlow,
                ChannelTransportPacketRankCaptureManager.OutboundPacketCapture outboundPacketCapture,
                ChunkBoundaryBandwidthRecorder.OutboundPacketTrace boundaryPacketTrace
        ) {
            synchronized (this.pendingPackets) {
                this.lastContext = context;
                this.pendingPackets.add(new PendingOutboundPacket(
                        packetBytes,
                        directFallbackPacketBytes,
                        packetFlow,
                        outboundPacketCapture,
                        boundaryPacketTrace
                ));
            }
        }

        private void scheduleFlushIfNeeded(Channel channel) {
            if (!this.flushScheduled.compareAndSet(false, true)) {
                return;
            }

            long windowMillis = ChannelTransportBatchRuntimeConfig.windowMillis();
            channel.eventLoop().schedule(() -> {
                try {
                    flushOutboundBatch(channel, OutboundBatchFlushMode.NORMAL_WINDOW);
                } finally {
                    this.flushScheduled.set(false);
                    synchronized (this.pendingPackets) {
                        if (!isChannelUsable(channel)) {
                            this.clearPendingLocked();
                        } else if (!this.pendingPackets.isEmpty()) {
                            scheduleFlushIfNeeded(channel);
                        }
                    }
                }
            }, windowMillis, TimeUnit.MILLISECONDS);
        }

        private void clearPending() {
            synchronized (this.pendingPackets) {
                clearPendingLocked();
            }
        }

        private void clearPendingLocked() {
            this.pendingPackets.clear();
            this.lastContext = null;
        }

        private OutboundBatchDrain drain() {
            synchronized (this.pendingPackets) {
                if (this.pendingPackets.isEmpty()) {
                    return null;
                }

                List<PendingOutboundPacket> drainedPendingPackets = List.copyOf(this.pendingPackets);
                this.pendingPackets.clear();
                return new OutboundBatchDrain(this.lastContext, drainedPendingPackets);
            }
        }
    }

    private static final class InboundBatchState {
        private long nextReplayAtNanos;

        private synchronized List<ScheduledReplayEntry> schedule(List<InboundReplayEntry> replayEntries) {
            long windowNanos = TimeUnit.MILLISECONDS.toNanos(ChannelTransportBatchRuntimeConfig.windowMillis());
            long replayAtNanos = Math.max(this.nextReplayAtNanos, System.nanoTime());
            long stepNanos = replayEntries.size() <= 1 ? 0L : Math.max(windowNanos / replayEntries.size(), 1L);
            List<ScheduledReplayEntry> scheduledReplayEntries = new ArrayList<>(replayEntries.size());
            for (InboundReplayEntry replayEntry : replayEntries) {
                scheduledReplayEntries.add(new ScheduledReplayEntry(replayEntry, replayAtNanos));
                replayAtNanos += stepNanos;
            }
            this.nextReplayAtNanos = replayAtNanos;
            return List.copyOf(scheduledReplayEntries);
        }
    }

    private enum OutboundBatchFlushMode {
        NORMAL_WINDOW,
        SENSITIVE_BOUNDARY
    }

    private static final class BatchApplicabilityState {
        private long warmupDeadlineNanos = Long.MIN_VALUE;

        private synchronized boolean touchAndIsBatchReady() {
            long nowNanos = System.nanoTime();
            if (this.warmupDeadlineNanos == Long.MIN_VALUE) {
                this.warmupDeadlineNanos = nowNanos + TimeUnit.MILLISECONDS.toNanos(ChannelTransportBatchRuntimeConfig.warmupMillis());
            }
            return nowNanos >= this.warmupDeadlineNanos;
        }
    }
}
