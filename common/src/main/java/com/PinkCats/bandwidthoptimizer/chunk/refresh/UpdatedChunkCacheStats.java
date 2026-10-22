package com.PinkCats.bandwidthoptimizer.chunk.refresh;

import com.PinkCats.bandwidthoptimizer.chunk.classify.packet.ChunkPacketCoordinate;
import java.util.LinkedHashMap;
import java.util.concurrent.atomic.AtomicLong;

/** Bounded provenance, not a cache or a replacement for the persistent hash index. */
public final class UpdatedChunkCacheStats {
    public static final String STORE_REASON = "loaded_chunk_updated_cache";
    public static final boolean ENABLED = Boolean.getBoolean("bandwidthoptimizer.experimental.loadedChunkRefresh");
    private static final int MAX_ENTRIES = 4096;
    private static final LinkedHashMap<Long, String> CANDIDATES = new LinkedHashMap<>();
    private static String scope = "";
    private static final AtomicLong REUSES = new AtomicLong();
    private static final AtomicLong CANDIDATE_STORES = new AtomicLong();
    private static final AtomicLong CAPTURE_FAILURES = new AtomicLong();
    private static final AtomicLong BLOCK_ENTITY_SKIPS = new AtomicLong();
    private UpdatedChunkCacheStats() {}

    public static synchronized void candidate(String token, ChunkPacketCoordinate coordinate, String hash) {
        if (token == null || token.isEmpty() || coordinate == null || !coordinate.present() || hash == null || hash.length() != 64) return;
        if (!scope.equals(token)) { CANDIDATES.clear(); scope = token; }
        long key = LoadedChunkUpdateTracker.key(coordinate.chunkX(), coordinate.chunkZ());
        CANDIDATES.remove(key);
        CANDIDATES.put(key, hash);
        if (CANDIDATES.size() > MAX_ENTRIES) CANDIDATES.remove(CANDIDATES.keySet().iterator().next());
        CANDIDATE_STORES.incrementAndGet();
    }
    public static synchronized void referenceRestored(String token, ChunkPacketCoordinate coordinate, String hash) {
        if (!scope.equals(token) || coordinate == null || !coordinate.present()) return;
        if (hash != null && hash.equals(CANDIDATES.get(LoadedChunkUpdateTracker.key(coordinate.chunkX(), coordinate.chunkZ())))) REUSES.incrementAndGet();
    }
    public static void captureFailed() { CAPTURE_FAILURES.incrementAndGet(); }
    public static void skipBlockEntities() { BLOCK_ENTITY_SKIPS.incrementAndGet(); }
    public static long blockEntitySkips() { return BLOCK_ENTITY_SKIPS.get(); }
    public static long reuses() { return REUSES.get(); }
    public static long candidateStores() { return CANDIDATE_STORES.get(); }
    public static long captureFailures() { return CAPTURE_FAILURES.get(); }
    public static synchronized int entries() { return CANDIDATES.size(); }
    public static synchronized void reset() {
        CANDIDATES.clear(); scope = ""; REUSES.set(0); CANDIDATE_STORES.set(0); CAPTURE_FAILURES.set(0); BLOCK_ENTITY_SKIPS.set(0);
    }
}
