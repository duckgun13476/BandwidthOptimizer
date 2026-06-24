package com.PinkCats.bandwidthoptimizer.channel.debug;

import com.PinkCats.bandwidthoptimizer.Bandwidthoptimizer;
import com.PinkCats.bandwidthoptimizer.channel.ChannelIdentity;
import com.PinkCats.bandwidthoptimizer.debug.DiagnosticToolRegistry;
import io.netty.channel.Channel;
import io.netty.channel.ChannelHandlerContext;
import io.netty.util.AttributeKey;

import java.util.StringJoiner;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;

public final class NettySpikeProbe {

    private static final String THRESHOLD_MILLIS_PROPERTY = "bandwidthoptimizer.netty.spikeThresholdMillis";
    private static final String WATCHDOG_INTERVAL_MILLIS_PROPERTY = "bandwidthoptimizer.netty.watchdogIntervalMillis";
    private static final String LOG_COOLDOWN_MILLIS_PROPERTY = "bandwidthoptimizer.netty.spikeLogCooldownMillis";
    private static final long DEFAULT_THRESHOLD_MILLIS = 1_000L;
    private static final long DEFAULT_WATCHDOG_INTERVAL_MILLIS = 500L;
    private static final long DEFAULT_LOG_COOLDOWN_MILLIS = 30_000L;
    private static final long THRESHOLD_MILLIS =
            readLong(THRESHOLD_MILLIS_PROPERTY, DEFAULT_THRESHOLD_MILLIS, 1L, 60_000L);
    private static final long WATCHDOG_INTERVAL_MILLIS =
            readLong(WATCHDOG_INTERVAL_MILLIS_PROPERTY, DEFAULT_WATCHDOG_INTERVAL_MILLIS, 100L, 10_000L);
    private static final long LOG_COOLDOWN_MILLIS =
            readLong(LOG_COOLDOWN_MILLIS_PROPERTY, DEFAULT_LOG_COOLDOWN_MILLIS, 1_000L, 300_000L);
    private static final int RECENT_OPERATION_LIMIT = 12;
    private static final AttributeKey<ProbeState> PROBE_STATE_KEY =
            AttributeKey.valueOf("bandwidthoptimizer:netty_spike_probe_state");

    private NettySpikeProbe() {}

    public static boolean isEnabled() {
        return DiagnosticToolRegistry.isEnabled(DiagnosticToolRegistry.Tool.NETTY_MSPT);
    }

    public static void BO_Diag_nettyMSPT(ChannelHandlerContext context) {
        ensureWatchdog(context);
    }

    public static long BO_Diag_nettyMSPT_timer(ChannelHandlerContext context, String operation, String detail) {
        return beginOperation(context, operation, detail);
    }

    public static void BO_Diag_nettyMSPT_timer(
            ChannelHandlerContext context,
            String operation,
            long startNanos,
            String detail
    ) {
        finishOperation(context, operation, startNanos, detail);
    }

    // Starts the per-channel event-loop watchdog.
    public static void ensureWatchdog(ChannelHandlerContext context) {
        if (!isEnabled() || context == null || context.channel() == null) {
            return;
        }
        ProbeState state = getOrCreateState(context.channel());
        state.ensureWatchdog(context.channel());
    }

    // Marks the active BO Netty stage for spike attribution.
    public static long beginOperation(ChannelHandlerContext context, String operation, String detail) {
        if (!isEnabled() || context == null || context.channel() == null) {
            return 0L;
        }
        ensureWatchdog(context);
        long nowNanos = System.nanoTime();
        getOrCreateState(context.channel()).recordStart(nowNanos, safeText(operation), safeText(detail));
        return nowNanos;
    }

