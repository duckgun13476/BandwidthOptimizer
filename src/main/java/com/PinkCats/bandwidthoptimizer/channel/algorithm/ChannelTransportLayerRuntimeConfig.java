package com.PinkCats.bandwidthoptimizer.channel.algorithm;

import com.PinkCats.bandwidthoptimizer.Config;


public final class ChannelTransportLayerRuntimeConfig {

    private static final String MAPPING_ENABLED_PROPERTY = "bandwidthoptimizer.transport.mappingEnabled";
    private static final String ZSTD_ENABLED_PROPERTY = "bandwidthoptimizer.transport.zstdEnabled";

    private ChannelTransportLayerRuntimeConfig() {}

    public static boolean isMappingEnabled() {
        return readBooleanOverride(MAPPING_ENABLED_PROPERTY, Config.enableBatchTemplateDictionary);
    }

    public static boolean isZstdEnabled() {
        return readBooleanOverride(ZSTD_ENABLED_PROPERTY, true);
    }

    public static ChannelTransportAlgorithmId algorithmId() {
        return ChannelTransportAlgorithmId.resolve(isMappingEnabled(), isZstdEnabled());
    }

    private static boolean readBooleanOverride(String propertyName, boolean defaultValue) {
        String rawValue = System.getProperty(propertyName);
        return rawValue == null || rawValue.isBlank() ? defaultValue : Boolean.parseBoolean(rawValue);
    }
}
