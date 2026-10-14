package com.PinkCats.bandwidthoptimizer.integration.lightmanscurrency;

import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Supplier;
import java.util.UUID;

/** Coalesces adjacent full bank snapshots, never append notifications or deltas.
 * Synchronous scope only, bounded nesting, no timer or retained packet history. */
public final class CurrencyBankSyncTransaction {
    private static final ThreadLocal<Scope> CURRENT = new ThreadLocal<>();
    private static final int MAX_DEPTH = 8;
    private static final long STARTED = System.currentTimeMillis();
    private static final AtomicLong REQUESTS = new AtomicLong(), FLUSHES = new AtomicLong(), SAVED = new AtomicLong();
    private CurrencyBankSyncTransaction() {}

    public static <T> T run(Object owner, Supplier<T> operation) {
        Scope parent = CURRENT.get();
        if (owner == null || (parent != null && (parent.owner == owner || parent.depth >= MAX_DEPTH))) return operation.get();
        barrier(); // a different trader is an ordering boundary
        Scope scope = new Scope(owner, parent == null ? 1 : parent.depth + 1);
        CURRENT.set(scope);
        Throwable failure = null;
        try { return operation.get(); }
        catch (RuntimeException | Error exception) { failure = exception; throw exception; }
        finally {
            try { barrier(); }
            catch (RuntimeException | Error exception) {
                if (failure == null) throw exception;
                if (failure != exception) failure.addSuppressed(exception);
            } finally { if (parent == null) CURRENT.remove(); else CURRENT.set(parent); }
        }
    }

    public static boolean defer(Object cache, UUID account, Runnable flush) {
        Scope scope = CURRENT.get();
        if (scope == null || scope.flushing || cache == null || account == null) return false;
        if (scope.requests != 0 && (scope.cache != cache || !account.equals(scope.account))) barrier();
        scope.cache=cache; scope.account=account; scope.flush=flush;
        if (scope.requests < Long.MAX_VALUE) scope.requests++;
        increment(REQUESTS,1);
        return true;
    }

    public static void barrier() {
        Scope scope = CURRENT.get();
        if (scope == null || scope.flushing || scope.requests == 0) return;
        long requests = scope.requests;
        Runnable flush=scope.flush;
        scope.requests = 0;
        scope.cache=null; scope.account=null; scope.flush=null;
        scope.flushing = true;
        try { flush.run(); increment(FLUSHES,1); increment(SAVED,requests - 1); }
        finally { scope.flushing = false; }
    }

    public static Snapshot snapshot() { return new Snapshot(STARTED,REQUESTS.get(),FLUSHES.get(),SAVED.get()); }
    private static void increment(AtomicLong counter,long amount) { if(amount>0)counter.updateAndGet(n->Long.MAX_VALUE-n<amount?Long.MAX_VALUE:n+amount); }
    public record Snapshot(long startedAtMillis,long requestedCalls,long flushedCalls,long savedCalls) {}
    private static final class Scope {
        final Object owner; final int depth; Object cache; UUID account; Runnable flush; long requests; boolean flushing;
        Scope(Object owner,int depth) {this.owner=owner;this.depth=depth;}
    }
}
