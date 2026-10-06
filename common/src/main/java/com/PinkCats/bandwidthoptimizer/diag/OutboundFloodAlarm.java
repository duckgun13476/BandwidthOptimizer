package com.PinkCats.bandwidthoptimizer.diag;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.function.Consumer;

/** Bounded metadata only; observes pressure without changing packet delivery. */
public final class OutboundFloodAlarm {
    static final long MIB = 1024L * 1024;
    static final int MAX_SOURCES = 64, BUCKETS = 10;
    private final Map<String, Source> sources = new LinkedHashMap<>();
    private final Source total = new Source();
    private long pressuredSince = -1, lastAlarm = -1;

    public synchronized void submitted(String id, int bytes, long second) {
        if (bytes <= 0) return;
        int totalSlot = (int) Math.floorMod(second, BUCKETS);
        if (total.seconds[totalSlot] != second) {
            total.seconds[totalSlot] = second; total.bytes[totalSlot] = 0; total.packets[totalSlot] = 0;
        }
        total.bytes[totalSlot] += bytes;
        total.packets[totalSlot]++;
        String key = normalize(id);
        sources.entrySet().removeIf(entry -> entry.getValue().lastSecond <= second - BUCKETS);
        Source s = sources.get(key);
        if (s == null) {
            // Weighted Space-Saving admission: a late heavy producer cannot be permanently
            // hidden behind the first packet classes seen at login. Estimates select victims
            // only; reported bytes below are exact observations since admission, a lower bound.
            long estimate = 0;
            if (sources.size() >= MAX_SOURCES) {
                var victim = sources.entrySet().stream()
                        .min(java.util.Comparator.comparingLong(entry -> entry.getValue().estimate)).orElseThrow();
                estimate = victim.getValue().estimate;
                sources.remove(victim.getKey());
            }
            s = new Source();
            s.estimate = estimate;
            sources.put(key, s);
        }
        s.estimate += bytes;
        s.lastSecond = second;
        int slot = (int) Math.floorMod(second, BUCKETS);
        if (s.seconds[slot] != second) { s.seconds[slot] = second; s.bytes[slot] = 0; s.packets[slot] = 0; }
        s.bytes[slot] += bytes; s.packets[slot]++;
    }

    public synchronized void sample(long second, boolean writable, long pendingBytes, Consumer<String> alarm) {
        if (writable || pendingBytes < 64 * MIB) { pressuredSince = -1; return; }
        if (pressuredSince < 0) pressuredSince = second;
        if (second - pressuredSince < 5 || (lastAlarm >= 0 && second - lastAlarm < 60)) return;
        String detail = describe(second, 32 * MIB);
        if (detail.isEmpty()) return;
        lastAlarm = second;
        alarm.accept("windowSeconds=10 pressureSeconds=" + (second - pressuredSince)
                + " connectionPendingChargeBytes=" + pendingBytes + " writable=false"
                + " attribution=large_producer_during_connection_pressure_not_proven_mod_bug"
                + " action=warning_only" + detail);
    }

    public synchronized String describe(long second, long minimumBytes) {
        StringBuilder detail = new StringBuilder(" sourceAccounting=observed_lower_bound_not_queue_ownership");
        boolean found = false;
        long retainedBytes = 0;
        for (var entry : sources.entrySet()) {
            long bytes = 0, packets = 0;
            Source s = entry.getValue();
            for (int i = 0; i < BUCKETS; i++) if (s.seconds[i] <= second && s.seconds[i] > second - BUCKETS) {
                bytes += s.bytes[i]; packets += s.packets[i];
            }
            if (bytes > 0 && bytes >= minimumBytes) {
                found = true;
                detail.append(" [source=").append(entry.getKey())
                        .append(" submittedLogicalBytes=").append(bytes).append(" packets=").append(packets).append(']');
            }
            retainedBytes += bytes;
        }
        long allBytes = 0;
        for (int i = 0; i < BUCKETS; i++) if (total.seconds[i] <= second && total.seconds[i] > second - BUCKETS) {
            allBytes += total.bytes[i];
        }
        long unattributed = Math.max(0, allBytes - retainedBytes);
        detail.append(" windowSubmittedLogicalBytes=").append(allBytes)
                .append(" unattributedLogicalBytes=").append(unattributed);
        found |= unattributed > 0 && unattributed >= minimumBytes;
        return found ? detail.toString() : "";
    }

    private static String normalize(String id) {
        if (id == null) return "unknown";
        int payload = id.indexOf("payloadChannel=");
        String key = payload < 0 ? id : id.substring(payload + "payloadChannel=".length());
        if (payload >= 0 && (key.equals("null") || key.equals("-") || key.isEmpty())) key = id.substring(0, payload);
        return key.substring(0, Math.min(key.length(), 192));
    }

    synchronized int sourceCount() { return sources.size(); }
    private static final class Source {
        long estimate, lastSecond;
        final long[] seconds = new long[BUCKETS], bytes = new long[BUCKETS], packets = new long[BUCKETS];
    }
}
