package com.PinkCats.bandwidthoptimizer.chunk.persistent;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Properties;
import java.util.Set;
import java.util.function.ToLongFunction;

final class PersistentChunkCacheRetentionPolicy {

    private static final long DAY_MILLIS = 24L * 60L * 60L * 1000L;

    private PersistentChunkCacheRetentionPolicy() {}

    static RetentionPlan plan(
            Properties index,
            ToLongFunction<String> storedBytes,
            RetentionLimits limits
    ) {
        ArrayList<CacheEntry> entries = readEntries(index, storedBytes);
        if (entries.isEmpty()) {
            return RetentionPlan.empty();
        }

        entries.sort(Comparator.comparingDouble(CacheEntry::score).reversed());
        LinkedHashMap<String, Integer> temperatures = new LinkedHashMap<>();
        for (int position = 0; position < entries.size(); position++) {
            temperatures.put(entries.get(position).keyPrefix(), Math.min(9, (position * 10) / entries.size()));
        }

        HashMap<String, Integer> scopeCounts = new HashMap<>();
        HashMap<String, Integer> hashReferences = new HashMap<>();
        HashMap<String, Long> hashBytes = new HashMap<>();
        for (CacheEntry entry : entries) {
            scopeCounts.merge(entry.scopeHash(), 1, Integer::sum);
            hashReferences.merge(entry.hash(), 1, Integer::sum);
            hashBytes.putIfAbsent(entry.hash(), entry.storedBytes());
        }
        long retainedBytes = hashBytes.values().stream().mapToLong(Long::longValue).sum();
        int retainedEntries = entries.size();
        if (retainedEntries <= limits.maxEntries() && retainedBytes <= limits.maxBytes()) {
            return new RetentionPlan(List.of(), Map.copyOf(temperatures), Set.copyOf(hashReferences.keySet()), retainedBytes);
        }

        entries.sort(Comparator
                .comparingInt((CacheEntry entry) -> temperatures.getOrDefault(entry.keyPrefix(), 9)).reversed()
                .thenComparingDouble(CacheEntry::score)
                .thenComparing(CacheEntry::keyPrefix));
        ArrayList<String> evicted = new ArrayList<>();
        HashSet<String> removedKeys = new HashSet<>();
        long[] bytesState = {retainedBytes};
        int[] entriesState = {retainedEntries};

        evictUntilWithinLimits(
                entries,
                limits,
                scopeCounts,
                hashReferences,
                hashBytes,
                evicted,
                removedKeys,
                bytesState,
                entriesState,
                true
        );
        if (entriesState[0] > limits.maxEntries() || bytesState[0] > limits.maxBytes()) {
            evictUntilWithinLimits(
                    entries,
                    limits,
                    scopeCounts,
                    hashReferences,
                    hashBytes,
                    evicted,
                    removedKeys,
                    bytesState,
                    entriesState,
                    false
            );
        }
        return new RetentionPlan(
                List.copyOf(evicted),
                Map.copyOf(temperatures),
                Set.copyOf(hashReferences.keySet()),
                Math.max(bytesState[0], 0L)
        );
    }

    private static void evictUntilWithinLimits(
            List<CacheEntry> entries,
            RetentionLimits limits,
            Map<String, Integer> scopeCounts,
            Map<String, Integer> hashReferences,
            Map<String, Long> hashBytes,
            List<String> evicted,
            Set<String> removedKeys,
            long[] retainedBytes,
            int[] retainedEntries,
            boolean preserveScopeMinimum
    ) {
        for (CacheEntry entry : entries) {
            if (retainedEntries[0] <= limits.maxEntries() && retainedBytes[0] <= limits.maxBytes()) {
                return;
            }
            if (removedKeys.contains(entry.keyPrefix())) {
                continue;
            }
            int scopeCount = scopeCounts.getOrDefault(entry.scopeHash(), 0);
            if (preserveScopeMinimum && scopeCount <= limits.minimumEntriesPerScope()) {
                continue;
            }
            removedKeys.add(entry.keyPrefix());
            evicted.add(entry.keyPrefix());
            retainedEntries[0]--;
            scopeCounts.put(entry.scopeHash(), Math.max(scopeCount - 1, 0));
            int hashCount = hashReferences.getOrDefault(entry.hash(), 0) - 1;
            if (hashCount <= 0) {
                hashReferences.remove(entry.hash());
                retainedBytes[0] -= hashBytes.getOrDefault(entry.hash(), 0L);
            } else {
                hashReferences.put(entry.hash(), hashCount);
            }
        }
    }

