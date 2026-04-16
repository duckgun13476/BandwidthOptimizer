package com.PinkCats.bandwidthoptimizer;

import com.PinkCats.bandwidthoptimizer.network.ModNetwork;
import net.minecraftforge.common.ForgeConfigSpec;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;
import net.minecraftforge.fml.event.config.ModConfigEvent;
import net.minecraftforge.server.ServerLifecycleHooks;

@Mod.EventBusSubscriber(modid = Bandwidthoptimizer.MODID, bus = Mod.EventBusSubscriber.Bus.MOD)
public class Config {
    private static final ForgeConfigSpec.Builder BUILDER = new ForgeConfigSpec.Builder();

    public static final ForgeConfigSpec.BooleanValue ENABLE_OPTIMIZER_STATS_LOGS;
    public static final ForgeConfigSpec.BooleanValue ENABLE_TEST_MODE;
    public static final ForgeConfigSpec.IntValue STATS_LOG_INTERVAL_MINUTES;
    private static final int TEST_MODE_STATS_LOG_INTERVAL_MINUTES = 3;

    public static final ForgeConfigSpec.BooleanValue ENABLE_BATCH_REFERENCE_DEDUP;
    public static final ForgeConfigSpec.BooleanValue ENABLE_BATCH_SHA256_DICTIONARY;
    public static final ForgeConfigSpec.BooleanValue ENABLE_BATCH_TEMPLATE_DICTIONARY;
    public static final ForgeConfigSpec.BooleanValue ENABLE_BATCH_ZSTD;
    public static final ForgeConfigSpec.BooleanValue ENABLE_BATCH_STREAMING_ZSTD;
    public static final ForgeConfigSpec.BooleanValue ENABLE_ASYNC_PLAY_BATCH_ENCODING;
    public static final ForgeConfigSpec.IntValue BATCH_MIN_PACKET_COUNT;
    public static final ForgeConfigSpec.IntValue BATCH_MIN_RAW_BYTES;

    public static final ForgeConfigSpec.IntValue BATCH_ZSTD_LEVEL;
    public static final ForgeConfigSpec.IntValue BATCH_STREAMING_ZSTD_LEVEL;

    public static final ForgeConfigSpec.IntValue BATCH_TEMPLATE_DICTIONARY_MAX_PACKET_BYTES;
    public static final ForgeConfigSpec.IntValue BATCH_TEMPLATE_DICTIONARY_MAX_ENTRIES;
    public static final ForgeConfigSpec.IntValue BATCH_TEMPLATE_DICTIONARY_MAX_PAYLOAD_BYTES;
    public static final ForgeConfigSpec.IntValue BATCH_TEMPLATE_DICTIONARY_MAX_DIFF_RUNS;
    public static final ForgeConfigSpec.IntValue BATCH_TEMPLATE_DICTIONARY_MAX_CHANGED_BYTES;

    public static final ForgeConfigSpec.IntValue BATCH_SHA256_DICTIONARY_MAX_PACKET_BYTES;
    public static final ForgeConfigSpec.IntValue BATCH_SHA256_DICTIONARY_MAX_ENTRIES;
    public static final ForgeConfigSpec.IntValue BATCH_SHA256_DICTIONARY_MAX_PAYLOAD_BYTES;

    static final ForgeConfigSpec SPEC;

    public static boolean enableBatchReferenceDedup = true;
    public static boolean enableBatchSha256Dictionary = true;
    public static boolean enableBatchTemplateDictionary = true;
    public static boolean enableBatchZstd = false;
    public static boolean enableBatchStreamingZstd = false;
    public static boolean enableAsyncPlayBatchEncoding = true;
    public static int batchZstdLevel = 3;
    public static int batchStreamingZstdLevel = 4;
    public static int batchSha256DictionaryMaxPacketBytes = 3000;
    public static int batchSha256DictionaryMaxEntries = 8192;
    public static int batchSha256DictionaryMaxPayloadBytes = 1048576;
    public static int batchTemplateDictionaryMaxPacketBytes = 4096;
    public static int batchTemplateDictionaryMaxEntries = 8192;
    public static int batchTemplateDictionaryMaxPayloadBytes = 2097152;
    public static int batchTemplateDictionaryMaxDiffRuns = 8;
    public static int batchTemplateDictionaryMaxChangedBytes = 128;
    public static int batchMinPacketCount = 8;
    public static int batchMinRawBytes = 1024;
    public static boolean enableOptimizerStatsLogs = true;
    public static boolean enableTestMode = false;
    public static int statsLogIntervalMinutes = 30;

