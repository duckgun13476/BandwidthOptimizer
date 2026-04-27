package com.PinkCats.bandwidthoptimizer.chunk.integration.transport;

import com.PinkCats.bandwidthoptimizer.Bandwidthoptimizer;
import com.PinkCats.bandwidthoptimizer.chunk.classify.packet.ChunkPacketCoordinate;
import com.PinkCats.bandwidthoptimizer.chunk.classify.packet.ChunkPacketDescriptor;
import com.PinkCats.bandwidthoptimizer.chunk.protocol.hotspot.ChunkHotspotFrame;
import io.netty.channel.Channel;
import io.netty.channel.ChannelHandlerContext;
import io.netty.util.AttributeKey;
import net.minecraft.network.PacketSendListener;
import net.minecraft.network.protocol.BundleDelimiterPacket;
import net.minecraft.network.protocol.BundlePacket;
import net.minecraft.network.protocol.Packet;
import net.minecraft.network.protocol.game.ClientboundForgetLevelChunkPacket;
import net.minecraft.network.protocol.game.ClientboundLoginPacket;
import net.minecraft.network.protocol.game.ClientboundRespawnPacket;
import net.minecraft.network.protocol.game.ClientboundSetChunkCacheCenterPacket;
import net.minecraft.network.protocol.game.ClientboundSetChunkCacheRadiusPacket;

import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.TimeUnit;

public final class ChunkTransportBoundaryController {

    private static final AttributeKey<ChannelBoundaryState> CHANNEL_BOUNDARY_STATE_KEY =
            AttributeKey.valueOf("bandwidthoptimizer:chunk_transport_boundary_state");

    private static final int LOGIN_WARMUP_CHUNK_PACKETS = 24;
    private static final int RESPAWN_WARMUP_CHUNK_PACKETS = 24;
    private static final long LOGIN_WARMUP_MIN_BYPASS_NANOS = TimeUnit.SECONDS.toNanos(1L);
    private static final long RESPAWN_WARMUP_MIN_BYPASS_NANOS = TimeUnit.SECONDS.toNanos(1L);
    private static final int CHUNK_CACHE_CONTROL_WARMUP_CHUNK_PACKETS = 2;
    private static final long CHUNK_CACHE_CONTROL_MIN_BYPASS_NANOS = TimeUnit.MILLISECONDS.toNanos(150L);
    private static final int FORGET_CHUNK_WARMUP_CHUNK_PACKETS = 2;
    private static final long FORGET_CHUNK_MIN_BYPASS_NANOS = TimeUnit.MILLISECONDS.toNanos(150L);
    private static final int BUNDLE_WARMUP_CHUNK_PACKETS = 1;
    private static final long BUNDLE_MIN_BYPASS_NANOS = TimeUnit.MILLISECONDS.toNanos(75L);
    private static final int KEEP_ALIVE_WARMUP_CHUNK_PACKETS = 1;
    private static final long KEEP_ALIVE_MIN_BYPASS_NANOS = TimeUnit.MILLISECONDS.toNanos(75L);
    private static final long OUTBOUND_BARRIER_ACK_TIMEOUT_NANOS = TimeUnit.SECONDS.toNanos(3L);
    private static final int MAX_TRACKED_FAILED_CHUNKS = 512;
    private static final int CHANNEL_FAILURE_THRESHOLD = 3;
    private static final long CHUNK_FAILURE_DISABLE_NANOS = TimeUnit.SECONDS.toNanos(15L);
    private static final long CHANNEL_FAILURE_WINDOW_NANOS = TimeUnit.SECONDS.toNanos(60L);
    private static final long CHANNEL_FAILURE_DISABLE_NANOS = TimeUnit.SECONDS.toNanos(20L);
    private static final java.util.Set<String> CLIENTBOUND_KEEP_ALIVE_PACKET_CLASS_NAMES = java.util.Set.of(
            "net.minecraft.network.protocol.common.ClientboundKeepAlivePacket",
            "net.minecraft.network.protocol.game.ClientboundKeepAlivePacket"
    );

