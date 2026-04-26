package com.PinkCats.bandwidthoptimizer.channel.algorithm.batch;

import com.PinkCats.bandwidthoptimizer.channel.ChannelTransportPacketCodec;
import com.PinkCats.bandwidthoptimizer.channel.ChannelTransportRuntimeGuard;
import com.PinkCats.bandwidthoptimizer.channel.ChannelTransportSession;
import com.PinkCats.bandwidthoptimizer.channel.ChannelTransportStateManager;
import com.PinkCats.bandwidthoptimizer.channel.capture.ChannelCaptureHooks;
import com.PinkCats.bandwidthoptimizer.channel.capture.ChannelCapturedFrame;
import com.PinkCats.bandwidthoptimizer.channel.capture.ChannelTransportTelemetry;
import com.PinkCats.bandwidthoptimizer.chunk.classify.ChunkInboundObservationService;
import io.netty.buffer.ByteBuf;
import io.netty.buffer.Unpooled;
import io.netty.channel.Channel;
import io.netty.channel.ChannelHandlerContext;
import io.netty.util.AttributeKey;
import net.minecraft.network.protocol.Packet;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

public final class ChannelTransportBatchManager {

    private static final AttributeKey<OutboundBatchState> OUTBOUND_BATCH_STATE_KEY =
            AttributeKey.valueOf("bandwidthoptimizer:channel_transport_outbound_batch_state");

    private static final AttributeKey<InboundBatchState> INBOUND_BATCH_STATE_KEY =
            AttributeKey.valueOf("bandwidthoptimizer:channel_transport_inbound_batch_state");

    private static final AttributeKey<BatchApplicabilityState> BATCH_APPLICABILITY_STATE_KEY =
            AttributeKey.valueOf("bandwidthoptimizer:channel_transport_batch_applicability_state");

    private ChannelTransportBatchManager() {}

    public static boolean enqueueOutboundPacket(ChannelHandlerContext context, byte[] originalPacketBytes) {
        if (context == null || originalPacketBytes == null)
            return false;

        OutboundBatchState batchState = getOrCreateOutboundBatchState(context.channel());
        batchState.addPacket(context, copyBytesOrEmpty(originalPacketBytes));
        batchState.scheduleFlushIfNeeded(context.channel());
        return true;
    }

    // prevent Delay packet queue problem
    public static void flushOutboundBatchNow(ChannelHandlerContext context) {
        if (context == null || !ChannelTransportBatchRuntimeConfig.isBatchEnabled()) {
            return;
        }
        flushOutboundBatch(context.channel());
    }

    // Prevent sensitive overtake problem
    public static boolean shouldBatchOutboundPacket(ChannelHandlerContext context) {
        if (context == null || !ChannelTransportBatchRuntimeConfig.isBatchEnabled())
            return false;
        return getOrCreateBatchApplicabilityState(context.channel()).touchAndIsBatchReady();
    }


    public static boolean shouldReplayInboundAsBatch(ChannelTransportPacketCodec.UnwrappedTransportFrame unwrappedFrame) {
        return unwrappedFrame != null && unwrappedFrame.frameKind() == ChannelTransportPacketCodec.FrameKind.BATCH;
    }

