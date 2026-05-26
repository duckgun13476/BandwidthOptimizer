package com.PinkCats.bandwidthoptimizer.experient;

import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;

public final class ExperientClientCommandTiming {
    private static final AtomicInteger SEQUENCE = new AtomicInteger();
    private static final AtomicLong LAST_SENT_MILLIS = new AtomicLong();
    private static final AtomicReference<String> LAST_COMMAND = new AtomicReference<>("");

    private ExperientClientCommandTiming() {}

    public static int recordSent(String command, long sentMillis) {
        LAST_COMMAND.set(command == null ? "" : command);
        LAST_SENT_MILLIS.set(Math.max(sentMillis, 0L));
        return SEQUENCE.incrementAndGet();
    }

    public static int sequence() {
        return SEQUENCE.get();
    }

    public static long lastSentMillis() {
        return LAST_SENT_MILLIS.get();
    }

    public static String lastCommand() {
        return LAST_COMMAND.get();
    }

    public static long millisSinceLastSent(long now) {
        long sentMillis = LAST_SENT_MILLIS.get();
        if (sentMillis <= 0L) {
            return -1L;
        }
        return Math.max(now - sentMillis, 0L);
    }
}
