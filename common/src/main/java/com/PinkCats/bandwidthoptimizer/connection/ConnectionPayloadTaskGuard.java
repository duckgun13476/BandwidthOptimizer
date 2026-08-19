package com.PinkCats.bandwidthoptimizer.connection;

import com.PinkCats.bandwidthoptimizer.debug.DiagnosticLog;
import com.PinkCats.bandwidthoptimizer.debug.DiagnosticToolRegistry;
import io.netty.channel.Channel;
import io.netty.util.AttributeKey;

import java.util.Objects;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Supplier;

public final class ConnectionPayloadTaskGuard {

    private static final AttributeKey<State> STATE_KEY =
            AttributeKey.valueOf("bandwidthoptimizer:payload_task_generation");

    private ConnectionPayloadTaskGuard() {}

    public static Runnable guard(Channel channel, String payloadId, Runnable task) {
        Objects.requireNonNull(task, "task");
        Token token = capture(channel, payloadId);
        return () -> {
            if (token.isCurrent()) {
                task.run();
            } else {
                token.recordDrop();
            }
        };
    }

    public static <T> Supplier<T> guard(Channel channel, String payloadId, Supplier<T> task) {
        Objects.requireNonNull(task, "task");
        Token token = capture(channel, payloadId);
        return () -> {
            if (token.isCurrent()) {
                return task.get();
            }
            token.recordDrop();
            return null;
        };
    }

    public static void advanceProtocolGeneration(Channel channel) {
        if (channel != null) {
            state(channel).generation.incrementAndGet();
        }
    }

    public static void close(Channel channel) {
        if (channel == null) {
            return;
        }
        State state = state(channel);
        state.closed = true;
        state.generation.incrementAndGet();
    }

    static long generation(Channel channel) {
        State existing = channel == null ? null : channel.attr(STATE_KEY).get();
        return existing == null ? 0L : existing.generation.get();
    }

    static long droppedTasks(Channel channel) {
        State existing = channel == null ? null : channel.attr(STATE_KEY).get();
        return existing == null ? 0L : existing.droppedTasks.get();
    }

    private static Token capture(Channel channel, String payloadId) {
        if (channel == null) {
            return new Token(null, null, -1L, safe(payloadId));
        }
        State state = state(channel);
        return new Token(channel, state, state.generation.get(), safe(payloadId));
    }

    private static State state(Channel channel) {
        State existing = channel.attr(STATE_KEY).get();
        if (existing != null) {
            return existing;
        }
        State created = new State();
        State raced = channel.attr(STATE_KEY).setIfAbsent(created);
        return raced == null ? created : raced;
    }

    private static String safe(String value) {
        return value == null || value.isBlank() ? "<unknown>" : value;
    }

    private record Token(Channel channel, State state, long generation, String payloadId) {
        boolean isCurrent() {
            return this.channel != null
                    && this.state != null
                    && !this.state.closed
                    && this.channel.isActive()
                    && this.state.generation.get() == this.generation;
        }

        void recordDrop() {
            if (this.state == null) {
                return;
            }
            long dropped = this.state.droppedTasks.incrementAndGet();
            if (DiagnosticToolRegistry.isEnabled(DiagnosticToolRegistry.Tool.CONNECTION_CLOSE)
                    && (dropped == 1L || (dropped & (dropped - 1L)) == 0L)) {
                DiagnosticLog.info(
                        DiagnosticToolRegistry.Tool.CONNECTION_CLOSE,
                        "event=stale_payload_task_drop payload={} generation={} currentGeneration={} dropped={}",
                        this.payloadId,
                        this.generation,
                        this.state.generation.get(),
                        dropped
                );
            }
        }
    }

    private static final class State {
        private final AtomicLong generation = new AtomicLong();
        private final AtomicLong droppedTasks = new AtomicLong();
        private volatile boolean closed;
    }
}