    private ChunkTransportBoundaryController() {}


    public static void notePacketSendListener(Channel channel, PacketSendListener listener) {
        if (channel == null || listener == null) {
            return;
        }
        getOrCreateBoundaryState(channel).armDirectSendPermit();
    }


    public static OutboundBoundaryDecision beginOutboundPacket(
            ChannelHandlerContext context,
            String protocolName,
            Packet<?> packet
    ) {
        if (context == null || packet == null) {
            return OutboundBoundaryDecision.allow();
        }

        ChannelBoundaryState boundaryState = getOrCreateBoundaryState(context.channel());
        BoundaryTrigger boundaryTrigger = resolveBoundaryTrigger(protocolName, packet);
        boolean forceDirectTransport = boundaryState.consumeDirectSendPermit() || boundaryTrigger.forceDirectTransport();
        long barrierId = 0L;
        if (boundaryTrigger.chunkWarmupPackets() > 0 || boundaryTrigger.minimumChunkBypassNanos() > 0L) {
            boundaryState.armChunkWarmup(
                    boundaryTrigger.chunkWarmupPackets(),
                    boundaryTrigger.minimumChunkBypassNanos(),
                    boundaryTrigger.reason()
            );
        }
        if (boundaryTrigger.requiresOutboundBarrierAck()) {
            barrierId = boundaryState.armOutboundBarrier(boundaryTrigger.reason());
        }
        return forceDirectTransport
                ? OutboundBoundaryDecision.forceDirect(boundaryTrigger.reason(), barrierId)
                : OutboundBoundaryDecision.allow();
    }


    public static void scheduleOutboundBarrier(ChannelHandlerContext context, OutboundBoundaryDecision boundaryDecision) {
        if (context == null
                || boundaryDecision == null
                || !boundaryDecision.requiresOutboundBarrierAck()
                || boundaryDecision.barrierId() <= 0L) {
            return;
        }

        Channel channel = context.channel();
        long barrierId = boundaryDecision.barrierId();
        channel.eventLoop().execute(() -> dispatchPendingOutboundBarrier(channel, barrierId));
    }

    public static ChunkTransportPermit permitChunkTransport(
            ChannelHandlerContext context,
            ChunkPacketDescriptor descriptor
    ) {
        if (context == null || descriptor == null || descriptor.coordinate() == null || !descriptor.coordinate().present()) {
            return ChunkTransportPermit.allow();
        }
        return getOrCreateBoundaryState(context.channel()).consumeChunkPermit(descriptor.coordinate());
    }

    public static void observeInboundPacket(
            ChannelHandlerContext context,
            String protocolName,
            Packet<?> packet
    ) {
        if (context == null || packet == null) {
            return;
        }

        BoundaryTrigger boundaryTrigger = resolveBoundaryTrigger(protocolName, packet);
        if (boundaryTrigger.advancesInboundEpoch()) {
            getOrCreateBoundaryState(context.channel()).advanceInboundChunkEpoch();
        }
    }

    public static long readInboundChunkEpoch(ChannelHandlerContext context) {
        if (context == null || context.channel() == null) {
            return 0L;
        }
        return getOrCreateBoundaryState(context.channel()).readInboundChunkEpoch();
    }

    public static InboundRuntimeFrameDecision beginInboundRuntimeFrame(ChannelHandlerContext context, long frameEpoch) {
        if (context == null || context.channel() == null) {
            return InboundRuntimeFrameDecision.allow(0L);
        }
        return getOrCreateBoundaryState(context.channel()).evaluateInboundRuntimeFrame(frameEpoch);
    }

