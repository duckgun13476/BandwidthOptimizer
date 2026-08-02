package com.PinkCats.bandwidthoptimizer.debug;

import com.PinkCats.bandwidthoptimizer.Bandwidthoptimizer;
import com.PinkCats.bandwidthoptimizer.util.BandwidthOptimizerOutputPaths;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;

public final class CompatibilityIssueReporter {

    private static final int MAX_CONSOLE_REPORTS = 10;
    private static final String REPORT_DIRECTORY = "diagnostics/compatibility-issues";
    private static final String HISTORY_FILE = "issues.jsonl";
    private static final String SUMMARY_FILE = "summary.jsonl";
    private static final ConcurrentHashMap<String, IssueState> ISSUES = new ConcurrentHashMap<>();
    private static final Object WRITE_LOCK = new Object();

    private CompatibilityIssueReporter() {}

    public static void report(String issueKey, Throwable throwable, String context) {
        String safeKey = safeText(issueKey, "unknown");
        IssueState state = ISSUES.computeIfAbsent(safeKey, ignored -> new IssueState());
        long occurrence = state.occurrences.incrementAndGet();
        if (occurrence <= MAX_CONSOLE_REPORTS) {
            writeHistory(safeKey, occurrence, throwable, context, false);
            Bandwidthoptimizer.LOGGER.warn(
                    "[BO:Compat] issue={} occurrence={}/{} context={} error={}: {}",
                    safeKey,
                    occurrence,
                    MAX_CONSOLE_REPORTS,
                    safeText(context, ""),
                    throwable == null ? "<none>" : throwable.getClass().getName(),
                    throwable == null ? "" : safeText(throwable.getMessage(), "")
            );
            return;
        }
        if (occurrence == MAX_CONSOLE_REPORTS + 1L || isPowerOfTwo(occurrence)) {
            writeHistory(safeKey, occurrence, throwable, context, true);
        }
    }

    private static void writeHistory(
            String issueKey,
            long occurrence,
            Throwable throwable,
            String context,
            boolean summary
    ) {
        Path target = BandwidthOptimizerOutputPaths.resolve(
                REPORT_DIRECTORY,
                summary ? SUMMARY_FILE : HISTORY_FILE
        );
        String json = "{"
                + "\"recorded_at_ms\":" + System.currentTimeMillis() + ','
                + "\"issue\":\"" + escapeJson(issueKey) + "\","
                + "\"occurrence\":" + occurrence + ','
                + "\"console_suppressed\":" + summary + ','
                + "\"context\":\"" + escapeJson(context) + "\","
                + "\"error_class\":\"" + escapeJson(throwable == null ? "" : throwable.getClass().getName()) + "\","
                + "\"error_message\":\"" + escapeJson(throwable == null ? "" : throwable.getMessage()) + "\""
                + '}';
        try {
            Path parent = target.getParent();
            if (parent != null) {
                Files.createDirectories(parent);
            }
            synchronized (WRITE_LOCK) {
                Files.writeString(
                        target,
                        json + System.lineSeparator(),
                        StandardCharsets.UTF_8,
                        StandardOpenOption.CREATE,
                        StandardOpenOption.APPEND,
                        StandardOpenOption.WRITE
                );
            }
        } catch (IOException ignored) {
            // Compatibility diagnostics must never introduce a new hot-path failure.
        }
    }

    private static boolean isPowerOfTwo(long value) {
        return value > 0L && (value & (value - 1L)) == 0L;
    }

    private static String safeText(String value, String fallback) {
        return value == null ? fallback : value;
    }

    private static String escapeJson(String value) {
        String safeValue = safeText(value, "");
        StringBuilder builder = new StringBuilder(safeValue.length() + 16);
        for (int index = 0; index < safeValue.length(); index++) {
            char current = safeValue.charAt(index);
            switch (current) {
                case '\\' -> builder.append("\\\\");
                case '"' -> builder.append("\\\"");
                case '\r' -> builder.append("\\r");
                case '\n' -> builder.append("\\n");
                case '\t' -> builder.append("\\t");
                default -> {
                    if (current < 32) {
                        builder.append(String.format("\\u%04x", (int) current));
                    } else {
                        builder.append(current);
                    }
                }
            }
        }
        return builder.toString();
    }

    private static final class IssueState {
        private final AtomicLong occurrences = new AtomicLong();
    }
}
