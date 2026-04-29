package com.PinkCats.bandwidthoptimizer.channel.algorithm;

import com.PinkCats.bandwidthoptimizer.Config;

import java.util.Objects;
import java.util.function.Supplier;

public final class ChannelTransportLayerRuntimeConfig {

    private static final String PACKET_ID_MAPPING_ENABLED_PROPERTY =
            Config.RuntimeProperty.Transport.PACKET_ID_MAPPING_ENABLED;
    private static final String MAPPING_ENABLED_PROPERTY = Config.RuntimeProperty.Transport.MAPPING_ENABLED;
    private static final String ZSTD_ENABLED_PROPERTY = Config.RuntimeProperty.Transport.ZSTD_ENABLED;
    private static final ThreadLocal<RuntimeOverride> THREAD_RUNTIME_OVERRIDE = new ThreadLocal<>();

    private ChannelTransportLayerRuntimeConfig() {}

    // Fix thread conflict
    public static boolean isPacketIdMappingEnabled() {
        RuntimeOverride runtimeOverride = THREAD_RUNTIME_OVERRIDE.get();
        if (runtimeOverride != null) {
            return runtimeOverride.packetIdMappingEnabled();
        }
        return readBooleanOverride(
                PACKET_ID_MAPPING_ENABLED_PROPERTY,
                Config.RuntimeProperty.Transport.DEFAULT_PACKET_ID_MAPPING_ENABLED
        );
    }

    public static boolean isMappingEnabled() {
        RuntimeOverride runtimeOverride = THREAD_RUNTIME_OVERRIDE.get();
        if (runtimeOverride != null) {
            return runtimeOverride.mappingEnabled();
        }
        return readBooleanOverride(MAPPING_ENABLED_PROPERTY, Config.enableBatchTemplateDictionary);
    }


    public static boolean isZstdEnabled() {
        RuntimeOverride runtimeOverride = THREAD_RUNTIME_OVERRIDE.get();
        if (runtimeOverride != null) {
            return runtimeOverride.zstdEnabled();
        }
        return readBooleanOverride(
                ZSTD_ENABLED_PROPERTY,
                Config.RuntimeProperty.Transport.DEFAULT_ZSTD_ENABLED
        );
    }

    public static ChannelTransportAlgorithmId algorithmId() {
        return ChannelTransportAlgorithmId.resolve(isMappingEnabled(), isZstdEnabled());
    }

    // Now only one time is needed.
    public static <T> T withTemporaryOverride(RuntimeOverride runtimeOverride, Supplier<T> action) {
        Objects.requireNonNull(runtimeOverride, "runtimeOverride");
        Objects.requireNonNull(action, "action");
        RuntimeOverride previousOverride = THREAD_RUNTIME_OVERRIDE.get();
        THREAD_RUNTIME_OVERRIDE.set(runtimeOverride);
        try {
            return action.get();
        } finally {
            if (previousOverride == null) {
                THREAD_RUNTIME_OVERRIDE.remove();
            } else {
                THREAD_RUNTIME_OVERRIDE.set(previousOverride);
            }
        }
    }

    // Fix null problem for thread get
    public static void runWithTemporaryOverride(RuntimeOverride runtimeOverride, Runnable action) {
        withTemporaryOverride(runtimeOverride, () -> {
            action.run();
            return null;
        });
    }

    private static boolean readBooleanOverride(String propertyName, boolean defaultValue) {
        String rawValue = System.getProperty(propertyName);
        return rawValue == null || rawValue.isBlank() ? defaultValue : Boolean.parseBoolean(rawValue);
    }

    public record RuntimeOverride(
            boolean mappingEnabled,
            boolean zstdEnabled,
            boolean packetIdMappingEnabled
    ) {
    }
}
