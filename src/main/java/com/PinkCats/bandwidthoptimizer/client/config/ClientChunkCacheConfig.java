package com.PinkCats.bandwidthoptimizer.client.config;

import com.PinkCats.bandwidthoptimizer.Bandwidthoptimizer;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.common.ForgeConfigSpec;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;
import net.minecraftforge.fml.event.config.ModConfigEvent;

@Mod.EventBusSubscriber(modid = Bandwidthoptimizer.MODID, value = Dist.CLIENT, bus = Mod.EventBusSubscriber.Bus.MOD)
public final class ClientChunkCacheConfig {

    private static final long BYTES_PER_MB = 1024L * 1024L;
    private static final String MAX_MEMORY_OVERRIDE_PROPERTY =
            "bandwidthoptimizer.clientChunkCacheMaxMemoryMb";
    private static final String RECYCLE_TRIGGER_OVERRIDE_PROPERTY =
            "bandwidthoptimizer.clientChunkCacheRecycleTriggerFreeMb";
    private static final ForgeConfigSpec.Builder BUILDER = new ForgeConfigSpec.Builder();

    public static final ForgeConfigSpec.IntValue CHUNK_CACHE_MAX_MEMORY_MB;
    public static final ForgeConfigSpec.IntValue CHUNK_CACHE_RECYCLE_TRIGGER_FREE_MB;
    public static final ForgeConfigSpec SPEC;

    private static volatile int chunkCacheMaxMemoryMb = 110;
    private static volatile int chunkCacheRecycleTriggerFreeMb = 10;

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
                readIntOverride(RECYCLE_TRIGGER_OVERRIDE_PROPERTY, CHUNK_CACHE_RECYCLE_TRIGGER_FREE_MB.get())
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

        chunkCacheMaxMemoryMb = safeMaxMemoryMb;
        chunkCacheRecycleTriggerFreeMb = safeRecycleTriggerFreeMb;

        Bandwidthoptimizer.LOGGER.info(
                "[ChunkCache][Config] maxMemoryMb={}, recycleTriggerFreeMb={}, maxOverride={}, recycleOverride={}",
                chunkCacheMaxMemoryMb,
                chunkCacheRecycleTriggerFreeMb,
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
            int chunkCacheRecycleTriggerFreeMb
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
