package com.PinkCats.bandwidthoptimizer.channel;

import com.PinkCats.bandwidthoptimizer.Bandwidthoptimizer;
import com.PinkCats.bandwidthoptimizer.debug.DiagnosticLog;
import com.PinkCats.bandwidthoptimizer.debug.DiagnosticToolRegistry;
import io.netty.channel.Channel;
import io.netty.util.AttributeKey;

import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;

public final class ChannelTransportStreamingEpochCoordinator {

    private static final long ACK_TIMEOUT_MILLIS = 5_000L;
    private static final long FALLBACK_WARN_INTERVAL_MILLIS = 60_000L;
    private static final AttributeKey<State> STATE_KEY =
            AttributeKey.valueOf("bandwidthoptimizer:streaming_epoch_coordinator");
    private static final AtomicLong NEXT_FALLBACK_WARN_AT_MILLIS = new AtomicLong();
    private static final AtomicLong SUPPRESSED_FALLBACK_WARNINGS = new AtomicLong();

    private ChannelTransportStreamingEpochCoordinator() {
    }

    public static void boundaryQueued(Channel channel) {
        if (channel == null) {
            return;
        }
        ChannelTransportSession session = ChannelTransportStateManager.getOrCreateSession(channel);
        ChannelTransportSession.StreamingEpochBoundary boundary = session.outboundStreamingEpochBoundary();
        if (boundary == null) {
            return;
        }
        State state = state(channel);
        state.arm(channel, session, boundary);
        state.boundaryCommitted(channel, boundary.epoch(), boundary.lastSequence());
    }

    public static void boundaryReceived(Channel channel, int epoch, int lastSequence) {
        log("boundary-receive", channel, epoch, lastSequence, "");
    }

    public static void acknowledgementSent(Channel channel, int epoch, int lastSequence) {
        log("ack-send", channel, epoch, lastSequence, "");
    }

    public static void acknowledgementReceived(Channel channel, int epoch, int lastSequence) {
        log("ack-receive", channel, epoch, lastSequence, "");
    }

    public static boolean consumeLateAcknowledgement(
            Channel channel,
            ChannelTransportSession session,
            int epoch,
            int lastSequence
    ) {
        State state = channel == null ? null : channel.attr(STATE_KEY).get();
        return state != null && session != null
                && state.consumeLateAcknowledgement(channel, session, epoch, lastSequence);
    }

    public static void releasedByAcknowledgement(Channel channel, int epoch, int lastSequence) {
        State state = channel == null ? null : channel.attr(STATE_KEY).get();
        if (state != null) {
            state.releasedByAcknowledgement(channel, epoch, lastSequence);
        }
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

    private static void log(String event, Channel channel, int epoch, int sequence, String detail) {
        if (!DiagnosticToolRegistry.isEnabled(DiagnosticToolRegistry.Tool.STREAMING_EPOCH)) {
            return;
        }
        DiagnosticLog.info(
                DiagnosticToolRegistry.Tool.STREAMING_EPOCH,
                "event={} epoch={} sequence={} channel={}{}",
                event,
                epoch,
                sequence,
                ChannelIdentity.shortText(channel),
                detail == null || detail.isBlank() ? "" : " " + detail
        );
    }

    private static final class State {
        private int epoch;
        private int lastSequence;
        private int fallbackEpoch;
        private int fallbackLastSequence;
        private boolean active;
        private boolean boundarySent;
        private boolean gateReleased;

        private void arm(
                Channel channel,
                ChannelTransportSession session,
                ChannelTransportSession.StreamingEpochBoundary boundary
        ) {
            synchronized (this) {
                if (this.active && this.epoch == boundary.epoch() && this.lastSequence == boundary.lastSequence()) {
                    return;
                }
                this.epoch = boundary.epoch();
                this.lastSequence = boundary.lastSequence();
                this.active = true;
                this.boundarySent = false;
                this.gateReleased = false;
            }
            log("close", channel, boundary.epoch(), boundary.lastSequence(), "");
            channel.eventLoop().schedule(
                    () -> fallback(channel, session, boundary.epoch(), boundary.lastSequence(), "ack-timeout"),
                    ACK_TIMEOUT_MILLIS,
                    TimeUnit.MILLISECONDS
            );
        }

        private void boundaryCommitted(Channel channel, int epoch, int lastSequence) {
            synchronized (this) {
                if (!this.active || this.epoch != epoch || this.lastSequence != lastSequence || this.boundarySent) {
                    return;
                }
                this.boundarySent = true;
                this.gateReleased = true;
            }
            log("boundary-send", channel, epoch, lastSequence, "");
            ChannelTransportStreamingEpochGate.release(channel);
            log("release", channel, epoch, lastSequence, "reason=boundary-commit");
        }

        private void fallback(
                Channel channel,
                ChannelTransportSession session,
                int epoch,
                int lastSequence,
                String reason
        ) {
            boolean releaseGate;
            synchronized (this) {
                if (!this.active || this.epoch != epoch || this.lastSequence != lastSequence) {
                    return;
                }
                this.active = false;
                this.fallbackEpoch = epoch;
                this.fallbackLastSequence = lastSequence;
                releaseGate = !this.gateReleased;
                this.gateReleased = true;
            }
            session.fallbackOutboundStreamingEpoch();
            warnFallback(channel, epoch, lastSequence, reason);
            if (releaseGate) {
                ChannelTransportStreamingEpochGate.release(channel);
                log("release", channel, epoch, lastSequence, "reason=" + reason);
            }
        }

        private boolean consumeLateAcknowledgement(
                Channel channel,
                ChannelTransportSession session,
                int epoch,
                int lastSequence
        ) {
            synchronized (this) {
                if (this.fallbackEpoch != epoch || this.fallbackLastSequence != lastSequence) {
                    return false;
                }
                this.fallbackEpoch = 0;
                this.fallbackLastSequence = 0;
            }
            session.restartOutboundStreamingEpoch();
            log("ack-receive", channel, epoch, lastSequence, "late=true");
            log("resume", channel, session.outboundStreamingEpoch(), 0, "reason=late-ack");
            return true;
        }

        private void releasedByAcknowledgement(Channel channel, int epoch, int lastSequence) {
            boolean releaseGate;
            synchronized (this) {
                if (!this.active || this.epoch != epoch || this.lastSequence != lastSequence) {
                    return;
                }
                this.active = false;
                releaseGate = !this.gateReleased;
                this.gateReleased = true;
            }
            if (releaseGate) {
                ChannelTransportStreamingEpochGate.release(channel);
                log("release", channel, epoch, lastSequence, "reason=ack");
            }
        }
    }

    private static void warnFallback(Channel channel, int epoch, int lastSequence, String reason) {
        long nowMillis = System.currentTimeMillis();
        long nextWarnAt = NEXT_FALLBACK_WARN_AT_MILLIS.get();
        if (nowMillis < nextWarnAt
                || !NEXT_FALLBACK_WARN_AT_MILLIS.compareAndSet(nextWarnAt, nowMillis + FALLBACK_WARN_INTERVAL_MILLIS)) {
            SUPPRESSED_FALLBACK_WARNINGS.incrementAndGet();
            return;
        }
        long suppressed = SUPPRESSED_FALLBACK_WARNINGS.getAndSet(0L);
        Bandwidthoptimizer.LOGGER.warn(
                "[Transport][StreamingEpoch] event=fallback epoch={} sequence={} channel={} reason={} suppressed={}",
                epoch,
                lastSequence,
                ChannelIdentity.shortText(channel),
                reason,
                suppressed
        );
    }
}
