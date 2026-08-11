package com.PinkCats.bandwidthoptimizer.report.traffic;

import java.util.List;

public record TrafficPeriodReport(
        int schemaVersion,
        String reportId,
        String periodType,
        long periodStartMillis,
        long periodEndMillis,
        long generatedAtMillis,
        String zoneId,
        boolean complete,
        String privacyLevel,
        TrafficCounters totals,
        List<PlayerTraffic> players
) {
    public record PlayerTraffic(
            String playerUuid,
            String playerName,
            TrafficCounters traffic
    ) {
    }

    public record TrafficCounters(
            long outboundRawPackets,
            long outboundRawBytes,
            long outboundVanillaEstimateBytes,
            long outboundVanillaEstimateWireBytes,
            long outboundTransportFrames,
            long outboundTransportBytes,
            long outboundBypassPackets,
            long outboundBypassBytes,
            long outboundWireBytes,
            long inboundRawPackets,
            long inboundRawBytes,
            long inboundTransportFrames,
            long inboundTransportBytes,
            long inboundBypassPackets,
            long inboundBypassBytes,
            long inboundWireBytes
    ) {
        public static TrafficCounters empty() {
            return new TrafficCounters(0L, 0L, 0L, 0L, 0L, 0L, 0L, 0L, 0L, 0L, 0L, 0L, 0L, 0L, 0L, 0L);
        }

        public TrafficCounters plus(TrafficCounters other) {
            if (other == null) {
                return this;
            }
            return new TrafficCounters(
                    outboundRawPackets + other.outboundRawPackets,
                    outboundRawBytes + other.outboundRawBytes,
                    outboundVanillaEstimateBytes + other.outboundVanillaEstimateBytes,
                    outboundVanillaEstimateWireBytes + other.outboundVanillaEstimateWireBytes,
                    outboundTransportFrames + other.outboundTransportFrames,
                    outboundTransportBytes + other.outboundTransportBytes,
                    outboundBypassPackets + other.outboundBypassPackets,
                    outboundBypassBytes + other.outboundBypassBytes,
                    outboundWireBytes + other.outboundWireBytes,
                    inboundRawPackets + other.inboundRawPackets,
                    inboundRawBytes + other.inboundRawBytes,
                    inboundTransportFrames + other.inboundTransportFrames,
                    inboundTransportBytes + other.inboundTransportBytes,
                    inboundBypassPackets + other.inboundBypassPackets,
                    inboundBypassBytes + other.inboundBypassBytes,
                    inboundWireBytes + other.inboundWireBytes
            );
        }

        public long totalWireBytes() {
            return outboundWireBytes + inboundWireBytes;
        }

        public boolean isEmpty() {
            return this.equals(empty());
        }
    }
}
