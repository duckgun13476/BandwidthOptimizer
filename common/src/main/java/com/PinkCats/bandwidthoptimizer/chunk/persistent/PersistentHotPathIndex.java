package com.PinkCats.bandwidthoptimizer.chunk.persistent;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Consumer;

final class PersistentHotPathIndex<T> {
    private static final int BUCKET_COUNT = 256;
    private static final int BUCKET_MASK = BUCKET_COUNT - 1;

    private final List<Map<String, T>> buckets;
    private final int size;

    private PersistentHotPathIndex(List<Map<String, T>> buckets, int size) {
        this.buckets = List.copyOf(buckets);
        this.size = Math.max(size, 0);
    }

    static <T> PersistentHotPathIndex<T> empty() {
        ArrayList<Map<String, T>> buckets = new ArrayList<>(BUCKET_COUNT);
        for (int bucket = 0; bucket < BUCKET_COUNT; bucket++) {
            buckets.add(Map.of());
        }
        return new PersistentHotPathIndex<>(buckets, 0);
    }

    static <T> PersistentHotPathIndex<T> from(Map<String, T> entries) {
        PersistentHotPathIndex<T> empty = empty();
        return entries == null || entries.isEmpty()
                ? empty
                : empty.withChanges(entries, Set.of());
    }

    T get(String key) {
        return key == null ? null : buckets.get(bucketIndex(key)).get(key);
    }

    int size() {
        return size;
    }

    void forEachValue(Consumer<T> consumer) {
        if (consumer == null) {
            return;
        }
        for (Map<String, T> bucket : buckets) {
            bucket.values().forEach(consumer);
        }
    }

    PersistentHotPathIndex<T> withChanges(Map<String, T> upserts, Set<String> removals) {
        if ((upserts == null || upserts.isEmpty()) && (removals == null || removals.isEmpty())) {
            return this;
        }

        ArrayList<Map<String, T>> nextBuckets = new ArrayList<>(buckets);
        HashMap<Integer, HashMap<String, T>> writableBuckets = new HashMap<>();
        int nextSize = size;

        if (removals != null) {
            for (String key : removals) {
                if (key == null) {
                    continue;
                }
                HashMap<String, T> bucket = writableBucket(nextBuckets, writableBuckets, key);
                if (bucket.remove(key) != null) {
                    nextSize--;
                }
            }
        }
        if (upserts != null) {
            for (Map.Entry<String, T> entry : upserts.entrySet()) {
                String key = entry.getKey();
                T value = entry.getValue();
                if (key == null || value == null) {
                    continue;
                }
                HashMap<String, T> bucket = writableBucket(nextBuckets, writableBuckets, key);
                if (bucket.put(key, value) == null) {
                    nextSize++;
                }
            }
        }

        for (Map.Entry<Integer, HashMap<String, T>> entry : writableBuckets.entrySet()) {
            nextBuckets.set(entry.getKey(), Map.copyOf(entry.getValue()));
        }
        return new PersistentHotPathIndex<>(nextBuckets, nextSize);
    }

    private static <T> HashMap<String, T> writableBucket(
            List<Map<String, T>> nextBuckets,
            Map<Integer, HashMap<String, T>> writableBuckets,
            String key
    ) {
        int bucketIndex = bucketIndex(key);
        return writableBuckets.computeIfAbsent(
                bucketIndex,
                ignored -> new HashMap<>(nextBuckets.get(bucketIndex))
        );
    }

    private static int bucketIndex(String key) {
        int hash = key.hashCode();
        hash ^= hash >>> 16;
        return hash & BUCKET_MASK;
    }
}
