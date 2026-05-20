package com.PinkCats.bandwidthoptimizer.server.stat;

import java.util.UUID;
import java.util.concurrent.atomic.LongAdder;

// Player stats
public final class ChannelBandwidthStats {

    private final String channelId;
    private final long createdAtMillis;
    private final LongAdder outboundRawEncodedPackets = new LongAdder();
    private final LongAdder outboundRawEncodedBytes = new LongAdder();
    private final LongAdder outboundVanillaCompressedEstimateBytes = new LongAdder();
    private final LongAdder inboundRawEncodedPackets = new LongAdder();
    private final LongAdder inboundRawEncodedBytes = new LongAdder();
    private final LongAdder outboundTransportFrames = new LongAdder();
    private final LongAdder outboundTransportFrameBytes = new LongAdder();
    private final LongAdder inboundTransportFrames = new LongAdder();
    private final LongAdder inboundTransportFrameBytes = new LongAdder();
    private final LongAdder outboundBypassPackets = new LongAdder();
    private final LongAdder outboundBypassBytes = new LongAdder();
    private final LongAdder inboundBypassPackets = new LongAdder();
    private final LongAdder inboundBypassBytes = new LongAdder();
    private final LongAdder outboundWireBytes = new LongAdder();
    private final LongAdder inboundWireBytes = new LongAdder();

    private volatile UUID playerId;
    private volatile String playerName;
    private volatile long boundAtMillis;

    public ChannelBandwidthStats(String channelId) {
        this.channelId = channelId == null || channelId.isBlank() ? "<unknown-channel>" : channelId;
        this.createdAtMillis = System.currentTimeMillis();
    }

    public void bindPlayer(UUID playerId, String playerName) {
        this.playerId = playerId;
        this.playerName = playerName == null || playerName.isBlank() ? "<unknown-player>" : playerName;
        this.boundAtMillis = System.currentTimeMillis();
    }

    public void unbindPlayer(UUID expectedPlayerId) {
        if (expectedPlayerId != null && this.playerId != null && !expectedPlayerId.equals(this.playerId)) {
            return;
        }
        this.playerId = null;
        this.playerName = null;
        this.boundAtMillis = 0L;
    }

    public void recordOutboundRawEncoded(int byteLength) {
        int safeByteLength = Math.max(byteLength, 0);
        this.outboundRawEncodedPackets.increment();
        this.outboundRawEncodedBytes.add(safeByteLength);
        this.outboundVanillaCompressedEstimateBytes.add(VanillaCompressionEstimator.estimateOutboundFrameBytes(null, safeByteLength));
    }

    public void recordOutboundRawEncoded(byte[] packetBytes, int fallbackByteLength) {
        int safeByteLength = packetBytes == null ? Math.max(fallbackByteLength, 0) : packetBytes.length;
        this.outboundRawEncodedPackets.increment();
        this.outboundRawEncodedBytes.add(safeByteLength);
        this.outboundVanillaCompressedEstimateBytes.add(VanillaCompressionEstimator.estimateOutboundFrameBytes(packetBytes, safeByteLength));
    }

    public void recordInboundRawEncoded(int byteLength, int packetCount) {
        this.inboundRawEncodedPackets.add(Math.max(packetCount, 0));
        this.inboundRawEncodedBytes.add(Math.max(byteLength, 0));
    }

    public void recordOutboundTransportFrame(int byteLength, int frameCount) {
        this.outboundTransportFrames.add(Math.max(frameCount, 0));
        this.outboundTransportFrameBytes.add(Math.max(byteLength, 0));
    }

    public void recordInboundTransportFrame(int byteLength, int frameCount) {
        this.inboundTransportFrames.add(Math.max(frameCount, 0));
        this.inboundTransportFrameBytes.add(Math.max(byteLength, 0));
    }

    public void recordOutboundBypass(int byteLength, int packetCount) {
        this.outboundBypassPackets.add(Math.max(packetCount, 0));
        this.outboundBypassBytes.add(Math.max(byteLength, 0));
    }

    public void recordInboundBypass(int byteLength, int packetCount) {
        this.inboundBypassPackets.add(Math.max(packetCount, 0));
        this.inboundBypassBytes.add(Math.max(byteLength, 0));
    }

