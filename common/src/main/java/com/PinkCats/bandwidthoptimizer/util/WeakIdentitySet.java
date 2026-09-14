package com.PinkCats.bandwidthoptimizer.util;

import java.lang.ref.ReferenceQueue;
import java.lang.ref.WeakReference;
import java.util.HashMap;
import java.util.Map;

public final class WeakIdentitySet<T> {

    private final ReferenceQueue<T> staleReferences = new ReferenceQueue<>();
    private final Map<IdentityReference<T>, Boolean> entries = new HashMap<>();

    public synchronized void add(T value) {
        if (value == null) {
            return;
        }
        removeStaleReferences();
        entries.put(new IdentityReference<>(value, staleReferences), Boolean.TRUE);
    }

    public synchronized boolean contains(T value) {
        if (value == null) {
            return false;
        }
        removeStaleReferences();
        return entries.containsKey(new IdentityReference<>(value));
    }

    public synchronized boolean remove(T value) {
        if (value == null) {
            return false;
        }
        removeStaleReferences();
        return entries.remove(new IdentityReference<>(value)) != null;
    }

    synchronized int size() {
        removeStaleReferences();
        return entries.size();
    }

    @SuppressWarnings("unchecked")
    private void removeStaleReferences() {
        IdentityReference<T> reference;
        while ((reference = (IdentityReference<T>) staleReferences.poll()) != null) {
            entries.remove(reference);
        }
    }

    private static final class IdentityReference<T> extends WeakReference<T> {
        private final int identityHash;

        private IdentityReference(T value) {
            super(value);
            this.identityHash = System.identityHashCode(value);
        }

        private IdentityReference(T value, ReferenceQueue<T> queue) {
            super(value, queue);
            this.identityHash = System.identityHashCode(value);
        }

        @Override
        public int hashCode() {
            return identityHash;
        }

        @Override
        public boolean equals(Object other) {
            if (this == other) {
                return true;
            }
            if (!(other instanceof IdentityReference<?> reference)) {
                return false;
            }
            Object value = get();
            return value != null && value == reference.get();
        }
    }
}
