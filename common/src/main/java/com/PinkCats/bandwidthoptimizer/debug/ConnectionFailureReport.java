package com.PinkCats.bandwidthoptimizer.debug;

import com.PinkCats.bandwidthoptimizer.Bandwidthoptimizer;
import com.PinkCats.bandwidthoptimizer.connection.ConnectionDisconnectClassifier.Decision;
import com.PinkCats.bandwidthoptimizer.connection.ConnectionDisconnectClassifier.Category;
import com.PinkCats.bandwidthoptimizer.util.BandwidthOptimizerOutputPaths;
import com.google.gson.JsonObject;
import com.google.gson.JsonArray;
import io.netty.channel.Channel;
import io.netty.util.AttributeKey;
import com.PinkCats.bandwidthoptimizer.channel.ChannelIdentity;
import java.util.ArrayDeque;

import java.nio.file.Path;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

/** Operational failure totals, independent of finite detailed diagnostic samples. */
public final class ConnectionFailureReport {
    private static final AttributeKey<Evidence> EVIDENCE = AttributeKey.valueOf("bandwidthoptimizer:failure_evidence");
    private static final java.util.concurrent.atomic.AtomicBoolean initializationWarned = new java.util.concurrent.atomic.AtomicBoolean();
    private static final java.util.concurrent.atomic.AtomicLong suppressedDetails = new java.util.concurrent.atomic.AtomicLong();
    private ConnectionFailureReport() {}

    public static void bindPlayer(Channel channel, String uuid, String name) {
        if (channel == null || !DiagnosticToolRegistry.isEnabled(DiagnosticToolRegistry.Tool.CONNECTION_CLOSE)) return;
        try {
            Evidence evidence = evidence(channel);
            synchronized (evidence) {
                evidence.playerId = bounded(uuid, 40);
                evidence.playerName = bounded(name, 64);
            }
        } catch (RuntimeException | LinkageError ignored) { }
    }

    /** Fixed-size metadata only: never retain a Throwable, packet, player or ByteBuf. */
    public static void observe(Channel channel, String event, String text) {
        if (channel == null || !DiagnosticToolRegistry.isEnabled(DiagnosticToolRegistry.Tool.CONNECTION_CLOSE)) return;
        try {
            Evidence evidence = evidence(channel);
            synchronized (evidence) {
                if (evidence.events.size() == 16) evidence.events.removeFirst();
                JsonObject item = new JsonObject();
                item.addProperty("at_ms", System.currentTimeMillis());
                item.addProperty("event", bounded(event, 64));
                item.addProperty("text", bounded(text, 384));
                evidence.events.addLast(item);
            }
        } catch (RuntimeException | LinkageError ignored) { /* diagnostics cannot affect networking */ }
    }

    public static void observeException(Channel channel, Throwable failure) {
        if (channel == null || failure == null
                || !DiagnosticToolRegistry.isEnabled(DiagnosticToolRegistry.Tool.CONNECTION_CLOSE)) return;
        try {
            Evidence evidence = evidence(channel);
            synchronized (evidence) {
                if (evidence.firstCause == null) evidence.firstCause = causes(failure);
                evidence.lastCause = causes(failure);
            }
            observe(channel, "exception", failure.getClass().getName());
        } catch (RuntimeException | LinkageError ignored) { }
    }

    private static Evidence evidence(Channel channel) {
        Evidence value = channel.attr(EVIDENCE).get();
        if (value != null) return value;
        Evidence created = new Evidence();
        Evidence raced = channel.attr(EVIDENCE).setIfAbsent(created);
        return raced == null ? created : raced;
    }

    public static JsonArray causes(Throwable failure) {
        JsonArray result = new JsonArray();
        for (int depth = 0; failure != null && depth < 8; depth++) {
            JsonObject cause = new JsonObject();
            cause.addProperty("class", failure.getClass().getName());
            cause.addProperty("message", bounded(failure.getMessage(), 512));
            JsonArray stack = new JsonArray();
            StackTraceElement[] elements = failure.getStackTrace();
            for (int i = 0; i < Math.min(elements.length, 8); i++) stack.add(bounded(elements[i].toString(), 256));
            cause.add("stack", stack);
            result.add(cause);
            if (failure.getCause() == failure) break;
            failure = failure.getCause();
        }
        return result;
    }

