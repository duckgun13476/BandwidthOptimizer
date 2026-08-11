package com.PinkCats.bandwidthoptimizer.report.traffic;

import java.util.List;

public record TrafficHistoryReport(
        int schemaVersion,
        long periodStartMillis,
        long periodEndMillis,
        long generatedAtMillis,
        String zoneId,
        boolean complete,
        TrafficPeriodReport.TrafficCounters totals,
        List<TrafficPeriodReport.PlayerTraffic> players,
        List<HourlyTraffic> hours
) {
    public record HourlyTraffic(
            long periodStartMillis,
            long periodEndMillis,
            boolean complete,
            TrafficPeriodReport.TrafficCounters totals,
            List<PlayerWireTraffic> players
    ) {
    }

    public record PlayerWireTraffic(
            String playerUuid,
            String playerName,
            long outboundRawBytes,
            long outboundWireBytes,
            long inboundRawBytes,
            long inboundWireBytes
    ) {
    }
}
