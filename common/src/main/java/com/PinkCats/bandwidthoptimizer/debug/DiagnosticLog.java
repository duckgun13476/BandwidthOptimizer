package com.PinkCats.bandwidthoptimizer.debug;

import com.PinkCats.bandwidthoptimizer.Bandwidthoptimizer;

import java.util.ArrayDeque;
import java.util.HashMap;
import java.util.Map;

public final class DiagnosticLog {

    static final int MAX_TOTAL_PER_EVENT = 100;
    static final int MAX_PER_SECOND_PER_EVENT = 20;
    private static final int MAX_EVENT_STATES = 256;
    private static final long WINDOW_MILLIS = 1_000L;
    private static final Object LIMITERS_LOCK = new Object();
    private static final Map<String, EventLimiter> LIMITERS = new HashMap<>();
    private static final EventLimiter OVERFLOW_LIMITER = new EventLimiter();

    private DiagnosticLog() {}

    public static String prefix(DiagnosticToolRegistry.Tool tool) {
        return "[BO:Diag:" + id(tool) + "]";
    }

    public static void info(DiagnosticToolRegistry.Tool tool, String message, Object... args) {
        if (!shouldEmit(tool, message, System.currentTimeMillis())) {
            return;
        }
        Bandwidthoptimizer.LOGGER.info(format(tool, message), args);
    }

    public static void warn(DiagnosticToolRegistry.Tool tool, String message, Object... args) {
        if (!shouldEmit(tool, message, System.currentTimeMillis())) {
            return;
        }
        Bandwidthoptimizer.LOGGER.warn(format(tool, message), args);
    }

    public static void error(DiagnosticToolRegistry.Tool tool, String message, Object... args) {
        if (!shouldEmit(tool, message, System.currentTimeMillis())) {
            return;
        }
        Bandwidthoptimizer.LOGGER.error(format(tool, message), args);
    }

    static boolean shouldEmitForTest(DiagnosticToolRegistry.Tool tool, String message, long nowMillis) {
        return shouldEmit(tool, message, nowMillis);
    }

    static void resetLimitersForTest() {
        synchronized (LIMITERS_LOCK) {
            LIMITERS.clear();
            OVERFLOW_LIMITER.reset();
        }
    }

    private static boolean shouldEmit(DiagnosticToolRegistry.Tool tool, String message, long nowMillis) {
        if (!DiagnosticToolRegistry.isEnabled(tool)) {
            return false;
        }
        String key = id(tool) + '/' + event(message);
        EventLimiter limiter;
        synchronized (LIMITERS_LOCK) {
            limiter = LIMITERS.get(key);
            if (limiter == null) {
                if (LIMITERS.size() >= MAX_EVENT_STATES) {
                    limiter = OVERFLOW_LIMITER;
                } else {
                    limiter = new EventLimiter();
                    LIMITERS.put(key, limiter);
                }
            }
        }
        return limiter.tryAcquire(nowMillis);
    }

    private static String event(String message) {
        if (message == null) {
            return "default";
        }
        int start = message.indexOf("event=");
        if (start < 0) {
            return "default";
        }
        start += "event=".length();
        int end = start;
        while (end < message.length()) {
            char character = message.charAt(end);
            if (!Character.isLetterOrDigit(character)
                    && character != '_'
                    && character != '-'
                    && character != '.') {
                break;
            }
            end++;
        }
        return end == start ? "default" : message.substring(start, end);
    }

    private static String format(DiagnosticToolRegistry.Tool tool, String message) {
        String safeMessage = message == null ? "" : message;
        return prefix(tool) + (safeMessage.isEmpty() ? "" : " " + safeMessage);
    }

    private static String id(DiagnosticToolRegistry.Tool tool) {
        return tool == null ? "unknown" : tool.id();
    }

    private static final class EventLimiter {
        private final ArrayDeque<Long> recentEmissions = new ArrayDeque<>(MAX_PER_SECOND_PER_EVENT);
        private int totalEmissions;
        private long dropped;
        private long lastTimestamp = Long.MIN_VALUE;

        private synchronized boolean tryAcquire(long nowMillis) {
            if (nowMillis < this.lastTimestamp) {
                this.recentEmissions.clear();
            }
            this.lastTimestamp = nowMillis;
            while (!this.recentEmissions.isEmpty()
                    && nowMillis - this.recentEmissions.peekFirst() >= WINDOW_MILLIS) {
                this.recentEmissions.removeFirst();
            }
            if (this.totalEmissions >= MAX_TOTAL_PER_EVENT
                    || this.recentEmissions.size() >= MAX_PER_SECOND_PER_EVENT) {
                this.dropped++;
                return false;
            }
            this.recentEmissions.addLast(nowMillis);
            this.totalEmissions++;
            return true;
        }

        private synchronized void reset() {
            this.recentEmissions.clear();
            this.totalEmissions = 0;
            this.dropped = 0L;
            this.lastTimestamp = Long.MIN_VALUE;
        }
    }
}
