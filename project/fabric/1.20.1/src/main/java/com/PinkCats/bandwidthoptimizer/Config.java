package com.PinkCats.bandwidthoptimizer;


public class Config {
    private static final ForgeConfigSpec.Builder BUILDER = new ForgeConfigSpec.Builder();

    public static final ForgeConfigSpec.BooleanValue ENABLE_OPTIMIZER_STATS_LOGS;
    public static final ForgeConfigSpec.BooleanValue ENABLE_TEST_MODE;
    public static final ForgeConfigSpec.IntValue STATS_LOG_INTERVAL_MINUTES;
    public static final ForgeConfigSpec.BooleanValue DEBUG_ANALYSIS;
    private static final int TEST_MODE_STATS_LOG_INTERVAL_MINUTES = 3;

    public static final ForgeConfigSpec.BooleanValue ENABLE_BATCH_REFERENCE_DEDUP;
    public static final ForgeConfigSpec.BooleanValue ENABLE_BATCH_SHA256_DICTIONARY;
    public static final ForgeConfigSpec.BooleanValue ENABLE_BATCH_TEMPLATE_DICTIONARY;
    public static final ForgeConfigSpec.BooleanValue ENABLE_BATCH_ZSTD;
    public static final ForgeConfigSpec.BooleanValue ENABLE_BATCH_STREAMING_ZSTD;
    public static final ForgeConfigSpec.BooleanValue ENABLE_ASYNC_PLAY_BATCH_ENCODING;

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
    public static boolean enableOptimizerStatsLogs = true;
    public static boolean enableTestMode = false;
    public static int statsLogIntervalMinutes = 30;
    public static boolean debugAnalysis = false;

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

        BUILDER.comment("Debug Settings").push("debug");

        DEBUG_ANALYSIS = BUILDER
                .comment("")
                .comment("--------------------------------------------------------------------------")
                .comment("Enable lightweight analysis outputs, such as packet rank, transport report, bypass rank, and telemetry dumps.")
                .define("debug_analysis", false);

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

    static void onLoad(final ModConfigEvent event) {
        if (event.getConfig().getSpec() != SPEC) {
            return;
        }
        applyRuntimeConfig(currentLocalRuntimeConfig());
    }

    public static RuntimeConfig currentLocalRuntimeConfig() {
        return new RuntimeConfig(
                ENABLE_BATCH_REFERENCE_DEDUP.get(),
                ENABLE_BATCH_SHA256_DICTIONARY.get(),
                ENABLE_BATCH_TEMPLATE_DICTIONARY.get(),
                ENABLE_BATCH_ZSTD.get(),
                ENABLE_BATCH_STREAMING_ZSTD.get(),
                ENABLE_ASYNC_PLAY_BATCH_ENCODING.get(),
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
                STATS_LOG_INTERVAL_MINUTES.get(),
                DEBUG_ANALYSIS.get()
        );
    }

    public static void applyRuntimeConfig(RuntimeConfig runtimeConfig) {
        enableBatchReferenceDedup = runtimeConfig.enableBatchReferenceDedup();
        enableBatchSha256Dictionary = runtimeConfig.enableBatchSha256Dictionary();
        enableBatchTemplateDictionary = runtimeConfig.enableBatchTemplateDictionary();
        enableBatchZstd = runtimeConfig.enableBatchZstd();
        enableBatchStreamingZstd = runtimeConfig.enableBatchStreamingZstd();
        enableAsyncPlayBatchEncoding = runtimeConfig.enableAsyncPlayBatchEncoding();
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
        debugAnalysis = runtimeConfig.debugAnalysis();
    }

    public static long optimizerStatsLogIntervalMillis() {
        int minutes = enableTestMode ? TEST_MODE_STATS_LOG_INTERVAL_MINUTES : statsLogIntervalMinutes;
        return Math.max(minutes, 1) * 60_000L;
    }

    public static boolean optimizerDebugLoggingEnabled() {
        return enableOptimizerStatsLogs && enableTestMode;
    }

    public static final class RuntimeProperty {

        private RuntimeProperty() {
        }

        public static final class Debug {

            public static final String ANALYSIS_ENABLED = "bandwidthoptimizer.debug.analysis";
            public static final boolean DEFAULT_ANALYSIS_ENABLED = false;

            private Debug() {
            }
        }

        public static final class Transport {