    public static void acknowledgeOutboundBarrier(ChannelHandlerContext context, ChunkHotspotFrame frame) {
        if (context == null || context.channel() == null || frame == null) {
            return;
        }
        getOrCreateBoundaryState(context.channel()).acknowledgeOutboundBarrier(frame.observedPacketCount(), frame.reason());
    }

    public static void recordRuntimeFailure(Channel channel, ChunkPacketCoordinate coordinate, String reason) {
        if (channel == null) {
            return;
        }
        getOrCreateBoundaryState(channel).recordRuntimeFailure(coordinate, reason);
    }


    private static BoundaryTrigger resolveBoundaryTrigger(String protocolName, Packet<?> packet) {
        if (packet instanceof ClientboundLoginPacket) {
            return new BoundaryTrigger(true, LOGIN_WARMUP_CHUNK_PACKETS, LOGIN_WARMUP_MIN_BYPASS_NANOS, true, true, "login_boundary");
        }
        if (packet instanceof ClientboundRespawnPacket) {
            return new BoundaryTrigger(true, RESPAWN_WARMUP_CHUNK_PACKETS, RESPAWN_WARMUP_MIN_BYPASS_NANOS, true, true, "respawn_boundary");
        }
        if (protocolName == null || !"PLAY".equalsIgnoreCase(protocolName)) {
            return new BoundaryTrigger(true, 0, 0L, false, false, "protocol_boundary_non_play");
        }
        if (packet instanceof ClientboundSetChunkCacheCenterPacket || packet instanceof ClientboundSetChunkCacheRadiusPacket) {
            return new BoundaryTrigger(
                    true,
                    CHUNK_CACHE_CONTROL_WARMUP_CHUNK_PACKETS,
                    CHUNK_CACHE_CONTROL_MIN_BYPASS_NANOS,
                    true,
                    false,
                    "chunk_cache_control_boundary"
            );
        }
        if (packet instanceof ClientboundForgetLevelChunkPacket) {
            return new BoundaryTrigger(
                    true,
                    FORGET_CHUNK_WARMUP_CHUNK_PACKETS,
                    FORGET_CHUNK_MIN_BYPASS_NANOS,
                    false,
                    false,
                    "forget_chunk_boundary"
            );
        }
        if (packet instanceof BundlePacket<?> || packet instanceof BundleDelimiterPacket) {
            return new BoundaryTrigger(
                    true,
                    BUNDLE_WARMUP_CHUNK_PACKETS,
                    BUNDLE_MIN_BYPASS_NANOS,
                    false,
                    false,
                    "bundle_boundary"
            );
        }
        if (packet != null && CLIENTBOUND_KEEP_ALIVE_PACKET_CLASS_NAMES.contains(packet.getClass().getName())) {
            return new BoundaryTrigger(
                    true,
                    KEEP_ALIVE_WARMUP_CHUNK_PACKETS,
                    KEEP_ALIVE_MIN_BYPASS_NANOS,
                    false,
                    false,
                    "keep_alive_boundary"
            );
        }
        return BoundaryTrigger.NONE;
    }

    private static void dispatchPendingOutboundBarrier(Channel channel, long barrierId) {
        if (channel == null || barrierId <= 0L) {
            return;
        }

        PendingOutboundBarrier pendingBarrier =
                getOrCreateBoundaryState(channel).snapshotPendingOutboundBarrier(barrierId, System.nanoTime());
        if (pendingBarrier == null) {
            return;
        }

        if (!ChunkTransportControlFrameSender.sendBoundaryBarrier(channel, pendingBarrier.barrierId(), pendingBarrier.reason())
                && channel.isOpen()
                && channel.isActive()) {
            Bandwidthoptimizer.LOGGER.info(
                    "[ChunkTransport][Barrier][SendSkipped] channel={}, barrierId={}, reason={}",
                    channel.id().asLongText(),
                    pendingBarrier.barrierId(),
                    pendingBarrier.reason()
            );
        }
    }

