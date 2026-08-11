package com.PinkCats.bandwidthoptimizer.report.unified;

import com.PinkCats.bandwidthoptimizer.report.traffic.TrafficPeriodReport;
import com.PinkCats.bandwidthoptimizer.report.traffic.TrafficHistoryReport;

public record BandwidthReportBundle(
        int schemaVersion,
        String reportId,
        long generatedAtMillis,
        String privacyLevel,
        UnifiedBandwidthReport summary,
        TrafficPeriodReport playerTraffic,
        TrafficHistoryReport trafficHistory
) {
}
