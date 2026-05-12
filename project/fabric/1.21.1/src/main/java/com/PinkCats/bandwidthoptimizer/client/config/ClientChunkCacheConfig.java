package com.PinkCats.bandwidthoptimizer.client.config;

import com.PinkCats.bandwidthoptimizer.Bandwidthoptimizer;
import com.PinkCats.bandwidthoptimizer.Config;

public final class ClientChunkCacheConfig {

    private static final long BYTES_PER_MB = 1024L * 1024L;
    private static final String MAX_MEMORY_OVERRIDE_PROPERTY =
            Config.RuntimeProperty.Client.CHUNK_CACHE_MAX_MEMORY_MB;
    private static final String RECYCLE_TRIGGER_OVERRIDE_PROPERTY =
            Config.RuntimeProperty.Client.CHUNK_CACHE_RECYCLE_TRIGGER_FREE_MB;
    private static final String PERSISTENT_CACHE_BACKUP_ENABLED_PROPERTY =
            "bandwidthoptimizer.clientPersistentChunkCacheBackupEnabled";
    private static final String PERSISTENT_CACHE_BACKUP_INTERVAL_MILLIS_PROPERTY =
            "bandwidthoptimizer.clientPersistentChunkCacheBackupMillis";
    private static final ForgeConfigSpec.Builder BUILDER = new ForgeConfigSpec.Builder();

    public static final ForgeConfigSpec.IntValue CHUNK_CACHE_MAX_MEMORY_MB;
    public static final ForgeConfigSpec.IntValue CHUNK_CACHE_RECYCLE_TRIGGER_FREE_MB;
    public static final ForgeConfigSpec.BooleanValue PERSISTENT_CACHE_BACKUP_ENABLED;
    public static final ForgeConfigSpec.IntValue PERSISTENT_CACHE_BACKUP_INTERVAL_SECONDS;
    public static final ForgeConfigSpec SPEC;

    private static volatile int chunkCacheMaxMemoryMb = Config.RuntimeProperty.Client.DEFAULT_CHUNK_CACHE_MAX_MEMORY_MB;
    private static volatile int chunkCacheRecycleTriggerFreeMb =
            Config.RuntimeProperty.Client.DEFAULT_CHUNK_CACHE_RECYCLE_TRIGGER_FREE_MB;
    private static volatile boolean persistentCacheBackupEnabled = true;
    private static volatile int persistentCacheBackupIntervalSeconds = 600;

    static {
        BUILDER.comment("Client Chunk Cache Settings").push("client-chunk-cache");

        CHUNK_CACHE_MAX_MEMORY_MB = BUILDER
                .comment("")
                .comment("--------------------------------------------------------------------------")
                .comment("Maximum estimated memory budget in MiB used by the client-side chunk transport cache.")
                .defineInRange("chunk_cache_max_memory_mb", 110, 50, 500);

        CHUNK_CACHE_RECYCLE_TRIGGER_FREE_MB = BUILDER
                .comment("")
                .comment("--------------------------------------------------------------------------")
                .comment("Start recycling old chunk cache entries when the remaining cache budget drops below this MiB threshold.")
                .defineInRange("chunk_cache_recycle_trigger_free_mb", 10, 1, 128);

        PERSISTENT_CACHE_BACKUP_ENABLED = BUILDER
                .comment("")
                .comment("--------------------------------------------------------------------------")
                .comment("Enable client-only periodic backup for the persistent chunk cache zip.")
                .define("persistent_cache_backup_enabled", true);

        PERSISTENT_CACHE_BACKUP_INTERVAL_SECONDS = BUILDER
                .comment("")
                .comment("--------------------------------------------------------------------------")
                .comment("Interval in seconds for copying the persistent chunk cache zip to a backup zip.")
                .defineInRange("persistent_cache_backup_interval_seconds", 600, 1, 3600);

        BUILDER.pop();
        SPEC = BUILDER.build();
    }

    private ClientChunkCacheConfig() {}


    static void onLoad(final ModConfigEvent event) {
        if (event.getConfig().getSpec() != SPEC) {
            return;
        }
        applyRuntimeConfig(currentRuntimeConfig());
    }


    public static RuntimeConfig currentRuntimeConfig() {
        return new RuntimeConfig(
                readIntOverride(MAX_MEMORY_OVERRIDE_PROPERTY, CHUNK_CACHE_MAX_MEMORY_MB.get()),
                readIntOverride(RECYCLE_TRIGGER_OVERRIDE_PROPERTY, CHUNK_CACHE_RECYCLE_TRIGGER_FREE_MB.get()),
                PERSISTENT_CACHE_BACKUP_ENABLED.get(),
                PERSISTENT_CACHE_BACKUP_INTERVAL_SECONDS.get()
        );
    }