    private static ChannelBoundaryState getOrCreateBoundaryState(Channel channel) {
        ChannelBoundaryState existingState = channel.attr(CHANNEL_BOUNDARY_STATE_KEY).get();
        if (existingState != null) {
            return existingState;
        }

        ChannelBoundaryState newState = new ChannelBoundaryState();
        ChannelBoundaryState racedState = channel.attr(CHANNEL_BOUNDARY_STATE_KEY).setIfAbsent(newState);
        return racedState == null ? newState : racedState;
    }

    public record OutboundBoundaryDecision(
            boolean forceDirectTransport,
            boolean requiresOutboundBarrierAck,
            long barrierId,
            String reason
    ) {
        public OutboundBoundaryDecision {
            reason = reason == null ? "" : reason;
        }

        private static OutboundBoundaryDecision allow() {
            return new OutboundBoundaryDecision(false, false, 0L, "");
        }

        private static OutboundBoundaryDecision forceDirect(String reason, long barrierId) {
            return new OutboundBoundaryDecision(true, barrierId > 0L, barrierId, reason);
        }
    }

    public record ChunkTransportPermit(boolean allowed, String reason) {
        public ChunkTransportPermit {
            reason = reason == null ? "" : reason;
        }

        private static ChunkTransportPermit allow() {
            return new ChunkTransportPermit(true, "");
        }

        private static ChunkTransportPermit bypass(String reason) {
            return new ChunkTransportPermit(false, reason);
        }
    }

    public record InboundRuntimeFrameDecision(boolean allowed, String reason, long currentEpoch) {
        public InboundRuntimeFrameDecision {
            reason = reason == null ? "" : reason;
        }

        private static InboundRuntimeFrameDecision allow(long currentEpoch) {
            return new InboundRuntimeFrameDecision(true, "", currentEpoch);
        }

        private static InboundRuntimeFrameDecision reject(String reason, long currentEpoch) {
            return new InboundRuntimeFrameDecision(false, reason, currentEpoch);
        }
    }

    private record BoundaryTrigger(
            boolean forceDirectTransport,
            int chunkWarmupPackets,
            long minimumChunkBypassNanos,
            boolean requiresOutboundBarrierAck,
            boolean advancesInboundEpoch,
            String reason
    ) {
        private static final BoundaryTrigger NONE = new BoundaryTrigger(false, 0, 0L, false, false, "");
    }

    private record PendingOutboundBarrier(long barrierId, String reason) {
        private PendingOutboundBarrier {
            reason = reason == null ? "" : reason;
        }

        private String awaitReason() {
            return reason.isBlank()
                    ? "await_receiver_barrier_ack_after_boundary"
                    : "await_receiver_barrier_ack_after_" + reason;
        }
    }

    private static final class ChannelBoundaryState {

        private final LinkedHashMap<String, Long> failedChunkCooldowns = new LinkedHashMap<>(16, 0.75F, true);
        private int pendingDirectSendPermits;
        private int pendingChunkWarmupPackets;
        private long chunkWarmupUntilNanos;
        private String chunkWarmupReason = "";
        private long nextOutboundBarrierId;
        private long pendingOutboundBarrierId;
        private long pendingOutboundBarrierDeadlineNanos;
        private String pendingOutboundBarrierReason = "";
        private long inboundChunkEpoch;
        private long highestAcceptedInboundRuntimeEpoch;
        private long channelFailureWindowStartNanos = Long.MIN_VALUE;
        private int channelFailureCount;
        private long channelDisableUntilNanos;
        private String channelDisableReason = "";

        private synchronized void armDirectSendPermit() {
            this.pendingDirectSendPermits = Math.min(this.pendingDirectSendPermits + 1, 8);
        }

        private synchronized boolean consumeDirectSendPermit() {
            if (this.pendingDirectSendPermits <= 0) {
                return false;
            }
            this.pendingDirectSendPermits--;
            return true;
        }

