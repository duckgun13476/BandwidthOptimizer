package com.PinkCats.bandwidthoptimizer.report;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/** Field occurrences, not byte allocations: several fields may share one packet. */
public final class EntitySyncDetailCounters {
    static final int MAX_KEYS = 4096;
    public static final int MAX_FIELDS = 256;
    private final Map<Key, Counter> counters = new HashMap<>();
    private long packets, unknownTypePackets, omittedFields;
    private long windowPackets, windowUnknownTypePackets, windowOmittedFields;

    public record Detail(String kind, String entityType, List<String> fields, int omittedFields) {
        public Detail {
            fields = List.copyOf(fields);
        }
        public String sourceKey() { return "entity_" + kind + ":" + entityType; }
    }
    public record Entry(String kind, String entityType, String fieldId, long occurrences, long windowOccurrences) {}
    public record Snapshot(long packets, long unknownTypePackets, long omittedFields,
                           long windowPackets, long windowUnknownTypePackets, long windowOmittedFields,
                           int trackedKeys, int rankingLimit, List<Entry> entries) {}
    private record Key(String kind, String entityType, String fieldId) {}
    private static final class Counter { long total, window; }

    public synchronized void record(Detail detail) {
        if (detail == null) return;
        packets++;
        windowPackets++;
        if ("<unknown>".equals(detail.entityType())) {
            unknownTypePackets++;
            windowUnknownTypePackets++;
        }
        long omitted = Math.max(detail.omittedFields(), 0);
        for (String field : detail.fields()) {
            Key key = new Key(detail.kind(), detail.entityType(), field);
            Counter counter = counters.get(key);
            if (counter == null && counters.size() < MAX_KEYS) {
                counter = new Counter();
                counters.put(key, counter);
            }
            if (counter == null) omitted++;
            else { counter.total++; counter.window++; }
        }
        omittedFields += omitted;
        windowOmittedFields += omitted;
    }

    public synchronized Snapshot snapshot(int limit, boolean resetWindow) {
        int bounded = Math.min(Math.max(limit, 0), 100);
        List<Entry> entries = new ArrayList<>(counters.size());
        counters.forEach((key, counter) -> entries.add(new Entry(key.kind(), key.entityType(), key.fieldId(), counter.total, counter.window)));
        var totalOrder = Comparator.comparingLong(Entry::occurrences).reversed()
                .thenComparing(Entry::kind).thenComparing(Entry::entityType).thenComparing(Entry::fieldId);
        var selected = new java.util.LinkedHashSet<Entry>();
        entries.stream().sorted(totalOrder).limit(bounded).forEach(selected::add);
        entries.stream().sorted(Comparator.comparingLong(Entry::windowOccurrences).reversed().thenComparing(totalOrder))
                .limit(bounded).forEach(selected::add);
        Snapshot result = new Snapshot(packets, unknownTypePackets, omittedFields, windowPackets,
                windowUnknownTypePackets, windowOmittedFields, counters.size(), bounded, List.copyOf(selected));
        if (resetWindow) {
            counters.values().forEach(counter -> counter.window = 0);
            windowPackets = windowUnknownTypePackets = windowOmittedFields = 0;
        }
        return result;
    }
}