    // Logs slow BO operations above the configured threshold.
    public static void finishOperation(ChannelHandlerContext context, String operation, long startNanos, String detail) {
        if (!isEnabled() || context == null || context.channel() == null || startNanos <= 0L) {
            return;
        }
        long nowNanos = System.nanoTime();
        long durationMillis = TimeUnit.NANOSECONDS.toMillis(Math.max(nowNanos - startNanos, 0L));
        ProbeState state = getOrCreateState(context.channel());
        state.recordFinish(nowNanos, safeText(operation), safeText(detail), durationMillis);
        if (durationMillis >= THRESHOLD_MILLIS) {
            state.operationSlowCounter.incrementAndGet();
            state.logSpike(context.channel(), "operation_slow", durationMillis, safeText(operation));
        }
    }

    private static ProbeState getOrCreateState(Channel channel) {
        ProbeState existingState = channel.attr(PROBE_STATE_KEY).get();
        if (existingState != null) {
            return existingState;
        }
        ProbeState newState = new ProbeState();
        ProbeState racedState = channel.attr(PROBE_STATE_KEY).setIfAbsent(newState);
        ProbeState resolvedState = racedState == null ? newState : racedState;
        if (racedState == null) {
            channel.closeFuture().addListener(future -> {
                resolvedState.close();
                channel.attr(PROBE_STATE_KEY).set(null);
            });
        }
        return resolvedState;
    }

    private static long readLong(String property, long defaultValue, long minValue, long maxValue) {
        String rawValue = System.getProperty(property);
        if (rawValue == null || rawValue.isBlank()) {
            return defaultValue;
        }
        try {
            long parsedValue = Long.parseLong(rawValue.trim());
            return Math.max(minValue, Math.min(maxValue, parsedValue));
        } catch (NumberFormatException ignored) {
            return defaultValue;
        }
    }

    private static String safeText(String text) {
        if (text == null || text.isBlank()) {
            return "<none>";
        }
        return text.length() <= 160 ? text : text.substring(0, 160);
    }

    private static final class ProbeState {

        private final String[] recentOperations = new String[RECENT_OPERATION_LIMIT];
        private final AtomicLong operationCounter = new AtomicLong();
        private final AtomicLong eventLoopDelayCounter = new AtomicLong();
        private final AtomicLong operationSlowCounter = new AtomicLong();
        private volatile boolean closed;
        private volatile boolean watchdogScheduled;
        private volatile long expectedWatchdogAtNanos;
        private volatile long activeOperationStartedAtNanos;
        private volatile String activeOperationName = "<none>";
        private volatile String activeOperationDetail = "<none>";
        private volatile long lastCompletedOperationAtMillis;
        private volatile String lastCompletedOperationName = "<none>";
        private volatile String lastCompletedOperationDetail = "<none>";
        private volatile long lastSpikeLoggedAtMillis;
        private int recentOperationCursor;

        private synchronized void ensureWatchdog(Channel channel) {
            if (this.closed || this.watchdogScheduled || channel == null || !channel.isOpen()) {
                return;
            }
            this.watchdogScheduled = true;
            scheduleNextWatchdog(channel);
        }

        private void scheduleNextWatchdog(Channel channel) {
            if (this.closed || channel == null || !channel.isOpen()) {
                this.watchdogScheduled = false;
                return;
            }
            this.expectedWatchdogAtNanos = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(WATCHDOG_INTERVAL_MILLIS);
            channel.eventLoop().schedule(() -> runWatchdog(channel), WATCHDOG_INTERVAL_MILLIS, TimeUnit.MILLISECONDS);
        }

        private void runWatchdog(Channel channel) {
            if (this.closed || channel == null || !channel.isOpen()) {
                this.watchdogScheduled = false;
                return;
            }
            long nowNanos = System.nanoTime();
            long delayMillis = TimeUnit.NANOSECONDS.toMillis(Math.max(nowNanos - this.expectedWatchdogAtNanos, 0L));
            if (delayMillis >= THRESHOLD_MILLIS) {
                this.eventLoopDelayCounter.incrementAndGet();
                logSpike(channel, "event_loop_delay", delayMillis, "watchdog");
            }
            scheduleNextWatchdog(channel);
        }

