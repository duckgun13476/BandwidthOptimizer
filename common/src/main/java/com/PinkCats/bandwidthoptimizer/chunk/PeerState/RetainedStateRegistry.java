package com.PinkCats.bandwidthoptimizer.chunk.PeerState;

import java.util.HashMap;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import java.util.function.LongSupplier;
import java.util.function.Supplier;

final class RetainedStateRegistry<K, V> {

    private final int maxRetainedEntries;
    private final long retainedTtlNanos;
    private final LongSupplier nanoTime;
    private final Map<K, V> activeStates = new HashMap<>();
    private final LinkedHashMap<K, RetainedState<V>> retainedStates = new LinkedHashMap<>(16, 0.75F, true);

    RetainedStateRegistry(int maxRetainedEntries, long retainedTtlNanos, LongSupplier nanoTime) {
        this.maxRetainedEntries = Math.max(maxRetainedEntries, 1);
        this.retainedTtlNanos = Math.max(retainedTtlNanos, 1L);
        this.nanoTime = Objects.requireNonNull(nanoTime);
    }

    synchronized V activate(K key, Supplier<V> factory) {
        if (key == null) {
            return null;
        }
        pruneExpired(this.nanoTime.getAsLong());
        V active = this.activeStates.get(key);
        if (active != null) {
            return active;
        }
        RetainedState<V> retained = this.retainedStates.remove(key);
        V state = retained == null ? Objects.requireNonNull(factory.get()) : retained.state();
        this.activeStates.put(key, state);
        return state;
    }

    synchronized V release(K key, boolean retain) {
        if (key == null) {
            return null;
        }
        long now = this.nanoTime.getAsLong();
        pruneExpired(now);
        V state = this.activeStates.remove(key);
        RetainedState<V> previousRetained = this.retainedStates.remove(key);
        if (state == null && previousRetained != null) {
            state = previousRetained.state();
        }
        if (retain && state != null) {
            this.retainedStates.put(key, new RetainedState<>(state, now));
            trimRetainedEntries();
        }
        return state;
    }

    synchronized int activeSize() {
        return this.activeStates.size();
    }

    synchronized int retainedSize() {
        pruneExpired(this.nanoTime.getAsLong());
        return this.retainedStates.size();
    }

    private void pruneExpired(long now) {
        Iterator<Map.Entry<K, RetainedState<V>>> iterator = this.retainedStates.entrySet().iterator();
        while (iterator.hasNext()) {
            RetainedState<V> retained = iterator.next().getValue();
            if (elapsedNanos(now, retained.releasedAtNanos()) >= this.retainedTtlNanos) {
                iterator.remove();
            } else {
                break;
            }
        }
    }

    private void trimRetainedEntries() {
        Iterator<K> iterator = this.retainedStates.keySet().iterator();
        while (this.retainedStates.size() > this.maxRetainedEntries && iterator.hasNext()) {
            iterator.next();
            iterator.remove();
        }
    }

    private static long elapsedNanos(long now, long then) {
        long elapsed = now - then;
        return elapsed < 0L ? Long.MAX_VALUE : elapsed;
    }

    private record RetainedState<V>(V state, long releasedAtNanos) {}
}
