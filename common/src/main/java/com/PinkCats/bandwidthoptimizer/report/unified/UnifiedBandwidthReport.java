package com.PinkCats.bandwidthoptimizer.report.unified;

import com.PinkCats.bandwidthoptimizer.channel.ChannelTransportBypassRankCore;

import java.util.List;

public record UnifiedBandwidthReport(
        int schemaVersion,
        String reportId,
        long generatedAtMillis,
        String modVersion,
        String physicalSide,
        String privacyLevel,
        List<Section> sections,
        ChannelTransportBypassRankCore.BypassSnapshot bypass
) {
    public record Section(
            String id,
            String title,
            String description,
            List<Metric> metrics
    ) {
    }

    public record Metric(
            String id,
            String label,
            long value,
            String unit,
            String stage,
            String scope,
            String semantics
    ) {
    }
}