    public void recordOutboundWire(int byteLength) {
        this.outboundWireBytes.add(Math.max(byteLength, 0));
    }

    public void recordInboundWire(int byteLength) {
        this.inboundWireBytes.add(Math.max(byteLength, 0));
    }

    public void resetCounters() {
        this.outboundRawEncodedPackets.reset();
        this.outboundRawEncodedBytes.reset();
        this.outboundVanillaCompressedEstimateBytes.reset();
        this.inboundRawEncodedPackets.reset();
        this.inboundRawEncodedBytes.reset();
        this.outboundTransportFrames.reset();
        this.outboundTransportFrameBytes.reset();
        this.inboundTransportFrames.reset();
        this.inboundTransportFrameBytes.reset();
        this.outboundBypassPackets.reset();
        this.outboundBypassBytes.reset();
        this.inboundBypassPackets.reset();
        this.inboundBypassBytes.reset();
        this.outboundWireBytes.reset();
        this.inboundWireBytes.reset();
    }

    public Snapshot snapshot() {
        return new Snapshot(
                this.channelId,
                this.playerId,
                this.playerName,
                this.createdAtMillis,
                this.boundAtMillis,
                this.outboundRawEncodedPackets.sum(),
                this.outboundRawEncodedBytes.sum(),
                this.outboundVanillaCompressedEstimateBytes.sum(),
                this.inboundRawEncodedPackets.sum(),
                this.inboundRawEncodedBytes.sum(),
                this.outboundTransportFrames.sum(),
                this.outboundTransportFrameBytes.sum(),
                this.inboundTransportFrames.sum(),
                this.inboundTransportFrameBytes.sum(),
                this.outboundBypassPackets.sum(),
                this.outboundBypassBytes.sum(),
                this.inboundBypassPackets.sum(),
                this.inboundBypassBytes.sum(),
                this.outboundWireBytes.sum(),
                this.inboundWireBytes.sum()
        );
    }

    public record Snapshot(
            String channelId,
            UUID playerId,
            String playerName,
            long createdAtMillis,
            long boundAtMillis,
            long outboundRawEncodedPackets,
            long outboundRawEncodedBytes,
            long outboundVanillaCompressedEstimateBytes,
            long inboundRawEncodedPackets,
            long inboundRawEncodedBytes,
            long outboundTransportFrames,
            long outboundTransportFrameBytes,
            long inboundTransportFrames,
            long inboundTransportFrameBytes,
            long outboundBypassPackets,
            long outboundBypassBytes,
            long inboundBypassPackets,
            long inboundBypassBytes,
            long outboundWireBytes,
            long inboundWireBytes
    ) {
        public Snapshot(
                String channelId,
                UUID playerId,
                String playerName,
                long createdAtMillis,
                long boundAtMillis,
                long outboundRawEncodedPackets,
                long outboundRawEncodedBytes,
                long inboundRawEncodedPackets,
                long inboundRawEncodedBytes,
                long outboundTransportFrames,
                long outboundTransportFrameBytes,
                long inboundTransportFrames,
                long inboundTransportFrameBytes,
                long outboundBypassPackets,
                long outboundBypassBytes,
                long inboundBypassPackets,
                long inboundBypassBytes,
                long outboundWireBytes,
                long inboundWireBytes
        ) {
            this(
                    channelId,
                    playerId,
                    playerName,
                    createdAtMillis,
                    boundAtMillis,
                    outboundRawEncodedPackets,
                    outboundRawEncodedBytes,
                    outboundRawEncodedBytes,
                    inboundRawEncodedPackets,
                    inboundRawEncodedBytes,
                    outboundTransportFrames,
                    outboundTransportFrameBytes,
                    inboundTransportFrames,
                    inboundTransportFrameBytes,
                    outboundBypassPackets,
                    outboundBypassBytes,
                    inboundBypassPackets,
                    inboundBypassBytes,
                    outboundWireBytes,
                    inboundWireBytes
            );
        }

        public long outboundTransportSavedBytes() {
            if (outboundTransportFrameBytes <= 0L) {
                return 0L;
            }
            return outboundVanillaCompressedEstimateBytes - outboundTransportFrameBytes - outboundBypassBytes;
        }
    }
}
