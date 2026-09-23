package com.PinkCats.bandwidthoptimizer.client.config;

import com.PinkCats.bandwidthoptimizer.Bandwidthoptimizer;
import com.PinkCats.bandwidthoptimizer.Config;
import com.PinkCats.bandwidthoptimizer.chunk.persistent.ChunkPersistentClientCache;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.neoforge.common.ModConfigSpec;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.Mod;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.fml.event.config.ModConfigEvent;

@EventBusSubscriber(modid = Bandwidthoptimizer.MODID, value = Dist.CLIENT)
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
    private static final ModConfigSpec.Builder BUILDER = new ModConfigSpec.Builder();

    public static final ModConfigSpec.IntValue CHUNK_CACHE_MAX_MEMORY_MB;
    public static final ModConfigSpec.IntValue CHUNK_CACHE_RECYCLE_TRIGGER_FREE_MB;
    public static final ModConfigSpec.IntValue PERSISTENT_CACHE_MAX_DISK_MB;
    public static final ModConfigSpec.BooleanValue PERSISTENT_CACHE_BACKUP_ENABLED;
    public static final ModConfigSpec.IntValue PERSISTENT_CACHE_BACKUP_INTERVAL_SECONDS;
    public static final ModConfigSpec SPEC;

    private static volatile int chunkCacheMaxMemoryMb = Config.RuntimeProperty.Client.DEFAULT_CHUNK_CACHE_MAX_MEMORY_MB;
    private static volatile int chunkCacheRecycleTriggerFreeMb =
            Config.RuntimeProperty.Client.DEFAULT_CHUNK_CACHE_RECYCLE_TRIGGER_FREE_MB;
    private static volatile int persistentCacheMaxDiskMb = 256;
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

        PERSISTENT_CACHE_MAX_DISK_MB = BUILDER
                .comment("")
                .comment("--------------------------------------------------------------------------")
                .comment("Maximum persistent chunk cache size on disk in MiB. Excess data is removed during offline maintenance.")
                .defineInRange("persistent_cache_max_disk_mb", 256, 128, 4096);

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


    @SubscribeEvent
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
                PERSISTENT_CACHE_MAX_DISK_MB.get(),
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

        int safePersistentCacheMaxDiskMb = runtimeConfig == null ? 256 : runtimeConfig.persistentCacheMaxDiskMb();
        safePersistentCacheMaxDiskMb = Math.max(128, Math.min(4096, safePersistentCacheMaxDiskMb));

        boolean safePersistentBackupEnabled = runtimeConfig == null || runtimeConfig.persistentCacheBackupEnabled();
        int safePersistentBackupIntervalSeconds = runtimeConfig == null ? 600 : runtimeConfig.persistentCacheBackupIntervalSeconds();
        safePersistentBackupIntervalSeconds = Math.max(1, Math.min(3600, safePersistentBackupIntervalSeconds));

        chunkCacheMaxMemoryMb = safeMaxMemoryMb;
        chunkCacheRecycleTriggerFreeMb = safeRecycleTriggerFreeMb;
        persistentCacheMaxDiskMb = safePersistentCacheMaxDiskMb;
        persistentCacheBackupEnabled = safePersistentBackupEnabled;
        persistentCacheBackupIntervalSeconds = safePersistentBackupIntervalSeconds;
        ChunkPersistentClientCache.configurePersistentCacheMaxDiskBytes((long) persistentCacheMaxDiskMb * BYTES_PER_MB);
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
            int persistentCacheMaxDiskMb,
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
}