    static {
        BUILDER.comment("Logging Settings").push("logging");

        ENABLE_OPTIMIZER_STATS_LOGS = BUILDER
                .comment("")
                .comment("--------------------------------------------------------------------------")
                .comment("Enable periodic optimizer stats logs. When false, batch, bypass, optimized play, and chunk cache summaries are not printed.")
                .define("enable_optimizer_stats_logs", true);

        ENABLE_TEST_MODE = BUILDER
                .comment("")
                .comment("--------------------------------------------------------------------------")
                .comment("Use the shorter test-mode optimizer stats log interval.")
                .define("enable_test_mode", false);

        STATS_LOG_INTERVAL_MINUTES = BUILDER
                .comment("")
                .comment("--------------------------------------------------------------------------")
                .comment("Optimizer stats log interval in minutes while enable_test_mode is false.")
                .defineInRange("stats_log_interval_minutes", 30, 1, 1440);

        BUILDER.pop();

        BUILDER.comment("PLAY Batch Optimizer Settings").push("play-batch");

        ENABLE_BATCH_REFERENCE_DEDUP = BUILDER
                .comment("")
                .comment("--------------------------------------------------------------------------")
                .comment("Legacy compatibility switch for the reference-dedup test algorithm. The default PLAY optimizer does not use this directly.")
                .define("enable_batch_reference_dedup", true);

        ENABLE_BATCH_SHA256_DICTIONARY = BUILDER
                .comment("")
                .comment("--------------------------------------------------------------------------")
                .comment("Legacy compatibility switch for the SHA-256 dictionary test algorithm. The default PLAY optimizer uses template dictionary instead.")
                .define("enable_batch_sha256_dictionary", true);

        ENABLE_BATCH_TEMPLATE_DICTIONARY = BUILDER
                .comment("")
                .comment("--------------------------------------------------------------------------")
                .comment("Enable the template dictionary stage used by the default PLAY optimizer.")
                .define("enable_batch_template_dictionary", true);

        ENABLE_BATCH_ZSTD = BUILDER
                .comment("")
                .comment("--------------------------------------------------------------------------")
                .comment("Legacy compatibility switch for one-shot zstd test algorithms. The default PLAY optimizer uses streaming zstd instead.")
                .define("enable_batch_zstd", false);

        ENABLE_BATCH_STREAMING_ZSTD = BUILDER
                .comment("")
                .comment("--------------------------------------------------------------------------")
                .comment("Legacy compatibility switch for standalone streaming-zstd tests. The default PLAY optimizer already uses streaming zstd when zstd-jni is available.")
                .define("enable_batch_streaming_zstd", false);

        ENABLE_ASYNC_PLAY_BATCH_ENCODING = BUILDER
                .comment("")
                .comment("--------------------------------------------------------------------------")
                .comment("Move stateful PLAY batch dictionary/zstd encoding to a background worker. Packet snapshotting and final send still run on the server thread.")
                .define("enable_async_play_batch_encoding", true);

        BATCH_MIN_PACKET_COUNT = BUILDER
                .comment("")
                .comment("--------------------------------------------------------------------------")
                .comment("Bypass PLAY batching when the batch is too small to be worth wrapping. Single-packet batches are always bypassed.")
                .defineInRange("batch_min_packet_count", 8, 2, 64);

        BATCH_MIN_RAW_BYTES = BUILDER
                .comment("")
                .comment("--------------------------------------------------------------------------")
                .comment("Bypass PLAY batching when a small batch carries less than this many raw bytes.")
                .defineInRange("batch_min_raw_bytes", 1024, 1, Integer.MAX_VALUE);

        BUILDER.pop();

        BUILDER.comment("Streaming Zstd Settings").push("streaming-zstd");

        BATCH_ZSTD_LEVEL = BUILDER
                .comment("")
                .comment("--------------------------------------------------------------------------")
                .comment("Legacy one-shot zstd compression level. Kept for compatibility with deprecated test algorithms; not used by the default PLAY optimizer.")
                .defineInRange("batch_zstd_level", 3, 1, 22);

        BATCH_STREAMING_ZSTD_LEVEL = BUILDER
                .comment("")
                .comment("--------------------------------------------------------------------------")
                .comment("Compression level used by streaming zstd in the default template_dictionary_streaming_zstd PLAY optimizer.")
                .defineInRange("batch_streaming_zstd_level", 4, 1, 20);

        BUILDER.pop();

        BUILDER.comment("Template Dictionary Settings").push("template-dictionary");

        BATCH_TEMPLATE_DICTIONARY_MAX_PACKET_BYTES = BUILDER
                .comment("")
                .comment("--------------------------------------------------------------------------")
                .comment("Maximum payload size eligible for template mapping.")
                .defineInRange("batch_template_dictionary_max_packet_bytes", 4096, 1, Integer.MAX_VALUE);

        BATCH_TEMPLATE_DICTIONARY_MAX_ENTRIES = BUILDER
                .comment("")
                .comment("--------------------------------------------------------------------------")
                .comment("Maximum number of synchronized exact/template mappings kept by the template batch algorithm.")
                .defineInRange("batch_template_dictionary_max_entries", 8192, 1, Integer.MAX_VALUE);

        BATCH_TEMPLATE_DICTIONARY_MAX_PAYLOAD_BYTES = BUILDER
                .comment("")
                .comment("--------------------------------------------------------------------------")
                .comment("Maximum total literal/template bytes kept by the template batch algorithm.")
                .defineInRange("batch_template_dictionary_max_payload_bytes", 2097152, 1, Integer.MAX_VALUE);

        BATCH_TEMPLATE_DICTIONARY_MAX_DIFF_RUNS = BUILDER
                .comment("")
                .comment("--------------------------------------------------------------------------")
                .comment("Maximum number of changed byte runs allowed when creating a reusable template.")
                .defineInRange("batch_template_dictionary_max_diff_runs", 8, 1, 128);

        BATCH_TEMPLATE_DICTIONARY_MAX_CHANGED_BYTES = BUILDER
                .comment("")
                .comment("--------------------------------------------------------------------------")
                .comment("Maximum total changed bytes allowed when creating a reusable template.")
                .defineInRange("batch_template_dictionary_max_changed_bytes", 128, 1, Integer.MAX_VALUE);

        BUILDER.pop();

        BUILDER.comment("SHA-256 Dictionary Settings").push("sha256-dictionary");

        BATCH_SHA256_DICTIONARY_MAX_PACKET_BYTES = BUILDER
                .comment("")
                .comment("--------------------------------------------------------------------------")
                .comment("Maximum payload size eligible for SHA-256 dictionary mapping. Payloads at or above this size stay literal.")
                .defineInRange("batch_sha256_dictionary_max_packet_bytes", 3000, 1, Integer.MAX_VALUE);

        BATCH_SHA256_DICTIONARY_MAX_ENTRIES = BUILDER
                .comment("")
                .comment("--------------------------------------------------------------------------")
                .comment("Maximum number of active payload mappings kept by the SHA-256 dictionary batch algorithm.")
                .defineInRange("batch_sha256_dictionary_max_entries", 8192, 1, Integer.MAX_VALUE);

        BATCH_SHA256_DICTIONARY_MAX_PAYLOAD_BYTES = BUILDER
                .comment("")
                .comment("--------------------------------------------------------------------------")
                .comment("Maximum total payload bytes kept by the SHA-256 dictionary batch algorithm.")
                .defineInRange("batch_sha256_dictionary_max_payload_bytes", 1048576, 1, Integer.MAX_VALUE);

        BUILDER.pop();

        SPEC = BUILDER.build();
    }

