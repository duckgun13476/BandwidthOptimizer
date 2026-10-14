package com.PinkCats.bandwidthoptimizer.integration.createsifter;

import java.util.concurrent.atomic.AtomicLong;

/** Synchronous, same-thread transaction only; never retains a world or packet after return. */
public final class SifterSyncTransaction {
    private static final int MAX_DEPTH = 8;
    private static final ThreadLocal<Scope> CURRENT = new ThreadLocal<>();
    private static final long STARTED = System.currentTimeMillis();
    private static final AtomicLong TRANSACTIONS = new AtomicLong();
    private static final AtomicLong DEFERRED = new AtomicLong();
    private static final AtomicLong FLUSHED = new AtomicLong();
    private static final AtomicLong SAVED = new AtomicLong();

    private SifterSyncTransaction() {}

    public static void run(Object owner, Runnable operation, Runnable flush) {
        Scope parent = CURRENT.get();
        for (Scope scope = parent; scope != null; scope = scope.parent) {
            if (scope.owner == owner) { operation.run(); return; }
        }
        if (owner == null || (parent != null && parent.depth >= MAX_DEPTH)) { operation.run(); return; }
        Scope scope = new Scope(owner, parent);
        CURRENT.set(scope);
        increment(TRANSACTIONS, 1);
        Throwable failure = null;
        try { operation.run(); }
        catch (RuntimeException | Error exception) { failure = exception; throw exception; }
        finally {
            if (parent == null) CURRENT.remove(); else CURRENT.set(parent);
            if (scope.requests != 0) {
                try {
                    flush.run();
                    increment(FLUSHED, 1);
                    increment(SAVED, scope.requests - 1);
                } catch (RuntimeException | Error exception) {
                    if (failure == null) throw exception;
                    if (failure != exception) failure.addSuppressed(exception);
                }
            }
        }
    }

    public static boolean defer(Object owner) {
        for (Scope scope = CURRENT.get(); scope != null; scope = scope.parent) {
            if (scope.owner == owner) {
                if (scope.requests < Long.MAX_VALUE) scope.requests++;
                increment(DEFERRED, 1);
                return true;
            }
        }
        return false;
    }

    public static Snapshot snapshot() { return new Snapshot(STARTED, TRANSACTIONS.get(), DEFERRED.get(), FLUSHED.get(), SAVED.get()); }
    private static void increment(AtomicLong counter, long amount) {
        if (amount > 0) counter.updateAndGet(value -> Long.MAX_VALUE - value < amount ? Long.MAX_VALUE : value + amount);
    }
    public record Snapshot(long startedAtMillis, long transactions, long deferredCalls, long flushedCalls, long savedCalls) {}
    private static final class Scope {
        final Object owner;
        final Scope parent;
        final int depth;
        long requests;
        Scope(Object owner, Scope parent) { this.owner = owner; this.parent = parent; depth = parent == null ? 1 : parent.depth + 1; }
    }
}