            public static final String ENABLED = "bandwidthoptimizer.transport.enabled";
            public static final boolean DEFAULT_ENABLED = true;
            public static final String CHUNK_HOTSPOT_TRANSPORT_ENABLED =
                    "bandwidthoptimizer.experimentalChunkHotspotTransport";
            public static final boolean DEFAULT_CHUNK_HOTSPOT_TRANSPORT_ENABLED = true;
            public static final String MAPPING_ENABLED = "bandwidthoptimizer.transport.mappingEnabled";
            public static final String ZSTD_ENABLED = "bandwidthoptimizer.transport.zstdEnabled";
            public static final boolean DEFAULT_ZSTD_ENABLED = true;
            public static final String PACKET_ID_MAPPING_ENABLED =
                    "bandwidthoptimizer.transport.packetIdMappingEnabled";
            public static final boolean DEFAULT_PACKET_ID_MAPPING_ENABLED = true;
            public static final String BATCH_ENABLED = "bandwidthoptimizer.transport.batchEnabled";
            public static final boolean DEFAULT_BATCH_ENABLED = true;
            public static final String BATCH_WINDOW_MILLIS = "bandwidthoptimizer.transport.batchWindowMillis";
            public static final long DEFAULT_BATCH_WINDOW_MILLIS = 20L;
            public static final long DEFAULT_BATCH_WARMUP_MILLIS = 5_000L;
            public static final String TELEMETRY_DUMP_FILE_NAME =
                    "bandwidthoptimizer.transport.telemetryDumpFileName";
            public static final String DECODER_EXCEPTION_DUMP_ENABLED =
                    "bandwidthoptimizer.decoderExceptionDumpEnabled";
            public static final boolean DEFAULT_DECODER_EXCEPTION_DUMP_ENABLED = true;
            public static final String DECODER_EXCEPTION_DUMP_MAX_BYTES =
                    "bandwidthoptimizer.decoderExceptionDumpMaxBytes";
            public static final int DEFAULT_DECODER_EXCEPTION_DUMP_MAX_BYTES = 8 * 1024 * 1024;
            public static final String PROXY_SAFE_CONTROL_ENABLED =
                    "bandwidthoptimizer.transport.proxySafeControlEnabled";
            public static final boolean DEFAULT_PROXY_SAFE_CONTROL_ENABLED = true;
            public static final String SERVERBOUND_TRANSPARENT_ENABLED =
                    "bandwidthoptimizer.transport.serverboundTransparentEnabled";
            public static final boolean DEFAULT_SERVERBOUND_TRANSPARENT_ENABLED = false;
            public static final String DEBUG_TRACE_SAMPLE_LIMIT =
                    "bandwidthoptimizer.transport.debugTraceSampleLimit";
            public static final int DEFAULT_DEBUG_TRACE_SAMPLE_LIMIT = 256;
            public static final String DEBUG_TRACE_PREFIX_BYTES =
                    "bandwidthoptimizer.transport.debugTracePrefixBytes";
            public static final int DEFAULT_DEBUG_TRACE_PREFIX_BYTES = 32;
            public static final String DEBUG_TRACE_CLOSE_DUMP_SIZE =
                    "bandwidthoptimizer.transport.debugTraceCloseDumpSize";
            public static final int DEFAULT_DEBUG_TRACE_CLOSE_DUMP_SIZE = 96;
            public static final String BYPASS_RANK_LOG_ENABLED =
                    "bandwidthoptimizer.transport.bypassRankLogEnabled";
            public static final boolean DEFAULT_BYPASS_RANK_LOG_ENABLED = false;
            public static final String BYPASS_RANK_LOG_INTERVAL_MILLIS =
                    "bandwidthoptimizer.transport.bypassRankLogIntervalMillis";
            public static final long DEFAULT_BYPASS_RANK_LOG_INTERVAL_MILLIS = 10_000L;
            public static final String BYPASS_RANK_LOG_TOP_N =
                    "bandwidthoptimizer.transport.bypassRankLogTopN";
            public static final int DEFAULT_BYPASS_RANK_LOG_TOP_N = 20;
            public static final String BYPASS_RANK_REPORT_ENABLED =
                    "bandwidthoptimizer.transport.bypassRankReportEnabled";
            public static final boolean DEFAULT_BYPASS_RANK_REPORT_ENABLED = false;
            public static final String BYPASS_RANK_REPORT_DIRECTORY =
                    "bandwidthoptimizer.transport.bypassRankReportDirectory";
            public static final String DEFAULT_BYPASS_RANK_REPORT_DIRECTORY = "transport-bypass-report";

            private Transport() {
            }
        }

        public static final class Client {