    @SubscribeEvent
    static void onLoad(final ModConfigEvent event) {
        applyRuntimeConfig(currentLocalRuntimeConfig());
        syncRuntimeConfigToOnlinePlayers();
    }

    public static RuntimeConfig currentLocalRuntimeConfig() {
        return new RuntimeConfig(
                ENABLE_BATCH_REFERENCE_DEDUP.get(),
                ENABLE_BATCH_SHA256_DICTIONARY.get(),
                ENABLE_BATCH_TEMPLATE_DICTIONARY.get(),
                ENABLE_BATCH_ZSTD.get(),
                ENABLE_BATCH_STREAMING_ZSTD.get(),
                ENABLE_ASYNC_PLAY_BATCH_ENCODING.get(),
                BATCH_MIN_PACKET_COUNT.get(),
                BATCH_MIN_RAW_BYTES.get(),
                BATCH_ZSTD_LEVEL.get(),
                BATCH_STREAMING_ZSTD_LEVEL.get(),
                BATCH_SHA256_DICTIONARY_MAX_PACKET_BYTES.get(),
                BATCH_SHA256_DICTIONARY_MAX_ENTRIES.get(),
                BATCH_SHA256_DICTIONARY_MAX_PAYLOAD_BYTES.get(),
                BATCH_TEMPLATE_DICTIONARY_MAX_PACKET_BYTES.get(),
                BATCH_TEMPLATE_DICTIONARY_MAX_ENTRIES.get(),
                BATCH_TEMPLATE_DICTIONARY_MAX_PAYLOAD_BYTES.get(),
                BATCH_TEMPLATE_DICTIONARY_MAX_DIFF_RUNS.get(),
                BATCH_TEMPLATE_DICTIONARY_MAX_CHANGED_BYTES.get(),
                ENABLE_OPTIMIZER_STATS_LOGS.get(),
                ENABLE_TEST_MODE.get(),
                STATS_LOG_INTERVAL_MINUTES.get()
        );
    }

