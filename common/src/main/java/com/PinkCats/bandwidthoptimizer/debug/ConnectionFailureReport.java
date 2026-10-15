package com.PinkCats.bandwidthoptimizer.debug;

import com.PinkCats.bandwidthoptimizer.Bandwidthoptimizer;
import com.PinkCats.bandwidthoptimizer.connection.ConnectionDisconnectClassifier.Decision;
import com.PinkCats.bandwidthoptimizer.connection.ConnectionDisconnectClassifier.Category;
import com.PinkCats.bandwidthoptimizer.util.BandwidthOptimizerOutputPaths;
import com.google.gson.JsonObject;

import java.nio.file.Path;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

/** Operational failure totals, independent of finite detailed diagnostic samples. */
public final class ConnectionFailureReport {
    private static final java.util.concurrent.atomic.AtomicBoolean initializationWarned = new java.util.concurrent.atomic.AtomicBoolean();
    private ConnectionFailureReport() {}

    public static boolean isFailure(Category category) {
        return switch (category) {
            case BO_TRANSPORT_FAILURE, PROTOCOL_FAILURE, SECURITY_FAILURE,
                    READ_TIMEOUT, CONNECT_TIMEOUT, CONNECTION_RESET, CONNECT_FAILURE -> true;
            default -> false;
        };
    }

    public static void record(Decision decision) {
        if (decision == null || !isFailure(decision.category())) return;
        try {
            Holder.RECORDER.record(decision, System.currentTimeMillis());
        } catch (RuntimeException | LinkageError failure) {
            if (initializationWarned.compareAndSet(false, true)) Bandwidthoptimizer.LOGGER.warn(
                    "[BO] Connection failure reporting could not start; networking is unchanged", failure);
        }
    }

    private static final class Holder {
        static final Recorder RECORDER = new Recorder(BandwidthOptimizerOutputPaths.resolve(
                "diagnostics/connection", "failures.jsonl").toAbsolutePath().normalize(), true);
        static {
            Runtime.getRuntime().addShutdownHook(new Thread(RECORDER::close, "bo-connection-failure-close"));
        }
    }

    static final class Recorder implements AutoCloseable {
        private final BoundedDiagnosticFileWriter writer;
        private final ScheduledExecutorService scheduler;
        private final long[] window = new long[Category.values().length];
        private final long[] totals = new long[Category.values().length];
        private final String[] stages = new String[Category.values().length];
        private final String[] errors = new String[Category.values().length];
        private final long startedAt = System.currentTimeMillis();
        private long firstAt;
        private long lastAt;
        private boolean started;
        private boolean notified;
        private final Path path;

        Recorder(Path path, boolean automatic) {
            this.path = path.toAbsolutePath().normalize();
            writer = new BoundedDiagnosticFileWriter(this.path, "connection-failures", 32, 256 * 1024L,
                    1024 * 1024L, 3);
            scheduler = automatic ? Executors.newSingleThreadScheduledExecutor(task -> {
                Thread thread = new Thread(task, "bo-connection-failure-summary");
                thread.setDaemon(true);
                thread.setContextClassLoader(ConnectionFailureReport.class.getClassLoader());
                return thread;
            }) : null;
        }

        synchronized void record(Decision decision, long now) {
            if (!isFailure(decision.category())) return;
            int index = decision.category().ordinal();
            window[index] = increment(window[index]);
            totals[index] = increment(totals[index]);
            if (firstAt == 0) firstAt = now;
            lastAt = now;
            stages[index] = bounded(decision.boStage());
            errors[index] = bounded(decision.throwableClass());
            if (!started && scheduler != null) {
                started = true;
                scheduler.scheduleWithFixedDelay(this::flush, 0, 60, TimeUnit.SECONDS);
            }
        }

        void flush() {
            JsonObject summary;
            synchronized (this) {
                if (firstAt == 0) return;
                summary = new JsonObject();
                summary.addProperty("v", 1);
                summary.addProperty("event", "connection_failure_summary");
                summary.addProperty("report_started_at_ms", startedAt);
                summary.addProperty("count_unit", "closed_connections");
                summary.addProperty("first_at_ms", firstAt);
                summary.addProperty("last_at_ms", lastAt);
                summary.addProperty("writer_dropped_lines", writer.droppedLines());
                JsonObject reasons = new JsonObject();
                for (Category category : Category.values()) {
                    int index = category.ordinal();
                    if (totals[index] == 0) continue;
                    JsonObject reason = new JsonObject();
                    reason.addProperty("window_count", window[index]);
                    reason.addProperty("process_count", totals[index]);
                    reason.addProperty("last_bo_stage", stages[index]);
                    reason.addProperty("last_throwable_class", errors[index]);
                    reasons.add(category.name(), reason);
                    window[index] = 0;
                }
                summary.add("reasons", reasons);
                firstAt = 0;
                lastAt = 0;
            }
            if (writer.offer(summary.toString()) && !notified) {
                notified = true;
                if (scheduler != null) Bandwidthoptimizer.LOGGER.warn(
                        "[BO] Connection failures detected; bounded failure summaries are queued at {}", path);
            }
        }

        private static long increment(long value) { return value == Long.MAX_VALUE ? value : value + 1; }
        private static String bounded(String value) {
            return value == null ? "" : value.substring(0, Math.min(value.length(), 160));
        }

        @Override public void close() {
            if (scheduler != null) {
                scheduler.shutdown();
                try { scheduler.awaitTermination(2, TimeUnit.SECONDS); }
                catch (InterruptedException exception) { Thread.currentThread().interrupt(); }
            }
            flush();
            writer.close();
        }
    }
}