        private synchronized void armChunkWarmup(int chunkWarmupPackets, long minimumChunkBypassNanos, String reason) {
            this.pendingChunkWarmupPackets = Math.max(this.pendingChunkWarmupPackets, Math.max(chunkWarmupPackets, 0));
            if (minimumChunkBypassNanos > 0L) {
                this.chunkWarmupUntilNanos = Math.max(
                        this.chunkWarmupUntilNanos,
                        System.nanoTime() + minimumChunkBypassNanos
                );
            }
            if (reason != null && !reason.isBlank()) {
                this.chunkWarmupReason = reason;
            }
        }

        private synchronized long armOutboundBarrier(String reason) {
            this.nextOutboundBarrierId = this.nextOutboundBarrierId <= 0L ? 1L : this.nextOutboundBarrierId + 1L;
            this.pendingOutboundBarrierId = this.nextOutboundBarrierId;
            this.pendingOutboundBarrierDeadlineNanos = System.nanoTime() + OUTBOUND_BARRIER_ACK_TIMEOUT_NANOS;
            this.pendingOutboundBarrierReason = reason == null ? "" : reason;
            return this.pendingOutboundBarrierId;
        }

        private synchronized PendingOutboundBarrier snapshotPendingOutboundBarrier(long barrierId, long nowNanos) {
            expireTimedOutBarrier(nowNanos);
            if (barrierId <= 0L || this.pendingOutboundBarrierId != barrierId) {
                return null;
            }
            return new PendingOutboundBarrier(this.pendingOutboundBarrierId, this.pendingOutboundBarrierReason);
        }

        private synchronized void acknowledgeOutboundBarrier(long barrierId, String reason) {
            if (barrierId <= 0L || this.pendingOutboundBarrierId != barrierId) {
                return;
            }
            this.pendingOutboundBarrierId = 0L;
            this.pendingOutboundBarrierDeadlineNanos = 0L;
            this.pendingOutboundBarrierReason = "";
            Bandwidthoptimizer.LOGGER.info(
                    "[ChunkTransport][Barrier][Ack] barrierId={}, reason={}",
                    barrierId,
                    reason == null ? "" : reason
            );
        }

        private synchronized void advanceInboundChunkEpoch() {
            this.inboundChunkEpoch = Math.max(this.inboundChunkEpoch + 1L, 1L);
        }

        private synchronized long readInboundChunkEpoch() {
            return Math.max(this.inboundChunkEpoch, this.highestAcceptedInboundRuntimeEpoch);
        }

        private synchronized InboundRuntimeFrameDecision evaluateInboundRuntimeFrame(long frameEpoch) {
            long currentEpoch = Math.max(this.inboundChunkEpoch, this.highestAcceptedInboundRuntimeEpoch);
            if (frameEpoch > 0L
                    && currentEpoch > 0L
                    && frameEpoch < currentEpoch) {
                return InboundRuntimeFrameDecision.reject("stale_inbound_runtime_epoch", currentEpoch);
            }
            if (frameEpoch > 0L) {
                this.highestAcceptedInboundRuntimeEpoch = Math.max(this.highestAcceptedInboundRuntimeEpoch, frameEpoch);
                currentEpoch = Math.max(currentEpoch, frameEpoch);
            }
            return InboundRuntimeFrameDecision.allow(currentEpoch);
        }