    public static void applyRuntimeConfig(RuntimeConfig runtimeConfig) {
        enableBatchReferenceDedup = runtimeConfig.enableBatchReferenceDedup();
        enableBatchSha256Dictionary = runtimeConfig.enableBatchSha256Dictionary();
        enableBatchTemplateDictionary = runtimeConfig.enableBatchTemplateDictionary();
        enableBatchZstd = runtimeConfig.enableBatchZstd();
        enableBatchStreamingZstd = runtimeConfig.enableBatchStreamingZstd();
        enableAsyncPlayBatchEncoding = runtimeConfig.enableAsyncPlayBatchEncoding();
        batchMinPacketCount = runtimeConfig.batchMinPacketCount();
        batchMinRawBytes = runtimeConfig.batchMinRawBytes();
        batchZstdLevel = runtimeConfig.batchZstdLevel();
        batchStreamingZstdLevel = runtimeConfig.batchStreamingZstdLevel();
        batchSha256DictionaryMaxPacketBytes = runtimeConfig.batchSha256DictionaryMaxPacketBytes();
        batchSha256DictionaryMaxEntries = runtimeConfig.batchSha256DictionaryMaxEntries();
        batchSha256DictionaryMaxPayloadBytes = runtimeConfig.batchSha256DictionaryMaxPayloadBytes();
        batchTemplateDictionaryMaxPacketBytes = runtimeConfig.batchTemplateDictionaryMaxPacketBytes();
        batchTemplateDictionaryMaxEntries = runtimeConfig.batchTemplateDictionaryMaxEntries();
        batchTemplateDictionaryMaxPayloadBytes = runtimeConfig.batchTemplateDictionaryMaxPayloadBytes();
        batchTemplateDictionaryMaxDiffRuns = runtimeConfig.batchTemplateDictionaryMaxDiffRuns();
        batchTemplateDictionaryMaxChangedBytes = runtimeConfig.batchTemplateDictionaryMaxChangedBytes();
        enableOptimizerStatsLogs = runtimeConfig.enableOptimizerStatsLogs();
        enableTestMode = runtimeConfig.enableTestMode();
        statsLogIntervalMinutes = runtimeConfig.statsLogIntervalMinutes();
    }

    public static long optimizerStatsLogIntervalMillis() {
        int minutes = enableTestMode ? TEST_MODE_STATS_LOG_INTERVAL_MINUTES : statsLogIntervalMinutes;
        return Math.max(minutes, 1) * 60_000L;
    }

    public static boolean optimizerDebugLoggingEnabled() {
        return enableOptimizerStatsLogs && enableTestMode;
    }

    private static void syncRuntimeConfigToOnlinePlayers() {
        net.minecraft.server.MinecraftServer server = ServerLifecycleHooks.getCurrentServer();
        if (server == null) {
            return;
        }
        server.getPlayerList().getPlayers().forEach(ModNetwork::sendServerConfigToPlayer);
    }

    public record RuntimeConfig(
            boolean enableBatchReferenceDedup,
            boolean enableBatchSha256Dictionary,
            boolean enableBatchTemplateDictionary,
            boolean enableBatchZstd,
            boolean enableBatchStreamingZstd,
            boolean enableAsyncPlayBatchEncoding,
            int batchMinPacketCount,
            int batchMinRawBytes,
            int batchZstdLevel,
            int batchStreamingZstdLevel,
            int batchSha256DictionaryMaxPacketBytes,
            int batchSha256DictionaryMaxEntries,
            int batchSha256DictionaryMaxPayloadBytes,
            int batchTemplateDictionaryMaxPacketBytes,
            int batchTemplateDictionaryMaxEntries,
            int batchTemplateDictionaryMaxPayloadBytes,
            int batchTemplateDictionaryMaxDiffRuns,
            int batchTemplateDictionaryMaxChangedBytes,
            boolean enableOptimizerStatsLogs,
            boolean enableTestMode,
            int statsLogIntervalMinutes
    ) {
    }
}