        private void recordStart(long nowNanos, String operation, String detail) {
            this.operationCounter.incrementAndGet();
            this.activeOperationStartedAtNanos = nowNanos;
            this.activeOperationName = operation;
            this.activeOperationDetail = detail;
        }

        private void recordFinish(long nowNanos, String operation, String detail, long durationMillis) {
            if (durationMillis >= 50L) {
                addRecentOperation(nowNanos, operation, detail, durationMillis);
            }
            this.lastCompletedOperationName = operation;
            this.lastCompletedOperationDetail = detail;
            this.lastCompletedOperationAtMillis = System.currentTimeMillis();
            if (operation.equals(this.activeOperationName)) {
                this.activeOperationStartedAtNanos = 0L;
                this.activeOperationName = "<none>";
                this.activeOperationDetail = "<none>";
            }
        }

        private synchronized void addRecentOperation(long nowNanos, String operation, String detail, long durationMillis) {
            String eventText = operation
                    + " durationMs=" + durationMillis
                    + " atMs=" + System.currentTimeMillis()
                    + " detail=" + detail;
            this.recentOperations[this.recentOperationCursor] = eventText;
            this.recentOperationCursor = (this.recentOperationCursor + 1) % RECENT_OPERATION_LIMIT;
        }

        private void logSpike(Channel channel, String type, long delayMillis, String operation) {
            long nowMillis = System.currentTimeMillis();
            long lastLoggedAt = this.lastSpikeLoggedAtMillis;
            if (nowMillis - lastLoggedAt < LOG_COOLDOWN_MILLIS) {
                return;
            }
            this.lastSpikeLoggedAtMillis = nowMillis;
            boolean boActive = this.activeOperationStartedAtNanos > 0L;
            long activeForMillis = !boActive
                    ? -1L
                    : TimeUnit.NANOSECONDS.toMillis(Math.max(System.nanoTime() - this.activeOperationStartedAtNanos, 0L));
            long lastCompletedAgoMillis = this.lastCompletedOperationAtMillis <= 0L
                    ? -1L
                    : Math.max(nowMillis - this.lastCompletedOperationAtMillis, 0L);
            Bandwidthoptimizer.LOGGER.warn(
                    "[BO:Diag:nettyMSPT] type={}, boActive={}, delayMs={}, thresholdMs={}, channel={}, eventLoop={}, operation={}, activeOperation={}, activeForMs={}, activeDetail={}, lastCompletedOperation={}, lastCompletedAgoMs={}, lastCompletedDetail={}, totalOperations={}, eventLoopDelays={}, operationSlows={}, recent={}",
                    type,
                    boActive,
                    delayMillis,
                    THRESHOLD_MILLIS,
                    ChannelIdentity.longText(channel),
                    channel == null || channel.eventLoop() == null ? "<unknown>" : channel.eventLoop().toString(),
                    operation,
                    this.activeOperationName,
                    activeForMillis,
                    this.activeOperationDetail,
                    this.lastCompletedOperationName,
                    lastCompletedAgoMillis,
                    this.lastCompletedOperationDetail,
                    this.operationCounter.get(),
                    this.eventLoopDelayCounter.get(),
                    this.operationSlowCounter.get(),
                    recentOperationsText()
            );
        }

        private synchronized String recentOperationsText() {
            StringJoiner joiner = new StringJoiner(" | ");
            for (int i = 0; i < RECENT_OPERATION_LIMIT; i++) {
                int index = (this.recentOperationCursor + i) % RECENT_OPERATION_LIMIT;
                String eventText = this.recentOperations[index];
                if (eventText != null && !eventText.isBlank()) {
                    joiner.add(eventText);
                }
            }
            String joined = joiner.toString();
            return joined.isBlank() ? "<empty>" : joined;
        }

        private void close() {
            this.closed = true;
            this.watchdogScheduled = false;
        }
    }
}
