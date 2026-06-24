package com.PinkCats.bandwidthoptimizer.debug;

import java.util.concurrent.TimeUnit;

public final class HotpathCostProbe {

    private static final long DEFAULT_THRESHOLD_NANOS = TimeUnit.MILLISECONDS.toNanos(50L);
    private static final int MAX_STAGES = 32;
    private static final ThreadLocal<Trace> ACTIVE_TRACE = new ThreadLocal<>();
    private static final Trace NOOP_TRACE = new Trace(null, null, false);

    private HotpathCostProbe() {}

    public static boolean isEnabled() {
        return DiagnosticToolRegistry.isEnabled(DiagnosticToolRegistry.Tool.HOTPATH_COST);
    }

    public static Trace begin(String root) {
        if (!isEnabled()) {
            return NOOP_TRACE;
        }
        Trace trace = new Trace(root, ACTIVE_TRACE.get(), true);
        ACTIVE_TRACE.set(trace);
        return trace;
    }

    public static long start() {
        return ACTIVE_TRACE.get() == null ? 0L : System.nanoTime();
    }

    public static void end(String stage, long startNanos) {
        if (startNanos == 0L) {
            return;
        }
        Trace trace = ACTIVE_TRACE.get();
        if (trace != null) {
            trace.add(stage, System.nanoTime() - startNanos);
        }
    }

    public static final class Trace implements AutoCloseable {
        private final String root;
        private final Trace previous;
        private final boolean active;
        private final long startNanos;
        private final String[] stageNames;
        private final long[] stageNanos;
        private int stageCount;
        private String detail = "";
        private boolean closed;

        private Trace(String root, Trace previous, boolean active) {
            this.root = root;
            this.previous = previous;
            this.active = active;
            this.startNanos = active ? System.nanoTime() : 0L;
            this.stageNames = active ? new String[MAX_STAGES] : null;
            this.stageNanos = active ? new long[MAX_STAGES] : null;
        }

        public boolean isActive() {
            return active;
        }

        public Trace detail(String detail) {
            if (active) {
                this.detail = detail == null ? "" : detail;
            }
            return this;
        }

        private void add(String stage, long nanos) {
            if (!active || stage == null || nanos <= 0L) {
                return;
            }
            for (int i = 0; i < stageCount; i++) {
                if (stage.equals(stageNames[i])) {
                    stageNanos[i] += nanos;
                    return;
                }
            }
            if (stageCount >= MAX_STAGES) {
                stageNanos[MAX_STAGES - 1] += nanos;
                stageNames[MAX_STAGES - 1] = "other";
                return;
            }
            stageNames[stageCount] = stage;
            stageNanos[stageCount] = nanos;
            stageCount++;
        }

        @Override
        public void close() {
            if (!active || closed) {
                return;
            }
            closed = true;
            if (previous == null) {
                ACTIVE_TRACE.remove();
            } else {
                ACTIVE_TRACE.set(previous);
            }
            long totalNanos = System.nanoTime() - startNanos;
            if (totalNanos < DEFAULT_THRESHOLD_NANOS) {
                return;
            }
            DiagnosticLog.warn(
                    DiagnosticToolRegistry.Tool.HOTPATH_COST,
                    "root={}, totalMs={}, thresholdMs={}, detail={}, stages={}",
                    root,
                    millis(totalNanos),
                    millis(DEFAULT_THRESHOLD_NANOS),
                    detail,
                    rankedStages()
            );
        }

        private String rankedStages() {
            if (stageCount == 0) {
                return "<none>";
            }
            boolean[] used = new boolean[stageCount];
            StringBuilder builder = new StringBuilder(stageCount * 24);
            for (int rank = 0; rank < stageCount; rank++) {
                int best = -1;
                long bestNanos = 0L;
                for (int i = 0; i < stageCount; i++) {
                    if (!used[i] && stageNanos[i] > bestNanos) {
                        best = i;
                        bestNanos = stageNanos[i];
                    }
                }
                if (best < 0) {
                    break;
                }
                used[best] = true;
                if (builder.length() > 0) {
                    builder.append(", ");
                }
                builder.append(stageNames[best]).append('=').append(millis(bestNanos)).append("ms");
            }
            return builder.toString();
        }

        private static String millis(long nanos) {
            return String.format(java.util.Locale.ROOT, "%.2f", nanos / 1_000_000.0D);
        }
    }
}
