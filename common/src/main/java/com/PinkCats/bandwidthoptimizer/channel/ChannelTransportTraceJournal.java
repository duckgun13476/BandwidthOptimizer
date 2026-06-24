package com.PinkCats.bandwidthoptimizer.channel;

import com.PinkCats.bandwidthoptimizer.Bandwidthoptimizer;
import com.PinkCats.bandwidthoptimizer.Config;
import com.PinkCats.bandwidthoptimizer.debug.DiagnosticToolRegistry;
import io.netty.channel.Channel;
import io.netty.channel.ChannelHandlerContext;
import io.netty.util.AttributeKey;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.Deque;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

public final class ChannelTransportTraceJournal {

    private static final AttributeKey<TraceJournal> TRACE_JOURNAL_KEY =
            AttributeKey.valueOf("bandwidthoptimizer:transport_trace_journal");
    private static final int MAX_COUNT_LINES = 32;
    private static final int MAX_EVENT_TEXT_LENGTH = 512;

    private ChannelTransportTraceJournal() {
    }


    public static void record(ChannelHandlerContext context, String kind, String countKey, String eventText) {
        if (!isEnabled() || context == null || context.channel() == null) {
            return;
        }
        int capacity = closeDumpSize();
        if (capacity <= 0) {
            return;
        }
        TraceJournal journal = getOrCreateJournal(context.channel());
        journal.record(kind, countKey, eventText, capacity);
    }


    // transport trace
    public static void dumpAndClear(Channel channel, String reason, Throwable throwable) {
        if (channel == null) {
            return;
        }
        if (!isEnabled()) {
            channel.attr(TRACE_JOURNAL_KEY).set(null);
            return;
        }
        TraceJournal journal = channel.attr(TRACE_JOURNAL_KEY).getAndSet(null);
        if (journal == null) {
            return;
        }
        journal.dump(com.PinkCats.bandwidthoptimizer.channel.ChannelIdentity.shortText(channel), reason, throwable);
    }

    private static TraceJournal getOrCreateJournal(Channel channel) {
        TraceJournal existingJournal = channel.attr(TRACE_JOURNAL_KEY).get();
        if (existingJournal != null) {
            return existingJournal;
        }
        TraceJournal newJournal = new TraceJournal();
        TraceJournal racedJournal = channel.attr(TRACE_JOURNAL_KEY).setIfAbsent(newJournal);
        return racedJournal == null ? newJournal : racedJournal;
    }

    private static int closeDumpSize() {
        String rawValue = System.getProperty(Config.RuntimeProperty.Transport.DEBUG_TRACE_CLOSE_DUMP_SIZE);
        if (rawValue == null || rawValue.isBlank()) {
            return Config.RuntimeProperty.Transport.DEFAULT_DEBUG_TRACE_CLOSE_DUMP_SIZE;
        }
        try {
            return Math.max(Integer.parseInt(rawValue.trim()), 0);
        } catch (NumberFormatException ignored) {
            return Config.RuntimeProperty.Transport.DEFAULT_DEBUG_TRACE_CLOSE_DUMP_SIZE;
        }
    }

    private static boolean isEnabled() {
        return DiagnosticToolRegistry.isEnabled(DiagnosticToolRegistry.Tool.TRANSPORT_TRACE_JOURNAL);
    }

    private static String trimToLogLine(String value) {
        String safeValue = value == null ? "<null>" : value;
        if (safeValue.length() <= MAX_EVENT_TEXT_LENGTH) {
            return safeValue;
        }
        return safeValue.substring(0, MAX_EVENT_TEXT_LENGTH) + "...";
    }

    private static final class TraceJournal {

        private final Deque<String> recentEvents = new ArrayDeque<>();
        private final Map<String, Long> counters = new LinkedHashMap<>();
        private long recordedEvents;

        private synchronized void record(String kind, String countKey, String eventText, int capacity) {
            String safeKind = kind == null || kind.isBlank() ? "unknown" : kind;
            String safeCountKey = countKey == null || countKey.isBlank() ? safeKind : countKey;
            this.recordedEvents++;
            this.counters.merge(safeCountKey, 1L, Long::sum);
            this.recentEvents.addLast(this.recordedEvents + " " + safeKind + " " + trimToLogLine(eventText));
            while (this.recentEvents.size() > capacity) {
                this.recentEvents.removeFirst();
            }
        }

        private synchronized void dump(String channelId, String reason, Throwable throwable) {
            Bandwidthoptimizer.LOGGER.info(
                    "[BO:Diag:transportTraceJournal] event=close_dump, channel={}, reason={}, recordedEvents={}, recentEvents={}, countKeys={}",
                    channelId,
                    reason,
                    this.recordedEvents,
                    this.recentEvents.size(),
                    this.counters.size()
            );
            if (throwable != null) {
                Bandwidthoptimizer.LOGGER.warn(
                        "[BO:Diag:transportTraceJournal] event=close_dump_exception, channel={}, reason={}",
                        channelId,
                        reason,
                        throwable
                );
            }
            List<Map.Entry<String, Long>> sortedCounters = new ArrayList<>(this.counters.entrySet());
            sortedCounters.sort(Comparator.comparingLong((Map.Entry<String, Long> entry) -> entry.getValue()).reversed());
            int countLines = Math.min(sortedCounters.size(), MAX_COUNT_LINES);
            for (int index = 0; index < countLines; index++) {
                Map.Entry<String, Long> entry = sortedCounters.get(index);
                Bandwidthoptimizer.LOGGER.info(
                        "[BO:Diag:transportTraceJournal] event=close_dump_count, channel={}, key={}, count={}",
                        channelId,
                        entry.getKey(),
                        entry.getValue()
                );
            }
            for (String event : this.recentEvents) {
                Bandwidthoptimizer.LOGGER.info(
                        "[BO:Diag:transportTraceJournal] event=close_dump_event, channel={}, {}",
                        channelId,
                        event
                );
            }
        }
    }
}