    public static void record(Channel channel, Decision decision, String lastInbound, String payloadChannel, long inboundAge) {
        record(decision); // Aggregate counts are independent of the finite detail budget.
        if (!isFailure(decision.category()) || channel == null) return;
        try {
            Evidence evidence = channel.attr(EVIDENCE).getAndSet(null);
            if (!DiagnosticLog.acquireFileEvent(DiagnosticToolRegistry.Tool.CONNECTION_CLOSE,
                    "event=connection_failure_detail")) {
                suppressedDetails.incrementAndGet();
                return;
            }
            JsonObject detail = new JsonObject();
            detail.addProperty("channel_id", ChannelIdentity.longText(channel));
            detail.addProperty("category", decision.category().name());
            detail.addProperty("trigger", decision.trigger());
            detail.addProperty("bo_stage", bounded(decision.boStage(), 160));
            detail.addProperty("throwable_class", decision.throwableClass());
            detail.addProperty("last_inbound_packet", bounded(lastInbound, 256));
            detail.addProperty("last_inbound_payload_channel", bounded(payloadChannel, 160));
            detail.addProperty("last_inbound_age_ms", inboundAge);
            detail.addProperty("bo_version", Bandwidthoptimizer.displayVersion());
            detail.addProperty("phase", lastInbound.startsWith("net.minecraft.network.protocol.game.") ? "PLAY"
                    : lastInbound.startsWith("net.minecraft.network.protocol.login.") ? "LOGIN"
                    : lastInbound.startsWith("net.minecraft.network.protocol.configuration.") ? "CONFIGURATION" : "UNKNOWN");
            detail.addProperty("phase_source", "last named inbound packet; obfuscated names remain UNKNOWN");
            JsonArray pipeline = new JsonArray();
            int count = 0;
            for (var entry : channel.pipeline()) {
                if (count++ == 32) break;
                pipeline.add(bounded(entry.getKey() + ":" + entry.getValue().getClass().getName(), 256));
            }
            detail.add("pipeline", pipeline);
            if (evidence != null) synchronized (evidence) {
                detail.addProperty("endpoint_role", evidence.playerId.isEmpty() ? "UNKNOWN" : "SERVER");
                detail.addProperty("player_uuid", evidence.playerId);
                detail.addProperty("player_name", evidence.playerName);
                detail.add("first_exception", evidence.firstCause);
                detail.add("last_exception", evidence.lastCause);
                JsonArray events = new JsonArray();
                evidence.events.forEach(events::add);
                detail.add("recent_events", events);
            }
            Holder.RECORDER.detail("connection_failure_detail", detail);
        } catch (RuntimeException | LinkageError ignored) { }
    }

    /** Shared sink for opt-in decoder evidence; no per-failure JSON or binary files. */
    public static void decoderDetail(JsonObject detail) {
        try {
            if (DiagnosticLog.acquireFileEvent(DiagnosticToolRegistry.Tool.CONNECTION_CLOSE,
                    "event=decoder_failure_detail")) Holder.RECORDER.detail("decoder_failure_detail", detail);
            else suppressedDetails.incrementAndGet();
        } catch (RuntimeException | LinkageError ignored) { }
    }

    private static String bounded(String value, int max) {
        return value == null ? "" : value.substring(0, Math.min(value.length(), max));
    }

    private static final class Evidence {
        final ArrayDeque<JsonObject> events = new ArrayDeque<>(16);
        JsonArray firstCause;
        JsonArray lastCause;
        String playerId = "";
        String playerName = "";
    }

    public static boolean isFailure(Category category) {
        return switch (category) {
            case IO_FAILURE, BO_TRANSPORT_FAILURE, PROTOCOL_FAILURE, SECURITY_FAILURE,
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

        void detail(String event, JsonObject detail) {
            detail.addProperty("v", 1);
            detail.addProperty("prefix", "[BO.diag]");
            detail.addProperty("domain", "connection");
            detail.addProperty("event", event);
            detail.addProperty("report_started_at_ms", startedAt);
            detail.addProperty("at_ms", System.currentTimeMillis());
            writer.offer(detail.toString());
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
                summary.addProperty("detail_suppressed_process_count", suppressedDetails.get());
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
