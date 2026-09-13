package com.PinkCats.bandwidthoptimizer.connection;

import com.PinkCats.bandwidthoptimizer.channel.ChannelIdentity;
import com.PinkCats.bandwidthoptimizer.debug.DiagnosticLog;
import com.PinkCats.bandwidthoptimizer.debug.DiagnosticToolRegistry;
import io.netty.channel.Channel;
import io.netty.util.AttributeKey;

import java.util.concurrent.TimeUnit;

public final class KeepAliveGraceController {

    private static final long INITIAL_GRACE_NANOS = TimeUnit.SECONDS.toNanos(15L);
    private static final long ABSOLUTE_GRACE_NANOS = TimeUnit.SECONDS.toNanos(75L);
    private static final long PROBE_INTERVAL_MILLIS = 3_000L;
    private static final long PROBE_FRESH_NANOS = TimeUnit.SECONDS.toNanos(7L);
    private static final boolean ENABLED = Boolean.parseBoolean(
            System.getProperty("bandwidthoptimizer.keepAliveGrace", "true")
    );
    private static final AttributeKey<State> STATE_KEY =
            AttributeKey.valueOf("bandwidthoptimizer:keepalive_grace");

    private KeepAliveGraceController() {
    }

    public static boolean shouldDeferTimeout(Channel channel, boolean playPhase, ProbeSender sender) {
        return shouldDeferTimeout(channel, playPhase, sender, System.nanoTime(), true);
    }

    static boolean shouldDeferTimeout(
            Channel channel,
            boolean playPhase,
            ProbeSender sender,
            long nowNanos,
            boolean scheduleProbes
    ) {
        if (!ENABLED || !playPhase || channel == null || sender == null || !channel.isActive()) {
            return false;
        }
        State state = state(channel);
        Decision decision = state.beginOrEvaluate(sender, nowNanos);
        if (decision.started()) {
            log("grace_start", channel, "initialGraceMs=15000 absoluteGraceMs=75000");
            if (scheduleProbes) {
                scheduleProbe(channel, state, decision.generation(), 0L);
            }
        }
        if (!decision.defer()) {
            log("grace_expired", channel, "reason=" + decision.reason());
        }
        return decision.defer();
    }

    public static void observeVanillaKeepAliveAck(Channel channel, String packetClass, boolean outbound) {
        if (!ENABLED || channel == null || outbound || !isServerboundKeepAlive(packetClass)) {
            return;
        }
        State state = channel.attr(STATE_KEY).get();
        if (state != null && state.completeByVanillaAck()) {
            log("grace_release", channel, "reason=vanilla_ack");
        }
    }

    public static void observeProbePong(Channel channel, long nonce) {
        observeProbePong(channel, nonce, System.nanoTime());
    }

    static void observeProbePong(Channel channel, long nonce, long nowNanos) {
        if (!ENABLED || channel == null || nonce <= 0L) {
            return;
        }
        State state = channel.attr(STATE_KEY).get();
        if (state != null && state.acceptPong(nonce, nowNanos)) {
            log("probe_pong", channel, "nonce=" + nonce);
        }
    }

    static long issueProbeForTest(Channel channel, long nowNanos) {
        State state = channel == null ? null : channel.attr(STATE_KEY).get();
        Probe probe = state == null ? null : state.nextProbeForCurrentGeneration(nowNanos);
        if (probe == null) {
            return 0L;
        }
        probe.sender().send(probe.nonce());
        return probe.nonce();
    }