            public static final String CHUNK_CACHE_MAX_MEMORY_MB =
                    "bandwidthoptimizer.clientChunkCacheMaxMemoryMb";
            public static final int DEFAULT_CHUNK_CACHE_MAX_MEMORY_MB = 110;
            public static final String CHUNK_CACHE_RECYCLE_TRIGGER_FREE_MB =
                    "bandwidthoptimizer.clientChunkCacheRecycleTriggerFreeMb";
            public static final int DEFAULT_CHUNK_CACHE_RECYCLE_TRIGGER_FREE_MB = 10;

            private Client() {
            }
        }

        public static final class Chunk {

            public static final String BOUNDARY_BARRIER_ENABLED =
                    "bandwidthoptimizer.chunk.boundaryBarrierEnabled";
            public static final boolean DEFAULT_BOUNDARY_BARRIER_ENABLED = true;
            public static final String WATCH_BOUNDARY_REUSE_MAX_DELTA_PACKETS =
                    "bandwidthoptimizer.chunk.watchBoundaryReuseMaxDeltaPackets";
            public static final long DEFAULT_WATCH_BOUNDARY_REUSE_MAX_DELTA_PACKETS = 64L;
            public static final String WATCH_BOUNDARY_REUSE_MAX_DELTA_BYTES_RATIO =
                    "bandwidthoptimizer.chunk.watchBoundaryReuseMaxDeltaBytesRatio";
            public static final double DEFAULT_WATCH_BOUNDARY_REUSE_MAX_DELTA_BYTES_RATIO = 0.5D;
            public static final String GLOBAL_STORE_BUDGET_BYTES =
                    "bandwidthoptimizer.chunkGlobalStoreBudgetBytes";
            public static final long DEFAULT_GLOBAL_STORE_BUDGET_BYTES = 32L * 1024L * 1024L;
            public static final String GLOBAL_STORE_MAX_VERSIONS_PER_CHUNK =
                    "bandwidthoptimizer.chunkGlobalStoreMaxVersionsPerChunk";
            public static final int DEFAULT_GLOBAL_STORE_MAX_VERSIONS_PER_CHUNK = 8;
            public static final String SERVER_SHADOW_ORIGINAL_BYTES_BUDGET_BYTES =
                    "bandwidthoptimizer.chunk.serverShadowOriginalBytesBudgetBytes";
            public static final long DEFAULT_SERVER_SHADOW_ORIGINAL_BYTES_BUDGET_BYTES = 256L * 1024L * 1024L;
            public static final String SERVER_SHADOW_METADATA_ENTRY_LIMIT =
                    "bandwidthoptimizer.chunk.serverShadowMetadataEntryLimit";
            public static final long DEFAULT_SERVER_SHADOW_METADATA_ENTRY_LIMIT = 1_000_000L;

            private Chunk() {
            }
        }

        public static final class Create {

            public static final String CREATE_BLOCK_ENTITY_UPDATE_GATE_ENABLED =
                    "bandwidthoptimizer.create.blockEntityUpdateGateEnabled";
            public static final boolean DEFAULT_CREATE_BLOCK_ENTITY_UPDATE_GATE_ENABLED = true;
            public static final String CREATE_BLOCK_ENTITY_UPDATE_GATE_MAX_DELAY_MILLIS =
                    "bandwidthoptimizer.create.blockEntityUpdateGateMaxDelayMillis";
            public static final long DEFAULT_CREATE_BLOCK_ENTITY_UPDATE_GATE_MAX_DELAY_MILLIS = 5000L;
            public static final String CREATE_BLOCK_ENTITY_UPDATE_GATE_LOOK_DOT =
                    "bandwidthoptimizer.create.blockEntityUpdateGateLookDot";
            public static final double DEFAULT_CREATE_BLOCK_ENTITY_UPDATE_GATE_LOOK_DOT = 0.35D;
            public static final String CREATE_BLOCK_ENTITY_UPDATE_GATE_ALWAYS_SEND_DISTANCE_BLOCKS =
                    "bandwidthoptimizer.create.blockEntityUpdateGateAlwaysSendDistanceBlocks";
            public static final double DEFAULT_CREATE_BLOCK_ENTITY_UPDATE_GATE_ALWAYS_SEND_DISTANCE_BLOCKS = 8.0D;
            public static final String CREATE_BLOCK_ENTITY_UPDATE_GATE_MAX_PENDING_PER_PLAYER =
                    "bandwidthoptimizer.create.blockEntityUpdateGateMaxPendingPerPlayer";
            public static final int DEFAULT_CREATE_BLOCK_ENTITY_UPDATE_GATE_MAX_PENDING_PER_PLAYER = 8192;
            public static final String CREATE_BLOCK_ENTITY_UPDATE_GATE_CHUNK_BOOTSTRAP_MILLIS =
                    "bandwidthoptimizer.create.blockEntityUpdateGateChunkBootstrapMillis";
            public static final long DEFAULT_CREATE_BLOCK_ENTITY_UPDATE_GATE_CHUNK_BOOTSTRAP_MILLIS = 8000L;

