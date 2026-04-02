package com.PinkCats.bandwidthoptimizer.network.algorithm;

import com.PinkCats.bandwidthoptimizer.Bandwidthoptimizer;
import com.PinkCats.bandwidthoptimizer.network.algorithm.legacy.dedup.ReferenceDedupBatchAlgorithm;
import com.PinkCats.bandwidthoptimizer.network.algorithm.legacy.dictionary.Sha256DictionaryBatchAlgorithm;
import com.PinkCats.bandwidthoptimizer.network.algorithm.template.BlockEntityCanonicalTemplateBatchAlgorithm;
import com.PinkCats.bandwidthoptimizer.network.algorithm.template.TemplateDictionaryBatchAlgorithm;
import com.PinkCats.bandwidthoptimizer.network.algorithm.template.TemplateDictionaryBatchAlgorithmModule;
import com.PinkCats.bandwidthoptimizer.network.algorithm.zstd.PostStreamingZstdBatchAlgorithm;
import com.PinkCats.bandwidthoptimizer.network.algorithm.zstd.PostZstdBatchAlgorithm;

import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;

public final class BatchAlgorithmRegistry {

    private static final BatchAlgorithm PASSTHROUGH = new PassthroughBatchAlgorithm();
    private static final String DEFAULT_ALGORITHM_ID = "template_dictionary_streaming_zstd";
    private static final String SAFE_FALLBACK_ALGORITHM_ID = "template_dictionary";
    private static final List<BatchAlgorithm> EXTRA_ALGORITHMS = List.of(
            // Deprecated combinations kept for compatibility and testing only.
            new PostZstdBatchAlgorithm("reference_dedup_zstd", new ReferenceDedupBatchAlgorithm()),
            new PostZstdBatchAlgorithm("sha256_dictionary_zstd", new Sha256DictionaryBatchAlgorithm()),
            new PostZstdBatchAlgorithm("template_dictionary_zstd", new TemplateDictionaryBatchAlgorithm()),
            new BlockEntityCanonicalTemplateBatchAlgorithm(),
            new PostStreamingZstdBatchAlgorithm("reference_dedup_streaming_zstd", new ReferenceDedupBatchAlgorithm()),
            new PostStreamingZstdBatchAlgorithm("sha256_dictionary_streaming_zstd", new Sha256DictionaryBatchAlgorithm()),
            new PostStreamingZstdBatchAlgorithm("template_dictionary_streaming_zstd", new TemplateDictionaryBatchAlgorithm()),
            new PostStreamingZstdBatchAlgorithm("block_entity_template_streaming_zstd", new BlockEntityCanonicalTemplateBatchAlgorithm())
    );
    private static final List<BatchAlgorithmModule> MODULES = List.of(
            // Recommended standalone algorithm.
            new TemplateDictionaryBatchAlgorithmModule()
    );
    private static final Map<String, BatchAlgorithm> BY_ID = buildById();
    private static volatile String overrideAlgorithmId;
    private static volatile boolean warnedMissingZstd;
    private static volatile boolean forceSafeFallback;

    private BatchAlgorithmRegistry() {
    }

    public static BatchAlgorithm configured() {
        if (forceSafeFallback) {
            return byId(SAFE_FALLBACK_ALGORITHM_ID);
        }
        String overrideId = overrideAlgorithmId;
        if (overrideId != null && !overrideId.isBlank()) {
            return resolveWithAvailabilityFallback(overrideId);
        }
        return resolveWithAvailabilityFallback(DEFAULT_ALGORITHM_ID);
    }

    public static void setOverride(String algorithmId) {
        if (algorithmId == null || algorithmId.isBlank()) {
            overrideAlgorithmId = null;
            return;
        }
        resolveWithAvailabilityFallback(algorithmId);
        overrideAlgorithmId = algorithmId;
    }

    public static void clearOverride() {
        overrideAlgorithmId = null;
    }

    public static BatchAlgorithm byId(String id) {
        BatchAlgorithm algorithm = BY_ID.get(id);
        if (algorithm == null) {
            throw new IllegalArgumentException("Unknown batch algorithm id: " + id);
        }
        return algorithm;
    }

    public static BatchAlgorithm fallbackAfterFailure(String requestedId, Throwable error) {
        if (requiresZstd(requestedId)) {
            forceSafeFallback = true;
            Bandwidthoptimizer.LOGGER.warn(
                    "Batch algorithm {} failed at runtime, disabling zstd path and falling back to {}.",
                    requestedId,
                    SAFE_FALLBACK_ALGORITHM_ID,
                    error
            );
            return byId(SAFE_FALLBACK_ALGORITHM_ID);
        }
        if (error instanceof RuntimeException runtimeException) {
            throw runtimeException;
        }
        throw new IllegalStateException("Batch algorithm failed: " + requestedId, error);
    }

    private static BatchAlgorithm resolveWithAvailabilityFallback(String id) {
        if (!requiresZstd(id) || isZstdAvailable()) {
            return byId(id);
        }
        warnMissingZstd(id);
        return byId(SAFE_FALLBACK_ALGORITHM_ID);
    }

    private static boolean requiresZstd(String id) {
        return id != null && id.contains("zstd");
    }

    private static boolean isZstdAvailable() {
        try {
            Class.forName("com.github.luben.zstd.ZstdCompressCtx", false, BatchAlgorithmRegistry.class.getClassLoader());
            return true;
        } catch (ClassNotFoundException exception) {
            return false;
        } catch (LinkageError error) {
            Bandwidthoptimizer.LOGGER.warn("Zstd runtime linkage failed, fallback to {}", SAFE_FALLBACK_ALGORITHM_ID, error);
            return false;
        }
    }

    private static void warnMissingZstd(String requestedId) {
        if (warnedMissingZstd) {
            return;
        }
        warnedMissingZstd = true;
        Bandwidthoptimizer.LOGGER.warn(
                "Requested batch algorithm {} requires zstd-jni, but runtime classes are unavailable. Falling back to {}.",
                requestedId,
                SAFE_FALLBACK_ALGORITHM_ID
        );
    }

    private static Map<String, BatchAlgorithm> buildById() {
        return java.util.stream.Stream.of(
                        java.util.stream.Stream.of(PASSTHROUGH),
                        EXTRA_ALGORITHMS.stream(),
                        MODULES.stream().map(BatchAlgorithmModule::algorithm)
                )
                .flatMap(Function.identity())
                .collect(Collectors.toUnmodifiableMap(BatchAlgorithm::id, Function.identity()));
    }
}
