package com.PinkCats.bandwidthoptimizer.chunk.integration.transport;

import com.PinkCats.bandwidthoptimizer.chunk.classify.packet.ChunkPacketCoordinate;
import com.PinkCats.bandwidthoptimizer.chunk.classify.packet.ChunkPacketDescriptor;
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
        if (channel == null || listener == null)
            return;
        getOrCreateBoundaryState(channel).armDirectSendPermit();
    }


    public static OutboundBoundaryDecision beginOutboundPacket(
            ChannelHandlerContext context,
            String protocolName,
            Packet<?> packet
    ) {
        if (context == null || packet == null)
            return OutboundBoundaryDecision.allow();

        ChannelBoundaryState boundaryState = getOrCreateBoundaryState(context.channel());
        BoundaryTrigger boundaryTrigger = resolveBoundaryTrigger(protocolName, packet);
        boolean forceDirectTransport = boundaryState.consumeDirectSendPermit() || boundaryTrigger.forceDirectTransport();
        if (boundaryTrigger.chunkWarmupPackets() > 0)
            boundaryState.armChunkWarmup(boundaryTrigger.chunkWarmupPackets(), boundaryTrigger.reason());
        return forceDirectTransport
                ? OutboundBoundaryDecision.forceDirect(boundaryTrigger.reason())
                : OutboundBoundaryDecision.allow();
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

    public static void recordRuntimeFailure(Channel channel, ChunkPacketCoordinate coordinate, String reason) {
        if (channel == null) {
            return;
        }
        getOrCreateBoundaryState(channel).recordRuntimeFailure(coordinate, reason);
    }

    // Chunk border controller
    private static BoundaryTrigger resolveBoundaryTrigger(String protocolName, Packet<?> packet) {
        if (protocolName == null || !"PLAY".equalsIgnoreCase(protocolName)) {
            return new BoundaryTrigger(true, 0, "protocol_boundary_non_play");
        }

        if (packet instanceof ClientboundLoginPacket) {
            return new BoundaryTrigger(true, LOGIN_WARMUP_CHUNK_PACKETS, "login_boundary");
        }
        if (packet instanceof ClientboundRespawnPacket) {
            return new BoundaryTrigger(true, RESPAWN_WARMUP_CHUNK_PACKETS, "respawn_boundary");
        }
        if (packet instanceof ClientboundSetChunkCacheCenterPacket || packet instanceof ClientboundSetChunkCacheRadiusPacket) {
            return new BoundaryTrigger(true, 0, "chunk_cache_control_boundary");
        }
        if (packet instanceof ClientboundForgetLevelChunkPacket) {
            return new BoundaryTrigger(true, 0, "forget_chunk_boundary");
        }
        if (packet instanceof BundlePacket<?> || packet instanceof BundleDelimiterPacket) {
            return new BoundaryTrigger(true, 0, "bundle_boundary");
        }
        if (packet != null && CLIENTBOUND_KEEP_ALIVE_PACKET_CLASS_NAMES.contains(packet.getClass().getName())) {
            return new BoundaryTrigger(true, 0, "keep_alive_boundary");
        }
        return BoundaryTrigger.NONE;
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

    public record OutboundBoundaryDecision(boolean forceDirectTransport, String reason) {
        public OutboundBoundaryDecision {
            reason = reason == null ? "" : reason;
        }

        private static OutboundBoundaryDecision allow() {
            return new OutboundBoundaryDecision(false, "");
        }

        private static OutboundBoundaryDecision forceDirect(String reason) {
            return new OutboundBoundaryDecision(true, reason);
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

    private record BoundaryTrigger(boolean forceDirectTransport, int chunkWarmupPackets, String reason) {
        private static final BoundaryTrigger NONE = new BoundaryTrigger(false, 0, "");
    }

    private static final class ChannelBoundaryState {

        private final LinkedHashMap<String, Long> failedChunkCooldowns = new LinkedHashMap<>(16, 0.75F, true);
        private int pendingDirectSendPermits;
        private int pendingChunkWarmupPackets;
        private String chunkWarmupReason = "";
        private long channelFailureWindowStartNanos = Long.MIN_VALUE;
        private int channelFailureCount;
        private long channelDisableUntilNanos;
        private String channelDisableReason = "";


        private synchronized void armDirectSendPermit() {
            this.pendingDirectSendPermits = Math.min(this.pendingDirectSendPermits + 1, 8);
        }

        private synchronized boolean consumeDirectSendPermit() {
            if (this.pendingDirectSendPermits <= 0)
                return false;
            this.pendingDirectSendPermits--;
            return true;
        }


        private synchronized void armChunkWarmup(int chunkWarmupPackets, String reason) {
            this.pendingChunkWarmupPackets = Math.max(this.pendingChunkWarmupPackets, Math.max(chunkWarmupPackets, 0));
            if (reason != null && !reason.isBlank())
                this.chunkWarmupReason = reason;
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

            if (this.pendingChunkWarmupPackets > 0) {
                this.pendingChunkWarmupPackets--;
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