        private synchronized ChunkTransportPermit consumeChunkPermit(ChunkPacketCoordinate coordinate) {
            long nowNanos = System.nanoTime();
            pruneExpiredCooldowns(nowNanos);
            if (nowNanos < this.channelDisableUntilNanos) {
                return ChunkTransportPermit.bypass(this.channelDisableReason);
            }

            Long chunkDisableUntilNanos = this.failedChunkCooldowns.get(chunkKeyText(coordinate));
            if (chunkDisableUntilNanos != null && nowNanos < chunkDisableUntilNanos) {
                return ChunkTransportPermit.bypass("runtime_chunk_cooldown");
            }

            PendingOutboundBarrier pendingBarrier = snapshotPendingOutboundBarrier(this.pendingOutboundBarrierId, nowNanos);
            if (pendingBarrier != null) {
                return ChunkTransportPermit.bypass(pendingBarrier.awaitReason());
            }

            if (this.pendingChunkWarmupPackets > 0) {
                this.pendingChunkWarmupPackets--;
                return ChunkTransportPermit.bypass(this.chunkWarmupReason.isBlank() ? "post_boundary_warmup" : this.chunkWarmupReason);
            }
            if (nowNanos < this.chunkWarmupUntilNanos) {
                return ChunkTransportPermit.bypass(this.chunkWarmupReason.isBlank() ? "post_boundary_warmup" : this.chunkWarmupReason);
            }
            return ChunkTransportPermit.allow();
        }

        private synchronized void recordRuntimeFailure(ChunkPacketCoordinate coordinate, String reason) {
            long nowNanos = System.nanoTime();
            pruneExpiredCooldowns(nowNanos);
            if (coordinate != null && coordinate.present()) {
                this.failedChunkCooldowns.put(chunkKeyText(coordinate), nowNanos + CHUNK_FAILURE_DISABLE_NANOS);
                trimFailedChunkCooldowns();
            }

            if (this.channelFailureWindowStartNanos == Long.MIN_VALUE
                    || nowNanos - this.channelFailureWindowStartNanos > CHANNEL_FAILURE_WINDOW_NANOS) {
                this.channelFailureWindowStartNanos = nowNanos;
                this.channelFailureCount = 1;
            } else {
                this.channelFailureCount++;
            }

            if (this.channelFailureCount >= CHANNEL_FAILURE_THRESHOLD) {
                this.channelDisableUntilNanos = Math.max(this.channelDisableUntilNanos, nowNanos + CHANNEL_FAILURE_DISABLE_NANOS);
                this.channelDisableReason = reason == null || reason.isBlank()
                        ? "runtime_channel_cooldown"
                        : "runtime_channel_cooldown_" + reason;
            }
        }

        private void pruneExpiredCooldowns(long nowNanos) {
            Iterator<Map.Entry<String, Long>> iterator = this.failedChunkCooldowns.entrySet().iterator();
            while (iterator.hasNext()) {
                if (iterator.next().getValue() <= nowNanos) {
                    iterator.remove();
                }
            }

            if (nowNanos >= this.channelDisableUntilNanos) {
                this.channelDisableUntilNanos = 0L;
                this.channelDisableReason = "";
            }
        }

        private void expireTimedOutBarrier(long nowNanos) {
            if (this.pendingOutboundBarrierId <= 0L || nowNanos < this.pendingOutboundBarrierDeadlineNanos) {
                return;
            }
            Bandwidthoptimizer.LOGGER.info(
                    "[ChunkTransport][Barrier][Timeout] barrierId={}, reason={}",
                    this.pendingOutboundBarrierId,
                    this.pendingOutboundBarrierReason
            );
            this.pendingOutboundBarrierId = 0L;
            this.pendingOutboundBarrierDeadlineNanos = 0L;
            this.pendingOutboundBarrierReason = "";
        }

        private void trimFailedChunkCooldowns() {
            while (this.failedChunkCooldowns.size() > MAX_TRACKED_FAILED_CHUNKS) {
                Iterator<Map.Entry<String, Long>> iterator = this.failedChunkCooldowns.entrySet().iterator();
                if (!iterator.hasNext()) {
                    return;
                }
                iterator.next();
                iterator.remove();
            }
        }
    }

    private static String chunkKeyText(ChunkPacketCoordinate coordinate) {
        return coordinate == null || !coordinate.present()
                ? "<unknown>"
                : coordinate.chunkX() + "," + coordinate.chunkZ();
    }
}
