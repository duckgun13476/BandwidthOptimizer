package com.PinkCats.bandwidthoptimizer.report;

import com.PinkCats.bandwidthoptimizer.channel.algorithm.ChannelTransportLayerRuntimeConfig;

import java.util.List;

public final class ChannelTransportReportScenarioCatalog {

    private static final List<ScenarioPreset> DEFAULT_SCENARIOS = List.of(
            new ScenarioPreset("sza", "SZA", true, false, false, false),
            new ScenarioPreset("tda_sza", "TDA + SZA", false, true, false, false),
            new ScenarioPreset("batch_sza", "BATCH + SZA", false, false, true, false),
            new ScenarioPreset("batch_tda_sza", "BATCH + TDA + SZA", false, true, true, false),
            new ScenarioPreset("batch_header_sza", "BATCH + HM + SZA", false, false, true, true)
    );

    private ChannelTransportReportScenarioCatalog() {}

    public static List<ScenarioPreset> defaultScenarios() {
        return DEFAULT_SCENARIOS;
    }

    public record ScenarioPreset(
            String id,
            String displayName,
            boolean baseline,
            boolean mappingEnabled,
            boolean batchEnabled,
            boolean packetIdMappingEnabled
    ) {
        public ChannelTransportLayerRuntimeConfig.RuntimeOverride toRuntimeOverride() {
            return new ChannelTransportLayerRuntimeConfig.RuntimeOverride(
                    this.mappingEnabled,
                    true,
                    this.packetIdMappingEnabled
            );
        }
    }
}