            private Create() {
            }
        }

        public static final class Experient {

            public static final String ENABLED = "bandwidthoptimizer.experient.enabled";
            public static final boolean DEFAULT_ENABLED = false;
            public static final String AUTO_CONNECT_ADDRESS =
                    "bandwidthoptimizer.experient.autoConnectAddress";
            public static final String AUTO_CONNECT_NAME =
                    "bandwidthoptimizer.experient.autoConnectName";
            public static final String DEFAULT_AUTO_CONNECT_NAME = "BandwidthOptimizer Experient";
            public static final String AUTO_CONNECT_DELAY_TICKS =
                    "bandwidthoptimizer.experient.autoConnectDelayTicks";
            public static final int DEFAULT_AUTO_CONNECT_DELAY_TICKS = 20;
            public static final String AUTO_CONNECT_MAX_ATTEMPTS =
                    "bandwidthoptimizer.experient.autoConnectMaxAttempts";
            public static final int DEFAULT_AUTO_CONNECT_MAX_ATTEMPTS = 4;
            public static final String AUTO_CONNECT_RETRY_DELAY_TICKS =
                    "bandwidthoptimizer.experient.autoConnectRetryDelayTicks";
            public static final int DEFAULT_AUTO_CONNECT_RETRY_DELAY_TICKS = 40;
            public static final String AUTO_CONNECT_REJOIN_CYCLES =
                    "bandwidthoptimizer.experient.autoConnectRejoinCycles";
            public static final int DEFAULT_AUTO_CONNECT_REJOIN_CYCLES = 0;
            public static final String AUTO_CONNECT_REJOIN_CONNECTED_TICKS =
                    "bandwidthoptimizer.experient.autoConnectRejoinConnectedTicks";
            public static final int DEFAULT_AUTO_CONNECT_REJOIN_CONNECTED_TICKS = 120;
            public static final String AUTO_CONNECT_REJOIN_DELAY_TICKS =
                    "bandwidthoptimizer.experient.autoConnectRejoinDelayTicks";
            public static final int DEFAULT_AUTO_CONNECT_REJOIN_DELAY_TICKS = 80;
            public static final String SERVER_COMMAND =
                    "bandwidthoptimizer.experient.serverCommand";
            public static final String DEFAULT_SERVER_COMMAND = "";
            public static final String SERVER_COMMAND_DELAY_TICKS =
                    "bandwidthoptimizer.experient.serverCommandDelayTicks";
            public static final int DEFAULT_SERVER_COMMAND_DELAY_TICKS = 0;
            public static final String RUN_ALL_MARKER_EXIT =
                    "bandwidthoptimizer.experient.runAllMarkerExit";
            public static final String SINGLEPLAYER_CARRIER_REGRESSION =
                    "bandwidthoptimizer.experient.singleplayerCarrierRegression";
            public static final String SINGLEPLAYER_CARRIER_MARKER =
                    "bandwidthoptimizer.experient.singleplayerCarrierMarker";
            public static final String CHUNK_HOTSPOT_PATH_ENABLED =
                    "bandwidthoptimizer.experient.chunkHotspotPathEnabled";
            public static final String CHUNK_HOTSPOT_PATH_INITIAL_DELAY_TICKS =
                    "bandwidthoptimizer.experient.chunkHotspotPathInitialDelayTicks";
            public static final int DEFAULT_CHUNK_HOTSPOT_PATH_INITIAL_DELAY_TICKS = 80;
            public static final String CHUNK_HOTSPOT_PATH_CLIENT_COMMAND_MODE =
                    "bandwidthoptimizer.experient.chunkHotspotPathClientCommandMode";
            public static final String CHUNK_HOTSPOT_PATH_BLOCK_ENTITY_FIRST_MODE =
                    "bandwidthoptimizer.experient.chunkHotspotPathBlockEntityFirstMode";
            public static final String CHUNK_HOTSPOT_PATH_WAIT_FOR_CLIENT_COMMAND_TARGET =
                    "bandwidthoptimizer.experient.chunkHotspotPathWaitForClientCommandTarget";
            public static final String CHUNK_HOTSPOT_PATH_STOP_AFTER_SECTION =
                    "bandwidthoptimizer.experient.chunkHotspotPathStopAfterSection";
            public static final String CHUNK_HOTSPOT_PATH_STOP_AFTER_BLOCK_ENTITY =
                    "bandwidthoptimizer.experient.chunkHotspotPathStopAfterBlockEntity";
            public static final String CHUNK_HOTSPOT_PATH_TWO_POINT_REUSE_MODE =
                    "bandwidthoptimizer.experient.chunkHotspotPathTwoPointReuseMode";
            public static final String CHUNK_HOTSPOT_PATH_BOUNDARY_HOP_MODE =
                    "bandwidthoptimizer.experient.chunkHotspotPathBoundaryHopMode";
            public static final String CHUNK_HOTSPOT_PATH_DIMENSION_HOP_MODE =
                    "bandwidthoptimizer.experient.chunkHotspotPathDimensionHopMode";
            public static final String CHUNK_HOTSPOT_PATH_RANGE_BOUNCE_MODE =
                    "bandwidthoptimizer.experient.chunkHotspotPathRangeBounceMode";
            public static final String CHUNK_HOTSPOT_PATH_RANGE_START_X =
                    "bandwidthoptimizer.experient.chunkHotspotPathRangeStartX";
            public static final double DEFAULT_CHUNK_HOTSPOT_PATH_RANGE_START_X = 28.0D;
            public static final String CHUNK_HOTSPOT_PATH_RANGE_START_Y =
                    "bandwidthoptimizer.experient.chunkHotspotPathRangeStartY";
            public static final double DEFAULT_CHUNK_HOTSPOT_PATH_RANGE_START_Y = 182.0D;
            public static final String CHUNK_HOTSPOT_PATH_RANGE_START_Z =
                    "bandwidthoptimizer.experient.chunkHotspotPathRangeStartZ";
            public static final double DEFAULT_CHUNK_HOTSPOT_PATH_RANGE_START_Z = -364.0D;
            public static final String CHUNK_HOTSPOT_PATH_RANGE_END_X =
                    "bandwidthoptimizer.experient.chunkHotspotPathRangeEndX";
            public static final double DEFAULT_CHUNK_HOTSPOT_PATH_RANGE_END_X = 26.0D;
            public static final String CHUNK_HOTSPOT_PATH_RANGE_END_Y =
                    "bandwidthoptimizer.experient.chunkHotspotPathRangeEndY";
            public static final double DEFAULT_CHUNK_HOTSPOT_PATH_RANGE_END_Y = 200.0D;
            public static final String CHUNK_HOTSPOT_PATH_RANGE_END_Z =
                    "bandwidthoptimizer.experient.chunkHotspotPathRangeEndZ";
            public static final double DEFAULT_CHUNK_HOTSPOT_PATH_RANGE_END_Z = -281.0D;
            public static final String CHUNK_HOTSPOT_PATH_RANGE_STEP_BLOCKS =
                    "bandwidthoptimizer.experient.chunkHotspotPathRangeStepBlocks";
            public static final double DEFAULT_CHUNK_HOTSPOT_PATH_RANGE_STEP_BLOCKS = 16.0D;
            public static final String CHUNK_HOTSPOT_PATH_RANGE_ROUND_TRIPS =
                    "bandwidthoptimizer.experient.chunkHotspotPathRangeRoundTrips";
            public static final int DEFAULT_CHUNK_HOTSPOT_PATH_RANGE_ROUND_TRIPS = 3;
            public static final String WATCH_BOUNDARY_REFRESH_PATCH_ENABLED =
                    "bandwidthoptimizer.experient.watchBoundaryRefreshPatchEnabled";

            private Experient() {
            }
        }
    }

    public record RuntimeConfig(
            boolean enableBatchReferenceDedup,
            boolean enableBatchSha256Dictionary,
            boolean enableBatchTemplateDictionary,
            boolean enableBatchZstd,
            boolean enableBatchStreamingZstd,
            boolean enableAsyncPlayBatchEncoding,
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
            int statsLogIntervalMinutes,
            boolean debugAnalysis
    ) {
    }

    private static final class ForgeConfigSpec {
        private static final class Builder {
            Builder comment(String value) { return this; }
            Builder push(String value) { return this; }
            Builder pop() { return this; }
            BooleanValue define(String name, boolean defaultValue) { return new BooleanValue(defaultValue); }
            IntValue defineInRange(String name, int defaultValue, int minimum, int maximum) { return new IntValue(defaultValue); }
            ForgeConfigSpec build() { return new ForgeConfigSpec(); }
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

        private static final class IntValue {
            private final int value;

            private IntValue(int value) {
                this.value = value;
            }

            int get() {
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


