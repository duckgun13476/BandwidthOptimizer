package com.PinkCats.bandwidthoptimizer.networkprobe;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/** Server-thread-owned, bounded task results. Times use a monotonic clock. */
final class LatencyProbeTasks {
    static final int LIMIT = 64;
    static final long RETENTION = 300_000_000_000L;
    static final long MAX_RUNNING = 10_800_000_000_000L;
    private final Map<String, Task> tasks = new LinkedHashMap<>();

    Task create(List<String> names, long now) {
        expire(now);
        if (tasks.size() >= LIMIT) return null;
        Task task = new Task(UUID.randomUUID().toString(), names, now);
        tasks.put(task.id, task);
        return task;
    }
    Task get(String id, long now) { expire(now); return tasks.get(id); }
    void expire(long now) {
        tasks.values().removeIf(t -> t.finishedAt != 0 ? now - t.finishedAt >= RETENTION : now - t.startedAt >= MAX_RUNNING);
    }
    static final class Entry {
        final String name;
        String status = "pending";
        LatencyProbeWindow.Result result;
        Entry(String name) { this.name = name; }
    }
    static final class Task {
        final String id;
        final long startedAt;
        long finishedAt;
        final List<Entry> entries;
        Task(String id, List<String> names, long now) {
            this.id = id; startedAt = now; entries = new ArrayList<>(names.size());
            for (String name : names) entries.add(new Entry(name));
        }
        void finish(long now) { finishedAt = now == 0 ? 1 : now; }
    }
}