    private static ArrayList<CacheEntry> readEntries(Properties index, ToLongFunction<String> storedBytes) {
        ArrayList<CacheEntry> entries = new ArrayList<>();
        if (index == null) {
            return entries;
        }
        for (String key : index.stringPropertyNames()) {
            if (!key.endsWith(".hash")) {
                continue;
            }
            String keyPrefix = key.substring(0, key.length() - "hash".length());
            String hash = index.getProperty(key, "").toLowerCase(java.util.Locale.ROOT);
            String scopeHash = index.getProperty(keyPrefix + "serverScopeHash", "");
            if (!hash.matches("[0-9a-f]{64}") || scopeHash.isBlank()) {
                continue;
            }
            long lastUsed = readLong(index, keyPrefix + "lastUsedAtMillis", 0L);
            long hitCount = readLong(index, keyPrefix + "hitCount", 0L);
            long savedBytes = readLong(index, keyPrefix + "savedBytes", 0L);
            long physicalBytes = Math.max(storedBytes.applyAsLong(hash), 0L);
            entries.add(new CacheEntry(
                    keyPrefix,
                    scopeHash,
                    hash,
                    lastUsed,
                    hitCount,
                    savedBytes,
                    physicalBytes,
                    score(lastUsed, hitCount, savedBytes)
            ));
        }
        return entries;
    }

    private static double score(long lastUsedAtMillis, long hitCount, long savedBytes) {
        double hitBonus = Math.log1p(Math.max(hitCount, 0L)) * 3.0D * DAY_MILLIS;
        double savedMib = (double) Math.max(savedBytes, 0L) / (1024D * 1024D);
        double savedBonus = Math.log1p(savedMib) * DAY_MILLIS;
        return Math.max(lastUsedAtMillis, 0L) + Math.min(hitBonus + savedBonus, 30D * DAY_MILLIS);
    }

    private static long readLong(Properties properties, String key, long fallback) {
        try {
            return Long.parseLong(properties.getProperty(key, Long.toString(fallback)).trim());
        } catch (NumberFormatException ignored) {
            return fallback;
        }
    }

    record RetentionLimits(long maxBytes, int maxEntries, int minimumEntriesPerScope) {
        RetentionLimits {
            maxBytes = Math.max(maxBytes, 1L);
            maxEntries = Math.max(maxEntries, 1);
            minimumEntriesPerScope = Math.max(Math.min(minimumEntriesPerScope, maxEntries), 0);
        }
    }

    record RetentionPlan(
            List<String> evictedKeyPrefixes,
            Map<String, Integer> temperatures,
            Set<String> referencedHashes,
            long retainedBytes
    ) {
        RetentionPlan {
            evictedKeyPrefixes = evictedKeyPrefixes == null ? List.of() : List.copyOf(evictedKeyPrefixes);
            temperatures = temperatures == null ? Map.of() : Map.copyOf(temperatures);
            referencedHashes = referencedHashes == null ? Set.of() : Set.copyOf(referencedHashes);
            retainedBytes = Math.max(retainedBytes, 0L);
        }

        private static RetentionPlan empty() {
            return new RetentionPlan(List.of(), Map.of(), Set.of(), 0L);
        }
    }

    private record CacheEntry(
            String keyPrefix,
            String scopeHash,
            String hash,
            long lastUsedAtMillis,
            long hitCount,
            long savedBytes,
            long storedBytes,
            double score
    ) {
    }
}
