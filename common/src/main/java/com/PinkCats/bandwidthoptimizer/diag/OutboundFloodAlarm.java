package com.PinkCats.bandwidthoptimizer.diag;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.function.Consumer;

/** Bounded metadata only; observes pressure without changing packet delivery. */
public final class OutboundFloodAlarm {
    static final long MIB = 1024L * 1024;
    static final int MAX_SOURCES = 64, BUCKETS = 10;
    private final Map<String, Source> sources = new LinkedHashMap<>();
    private long pressuredSince = -1, lastAlarm = -1;

    public synchronized void submitted(String id, int bytes, long second) {
        if (bytes <= 0) return;
        String key = id == null ? "unknown" : id.substring(0, Math.min(id.length(), 192));
        // Reserve one bounded overflow bucket rather than evict active producers.
        if (!sources.containsKey(key) && sources.size() >= MAX_SOURCES - 1) key = "other_sources";
        Source s = sources.computeIfAbsent(key, ignored -> new Source());
        int slot = (int) Math.floorMod(second, BUCKETS);
        if (s.seconds[slot] != second) { s.seconds[slot] = second; s.bytes[slot] = 0; s.packets[slot] = 0; }
        s.bytes[slot] += bytes; s.packets[slot]++;
    }

    public synchronized void sample(long second, boolean writable, long pendingBytes, Consumer<String> alarm) {
        if (writable || pendingBytes < 64 * MIB) { pressuredSince = -1; return; }
        if (pressuredSince < 0) pressuredSince = second;
        if (second - pressuredSince < 5 || (lastAlarm >= 0 && second - lastAlarm < 60)) return;
        StringBuilder detail = new StringBuilder();
        for (var entry : sources.entrySet()) {
            long bytes = 0, packets = 0;
            Source s = entry.getValue();
            for (int i = 0; i < BUCKETS; i++) if (s.seconds[i] <= second && s.seconds[i] > second - BUCKETS) {
                bytes += s.bytes[i]; packets += s.packets[i];
            }
            if (bytes >= 32 * MIB) detail.append(" [source=").append(entry.getKey())
                    .append(" submittedLogicalBytes=").append(bytes).append(" packets=").append(packets).append(']');
        }
        if (detail.length() == 0) return;
        lastAlarm = second;
        alarm.accept("windowSeconds=10 pressureSeconds=" + (second - pressuredSince)
                + " connectionPendingChargeBytes=" + pendingBytes + " writable=false"
                + " attribution=large_producer_during_connection_pressure_not_proven_mod_bug"
                + " action=warning_only" + detail);
    }

    synchronized int sourceCount() { return sources.size(); }
    private static final class Source {
        final long[] seconds = new long[BUCKETS], bytes = new long[BUCKETS], packets = new long[BUCKETS];
    }
}