    // simulate like original replay
    public static void replayInboundBatch(ChannelHandlerContext context, List<InboundReplayEntry> replayEntries) {
        if (context == null || replayEntries == null || replayEntries.isEmpty())
            return;

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
        if (channel == null || !channel.isActive())
            return;

        OutboundBatchState batchState = getOrCreateOutboundBatchState(channel);
        OutboundBatchDrain drainedBatch = batchState.drain();
        if (drainedBatch == null || drainedBatch.packetBytesList().isEmpty() || drainedBatch.context() == null)
            return;

        try {
            ChannelTransportSession transportSession = ChannelTransportStateManager.getOrCreateSession(channel);
            ChannelTransportPacketCodec.WrappedTransportFrame wrappedFrame =
                    ChannelTransportPacketCodec.wrapBatchPackets(transportSession, drainedBatch.packetBytesList());
            if (wrappedFrame == null)
                return;

            drainedBatch.context().writeAndFlush(Unpooled.wrappedBuffer(wrappedFrame.transportFrameBytes())).addListener(future -> {
                if (!future.isSuccess()) {
                    Throwable failure = future.cause() == null ? new IllegalStateException("Unknown outbound batch flush failure") : future.cause();
                    ChannelTransportRuntimeGuard.disableTransport("outbound-batch-flush", failure);
                    return;
                }
                ChannelTransportTelemetry.recordOutboundWrap(readProtocolName(drainedBatch.context()), wrappedFrame);
            });
        } catch (Throwable throwable) {
            ChannelTransportRuntimeGuard.disableTransport("outbound-batch-flush", throwable);
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
            ChannelTransportRuntimeGuard.disableTransport("inbound-batch-replay", throwable);}
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
                    pendingInboundFrame,
                    decodedPackets,
                    outputSizeBeforeDecode
            );
        } finally {
            restoredBuffer.release();
        }
    }

    private static String readProtocolName(ChannelHandlerContext context) {
        Object protocol = context.channel().attr(net.minecraft.network.Connection.ATTRIBUTE_PROTOCOL).get();
        return protocol == null ? "null" : String.valueOf(protocol);
    }

    private static ChannelHandlerContext readDecoderContext(ChannelHandlerContext context) {
        if (context == null || context.channel() == null || !context.channel().isActive()) {
            return null;
        }
        return context.channel().pipeline().context("decoder");
    }

    private static OutboundBatchState getOrCreateOutboundBatchState(Channel channel) {
        OutboundBatchState existingState = channel.attr(OUTBOUND_BATCH_STATE_KEY).get();
        if (existingState != null) {
            return existingState;
        }

        OutboundBatchState newState = new OutboundBatchState();
        OutboundBatchState racedState = channel.attr(OUTBOUND_BATCH_STATE_KEY).setIfAbsent(newState);
        return racedState != null ? racedState : newState;
    }




    private static InboundBatchState getOrCreateInboundBatchState(Channel channel) {
        InboundBatchState existingState = channel.attr(INBOUND_BATCH_STATE_KEY).get();
        if (existingState != null) {
            return existingState;
        }

        InboundBatchState newState = new InboundBatchState();
        InboundBatchState racedState = channel.attr(INBOUND_BATCH_STATE_KEY).setIfAbsent(newState);
        return racedState != null ? racedState : newState;
    }

    private static BatchApplicabilityState getOrCreateBatchApplicabilityState(Channel channel) {
        BatchApplicabilityState existingState = channel.attr(BATCH_APPLICABILITY_STATE_KEY).get();
        if (existingState != null) {
            return existingState;
        }

        BatchApplicabilityState newState = new BatchApplicabilityState();
        BatchApplicabilityState racedState = channel.attr(BATCH_APPLICABILITY_STATE_KEY).setIfAbsent(newState);
        return racedState != null ? racedState : newState;
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
    private record OutboundBatchDrain(ChannelHandlerContext context, List<byte[]> packetBytesList) { }



    private static final class OutboundBatchState {
        private final List<byte[]> pendingPacketBytesList = new ArrayList<>();
        private final AtomicBoolean flushScheduled = new AtomicBoolean();
        private ChannelHandlerContext lastContext;

        private void addPacket(ChannelHandlerContext context, byte[] packetBytes) {
            synchronized (this.pendingPacketBytesList) {
                this.lastContext = context;
                this.pendingPacketBytesList.add(packetBytes);
            }
        }

        private void scheduleFlushIfNeeded(Channel channel) {
            if (!this.flushScheduled.compareAndSet(false, true)) {
                return;
            }

            long windowMillis = ChannelTransportBatchRuntimeConfig.windowMillis();
            channel.eventLoop().schedule(() -> {
                try {
                    flushOutboundBatch(channel);
                } finally {
                    this.flushScheduled.set(false);
                    synchronized (this.pendingPacketBytesList) {
                        if (!this.pendingPacketBytesList.isEmpty()) {
                            scheduleFlushIfNeeded(channel);
                        }
                    }
                }
            }, windowMillis, TimeUnit.MILLISECONDS);
        }

        private OutboundBatchDrain drain() {
            synchronized (this.pendingPacketBytesList) {
                if (this.pendingPacketBytesList.isEmpty()) {
                    return null;
                }

                List<byte[]> drainedPacketBytesList = List.copyOf(this.pendingPacketBytesList);
                this.pendingPacketBytesList.clear();
                return new OutboundBatchDrain(this.lastContext, drainedPacketBytesList);
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