    static Snapshot snapshot(Channel channel, long nowNanos) {
        State state = channel == null ? null : channel.attr(STATE_KEY).get();
        return state == null ? Snapshot.EMPTY : state.snapshot(nowNanos);
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

    private static void scheduleProbe(Channel channel, State state, long generation, long delayMillis) {
        channel.eventLoop().schedule(() -> {
            Probe probe = state.nextProbe(generation, System.nanoTime());
            if (probe == null || !channel.isActive()) {
                return;
            }
            boolean submitted = probe.sender().send(probe.nonce());
            log("probe_send", channel, "nonce=" + probe.nonce() + " submitted=" + submitted);
            scheduleProbe(channel, state, generation, PROBE_INTERVAL_MILLIS);
        }, delayMillis, TimeUnit.MILLISECONDS);
    }

    private static boolean isServerboundKeepAlive(String packetClass) {
        return "net.minecraft.network.protocol.game.ServerboundKeepAlivePacket".equals(packetClass)
                || "net.minecraft.network.protocol.common.ServerboundKeepAlivePacket".equals(packetClass);
    }

    private static void log(String event, Channel channel, String detail) {
        if (!DiagnosticToolRegistry.isEnabled(DiagnosticToolRegistry.Tool.KEEP_ALIVE_TIMEOUT)) {
            return;
        }
        DiagnosticLog.info(
                DiagnosticToolRegistry.Tool.KEEP_ALIVE_TIMEOUT,
                "v=1 domain=connection event={} channel={} {}",
                event,
                ChannelIdentity.shortText(channel),
                detail
        );
    }

    @FunctionalInterface
    public interface ProbeSender {
        boolean send(long nonce);
    }

    record Snapshot(boolean active, long elapsedMillis, long lastPongAgeMillis, long lastSentNonce) {
        private static final Snapshot EMPTY = new Snapshot(false, 0L, 0L, 0L);
    }

    private record Decision(boolean defer, boolean started, long generation, String reason) {
    }

    private record Probe(long nonce, ProbeSender sender) {
    }

    private static final class State {
        private boolean active;
        private long startedAtNanos;
        private long lastPongAtNanos;
        private long lastSentNonce;
        private long lastAcceptedNonce;
        private long generation;
        private ProbeSender sender;
        private boolean expired;

        synchronized Decision beginOrEvaluate(ProbeSender sender, long nowNanos) {
            boolean started = false;
            if (this.expired) {
                return new Decision(false, false, this.generation, "expired");
            }
            if (!this.active) {
                this.active = true;
                this.startedAtNanos = nowNanos;
                this.lastPongAtNanos = 0L;
                this.lastSentNonce = 0L;
                this.lastAcceptedNonce = 0L;
                this.sender = sender;
                this.generation++;
                started = true;
            }
            long elapsed = Math.max(0L, nowNanos - this.startedAtNanos);
            if (elapsed >= ABSOLUTE_GRACE_NANOS) {
                this.active = false;
                this.sender = null;
                this.expired = true;
                return new Decision(false, started, this.generation, "absolute_limit");
            }
            boolean initialGrace = elapsed < INITIAL_GRACE_NANOS;
            boolean freshPong = this.lastPongAtNanos >= this.startedAtNanos
                    && nowNanos - this.lastPongAtNanos <= PROBE_FRESH_NANOS;
            if (!initialGrace && !freshPong) {
                this.active = false;
                this.sender = null;
                this.expired = true;
                return new Decision(false, started, this.generation, "probe_stale");
            }
            return new Decision(true, started, this.generation, initialGrace ? "initial" : "probe_alive");
        }

        synchronized Probe nextProbe(long expectedGeneration, long nowNanos) {
            if (!this.active || this.generation != expectedGeneration || this.sender == null
                    || nowNanos - this.startedAtNanos >= ABSOLUTE_GRACE_NANOS) {
                return null;
            }
            this.lastSentNonce = this.lastSentNonce == Long.MAX_VALUE ? 1L : this.lastSentNonce + 1L;
            return new Probe(this.lastSentNonce, this.sender);
        }

        synchronized Probe nextProbeForCurrentGeneration(long nowNanos) {
            return nextProbe(this.generation, nowNanos);
        }

        synchronized boolean acceptPong(long nonce, long nowNanos) {
            if (!this.active || nonce <= this.lastAcceptedNonce || nonce > this.lastSentNonce) {
                return false;
            }
            this.lastAcceptedNonce = nonce;
            this.lastPongAtNanos = nowNanos;
            return true;
        }

        synchronized boolean completeByVanillaAck() {
            if (!this.active) {
                return false;
            }
            this.active = false;
            this.sender = null;
            this.expired = false;
            return true;
        }

        synchronized Snapshot snapshot(long nowNanos) {
            long elapsed = this.startedAtNanos == 0L ? 0L : Math.max(0L, nowNanos - this.startedAtNanos);
            long pongAge = this.lastPongAtNanos == 0L ? 0L : Math.max(0L, nowNanos - this.lastPongAtNanos);
            return new Snapshot(
                    this.active,
                    TimeUnit.NANOSECONDS.toMillis(elapsed),
                    TimeUnit.NANOSECONDS.toMillis(pongAge),
                    this.lastSentNonce
            );
        }
    }
}
