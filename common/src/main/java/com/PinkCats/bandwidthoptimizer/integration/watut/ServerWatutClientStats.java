package com.PinkCats.bandwidthoptimizer.integration.watut;

import java.util.HashMap;
import java.util.Map;

/** Connection-scoped cumulative client reports. Client claims are not measured socket bytes. */
public final class ServerWatutClientStats {
    public static final int MAX_CLIENTS = 4096;
    private static final Map<Object, Baseline> CLIENTS = new HashMap<>();
    private static long saved, frames, sent, startedAtMillis = System.currentTimeMillis();
    private ServerWatutClientStats() {}

    public static synchronized void accept(Object connection, int sequence, long bytes, long count, long sentBytes) {
        if (connection == null || bytes < 0 || count < 0 || sentBytes < 0) return;
        Baseline old = CLIENTS.get(connection);
        if (old == null && CLIENTS.size() >= MAX_CLIENTS) return;
        // Serial-number arithmetic also handles int sequence wrap; stale/duplicate reports never change baseline.
        if (old != null && sequence - old.sequence <= 0) return;
        boolean rollback = old != null && (bytes < old.saved || count < old.frames || sentBytes < old.sent);
        if (!rollback) {
            saved = add(saved, bytes - (old == null ? 0 : old.saved));
            frames = add(frames, count - (old == null ? 0 : old.frames));
            sent = add(sent, sentBytes - (old == null ? 0 : old.sent));
        }
        CLIENTS.put(connection, new Baseline(sequence, bytes, count, sentBytes));
    }

    public static synchronized void remove(Object connection) { CLIENTS.remove(connection); }
    public static synchronized Snapshot snapshot() { return new Snapshot(saved, frames, sent, CLIENTS.size(), startedAtMillis); }
    /** Reset the observation window without replaying already accepted client cumulative values. */
    public static synchronized void reset() { saved = frames = sent = 0; startedAtMillis = System.currentTimeMillis(); }
    public static synchronized void clear() { CLIENTS.clear(); reset(); }
    private static long add(long value, long delta) { return value >= Long.MAX_VALUE - delta ? Long.MAX_VALUE : value + delta; }
    private record Baseline(int sequence, long saved, long frames, long sent) {}
    public record Snapshot(long savedPayloadBytes, long suppressedFrames, long sentPayloadBytes,
                           int reportingConnections, long startedAtMillis) {}
}