    // clear when cache over limit
    public static void applyRuntimeConfig(RuntimeConfig runtimeConfig) {
        boolean hasMaxMemoryOverride = hasIntOverride(MAX_MEMORY_OVERRIDE_PROPERTY);
        boolean hasRecycleTriggerOverride = hasIntOverride(RECYCLE_TRIGGER_OVERRIDE_PROPERTY);

        int safeMaxMemoryMb = runtimeConfig == null ? 110 : runtimeConfig.chunkCacheMaxMemoryMb();
        safeMaxMemoryMb = Math.max(hasMaxMemoryOverride ? 2 : 50, Math.min(500, safeMaxMemoryMb));

        int safeRecycleTriggerFreeMb = runtimeConfig == null ? 10 : runtimeConfig.chunkCacheRecycleTriggerFreeMb();
        safeRecycleTriggerFreeMb = Math.max(1, Math.min(safeMaxMemoryMb - 1, safeRecycleTriggerFreeMb));

        boolean safePersistentBackupEnabled = runtimeConfig == null || runtimeConfig.persistentCacheBackupEnabled();
        int safePersistentBackupIntervalSeconds = runtimeConfig == null ? 600 : runtimeConfig.persistentCacheBackupIntervalSeconds();
        safePersistentBackupIntervalSeconds = Math.max(1, Math.min(3600, safePersistentBackupIntervalSeconds));

        chunkCacheMaxMemoryMb = safeMaxMemoryMb;
        chunkCacheRecycleTriggerFreeMb = safeRecycleTriggerFreeMb;
        persistentCacheBackupEnabled = safePersistentBackupEnabled;
        persistentCacheBackupIntervalSeconds = safePersistentBackupIntervalSeconds;
        System.setProperty(PERSISTENT_CACHE_BACKUP_ENABLED_PROPERTY, Boolean.toString(persistentCacheBackupEnabled));
        System.setProperty(
                PERSISTENT_CACHE_BACKUP_INTERVAL_MILLIS_PROPERTY,
                Long.toString((long) persistentCacheBackupIntervalSeconds * 1000L)
        );

        Bandwidthoptimizer.LOGGER.info(
                "[ChunkCache][Config] maxMemoryMb={}, recycleTriggerFreeMb={}, persistentBackupEnabled={}, persistentBackupIntervalSeconds={}, maxOverride={}, recycleOverride={}",
                chunkCacheMaxMemoryMb,
                chunkCacheRecycleTriggerFreeMb,
                persistentCacheBackupEnabled,
                persistentCacheBackupIntervalSeconds,
                hasMaxMemoryOverride,
                hasRecycleTriggerOverride
        );
    }


    public static long chunkCacheMaxMemoryBytes() {
        return Math.max(chunkCacheMaxMemoryMb, 1) * BYTES_PER_MB;
    }


    public static long chunkCacheRecycleTriggerFreeBytes() {
        return Math.max(chunkCacheRecycleTriggerFreeMb, 1) * BYTES_PER_MB;
    }


    public static long chunkCacheRecycleTargetFreeBytes() {
        long maxBytes = chunkCacheMaxMemoryBytes();
        long triggerFreeBytes = chunkCacheRecycleTriggerFreeBytes();
        long targetFreeBytes = Math.min(maxBytes - BYTES_PER_MB, triggerFreeBytes + 10L * BYTES_PER_MB);
        return Math.max(targetFreeBytes, triggerFreeBytes);
    }

    public record RuntimeConfig(
            int chunkCacheMaxMemoryMb,
            int chunkCacheRecycleTriggerFreeMb,
            boolean persistentCacheBackupEnabled,
            int persistentCacheBackupIntervalSeconds
    ) {
    }

    private static int readIntOverride(String propertyName, int fallbackValue) {
        if (propertyName == null || propertyName.isBlank()) {
            return fallbackValue;
        }

        String rawValue = System.getProperty(propertyName);
        if (rawValue == null || rawValue.isBlank()) {
            return fallbackValue;
        }

        try {
            return Integer.parseInt(rawValue.trim());
        } catch (NumberFormatException ignored) {
            return fallbackValue;
        }
    }

    private static boolean hasIntOverride(String propertyName) {
        if (propertyName == null || propertyName.isBlank()) {
            return false;
        }

        String rawValue = System.getProperty(propertyName);
        if (rawValue == null || rawValue.isBlank()) {
            return false;
        }

        try {
            Integer.parseInt(rawValue.trim());
            return true;
        } catch (NumberFormatException ignored) {
            return false;
        }
    }

    private static final class ForgeConfigSpec {
        private static final class Builder {
            Builder comment(String value) { return this; }
            Builder push(String value) { return this; }
            Builder pop() { return this; }
            IntValue defineInRange(String name, int defaultValue, int minimum, int maximum) { return new IntValue(defaultValue); }
            BooleanValue define(String name, boolean defaultValue) { return new BooleanValue(defaultValue); }
            ForgeConfigSpec build() { return new ForgeConfigSpec(); }
        }

        private static final class IntValue {
            private final int value;

            private IntValue(int value) {
                this.value = value;
            }

            int get() {
                return value;
            }
        }

        private static final class BooleanValue {
            private final boolean value;

            private BooleanValue(boolean value) {
                this.value = value;
            }

            boolean get() {
                return value;
            }
        }
    }

    private static final class ModConfigEvent {
        private ModConfig getConfig() {
            return new ModConfig();
        }
    }

    private static final class ModConfig {
        private Object getSpec() {
            return null;
        }
    }}



