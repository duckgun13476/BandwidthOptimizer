package com.PinkCats.bandwidthoptimizer.gate.integration.create;

import com.PinkCats.bandwidthoptimizer.Config;

import java.util.concurrent.TimeUnit;

final class CreateGateQueueConfig {

    private CreateGateQueueConfig() {}

    static long maxDelayNanos() {
        return TimeUnit.MILLISECONDS.toNanos(readLong(
                Config.RuntimeProperty.Create.CREATE_BLOCK_ENTITY_UPDATE_GATE_MAX_DELAY_MILLIS,
                Config.RuntimeProperty.Create.DEFAULT_CREATE_BLOCK_ENTITY_UPDATE_GATE_MAX_DELAY_MILLIS,
                50L,
                10_000L));
    }

    static int maxPendingPerPlayer() {
        return (int) readLong(
                Config.RuntimeProperty.Create.CREATE_BLOCK_ENTITY_UPDATE_GATE_MAX_PENDING_PER_PLAYER,
                Config.RuntimeProperty.Create.DEFAULT_CREATE_BLOCK_ENTITY_UPDATE_GATE_MAX_PENDING_PER_PLAYER,
                1L,
                8192L);
    }

    static long chunkBootstrapNanos() {
        return TimeUnit.MILLISECONDS.toNanos(readLong(
                Config.RuntimeProperty.Create.CREATE_BLOCK_ENTITY_UPDATE_GATE_CHUNK_BOOTSTRAP_MILLIS,
                Config.RuntimeProperty.Create.DEFAULT_CREATE_BLOCK_ENTITY_UPDATE_GATE_CHUNK_BOOTSTRAP_MILLIS,
                0L,
                15_000L));
    }

    private static long readLong(String propertyName, long defaultValue, long minValue, long maxValue) {
        String rawValue = System.getProperty(propertyName);
        if (rawValue == null || rawValue.isBlank()) {
            return defaultValue;
        }
        try {
            long parsed = Long.parseLong(rawValue.trim());
            return Math.max(minValue, Math.min(maxValue, parsed));
        } catch (NumberFormatException ignored) {
            return defaultValue;
        }
    }
}
