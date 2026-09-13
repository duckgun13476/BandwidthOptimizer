package com.PinkCats.bandwidthoptimizer.connection;

import com.PinkCats.bandwidthoptimizer.channel.ChannelIdentity;
import com.PinkCats.bandwidthoptimizer.debug.DiagnosticLog;
import com.PinkCats.bandwidthoptimizer.debug.DiagnosticToolRegistry;
import io.netty.channel.Channel;
import io.netty.util.AttributeKey;

public final class KeepAliveTimeoutDiagnostic {

    private static final String LEGACY_CLIENTBOUND_KEEP_ALIVE =
            "net.minecraft.network.protocol.game.ClientboundKeepAlivePacket";
    private static final String LEGACY_SERVERBOUND_KEEP_ALIVE =
            "net.minecraft.network.protocol.game.ServerboundKeepAlivePacket";
    private static final String CLIENTBOUND_KEEP_ALIVE =
            "net.minecraft.network.protocol.common.ClientboundKeepAlivePacket";
    private static final String SERVERBOUND_KEEP_ALIVE =
            "net.minecraft.network.protocol.common.ServerboundKeepAlivePacket";
    private static final AttributeKey<State> STATE_KEY =
            AttributeKey.valueOf("bandwidthoptimizer:keepalive_timeout_diagnostic");

    private KeepAliveTimeoutDiagnostic() {}

    public static void observePacket(Channel channel, Object packet, boolean outbound) {
        if (!DiagnosticToolRegistry.isEnabled(DiagnosticToolRegistry.Tool.KEEP_ALIVE_TIMEOUT)
                || channel == null
                || packet == null) {
            return;
        }
        observePacketClassEnabled(channel, packet.getClass().getName(), outbound, System.currentTimeMillis());
    }

    public static void observeReadTimeout(Channel channel) {
        if (!DiagnosticToolRegistry.isEnabled(DiagnosticToolRegistry.Tool.KEEP_ALIVE_TIMEOUT)
                || channel == null) {
            return;
        }
        Snapshot snapshot = snapshot(channel, System.currentTimeMillis());
        DiagnosticLog.info(
                DiagnosticToolRegistry.Tool.KEEP_ALIVE_TIMEOUT,
                "v=1 domain=connection event=read_timeout_candidate channel={} phase={} endpoint={} pendingChallenge={} challengeAgeMs={} lastAckAgeMs={} challenges={} acks={} exactKeepAliveBoundary={}",
                ChannelIdentity.shortText(channel),
                snapshot.phase(),
                snapshot.endpoint(),
                snapshot.pendingChallenge(),
                snapshot.challengeAgeMillis(),
                snapshot.lastAckAgeMillis(),
                snapshot.challengeCount(),
                snapshot.ackCount(),
                snapshot.keepAliveTimeoutBoundaryObserved()
        );
    }

    public static void observeVanillaKeepAliveTimeoutBoundary(
            Channel channel,
            long vanillaChallengeAgeMillis,
            boolean playPhase
    ) {
        if (!DiagnosticToolRegistry.isEnabled(DiagnosticToolRegistry.Tool.KEEP_ALIVE_TIMEOUT)
                || channel == null) {
            return;
        }
        long nowMillis = System.currentTimeMillis();
        State state = state(channel);
        state.markVanillaKeepAliveTimeout(
                vanillaChallengeAgeMillis,
                nowMillis,
                playPhase ? Phase.PLAY : Phase.CONFIGURATION
        );
        Snapshot snapshot = state.snapshot(nowMillis);
        DiagnosticLog.info(
                DiagnosticToolRegistry.Tool.KEEP_ALIVE_TIMEOUT,
                "v=1 domain=connection event=keepalive_timeout_boundary channel={} phase={} endpoint={} challengeAgeMs={} challenges={} acks={} exact=true behavior=unchanged",
                ChannelIdentity.shortText(channel),
                snapshot.phase(),
                snapshot.endpoint(),
                snapshot.challengeAgeMillis(),
                snapshot.challengeCount(),
                snapshot.ackCount()
        );
    }

    public static void observeClose(Channel channel, ConnectionDisconnectClassifier.Decision decision) {
        if (!DiagnosticToolRegistry.isEnabled(DiagnosticToolRegistry.Tool.KEEP_ALIVE_TIMEOUT)
                || channel == null) {
            return;
        }
        Snapshot snapshot = snapshot(channel, System.currentTimeMillis());
        DiagnosticLog.info(
                DiagnosticToolRegistry.Tool.KEEP_ALIVE_TIMEOUT,
                "v=1 domain=connection event=close_summary channel={} phase={} endpoint={} category={} recovery={} pendingChallenge={} challengeAgeMs={} lastAckAgeMs={} challenges={} acks={} exactKeepAliveBoundary={}",
                ChannelIdentity.shortText(channel),
                snapshot.phase(),
                snapshot.endpoint(),
                decision.category(),
                decision.recoveryPolicy(),
                snapshot.pendingChallenge(),
                snapshot.challengeAgeMillis(),
                snapshot.lastAckAgeMillis(),
                snapshot.challengeCount(),
                snapshot.ackCount(),
                snapshot.keepAliveTimeoutBoundaryObserved()
        );
    }

    static void observePacketClass(
            Channel channel,
            String packetClass,
            boolean outbound,
            long nowMillis
    ) {
        if (!DiagnosticToolRegistry.isEnabled(DiagnosticToolRegistry.Tool.KEEP_ALIVE_TIMEOUT)
                || channel == null
                || packetClass == null
                || packetClass.isEmpty()) {
            return;
        }
        observePacketClassEnabled(channel, packetClass, outbound, nowMillis);
    }

    static void observePacketClass(Channel channel, String packetClass, boolean outbound) {
        if (!DiagnosticToolRegistry.isEnabled(DiagnosticToolRegistry.Tool.KEEP_ALIVE_TIMEOUT)
                || channel == null
                || packetClass == null
                || packetClass.isEmpty()) {
            return;
        }
        observePacketClassEnabled(channel, packetClass, outbound, System.currentTimeMillis());
    }

    private static void observePacketClassEnabled(
            Channel channel,
            String packetClass,
            boolean outbound,
            long nowMillis
    ) {
        Phase phase = phase(packetClass);
        boolean clientboundKeepAlive = isClientboundKeepAlive(packetClass);
        boolean serverboundKeepAlive = isServerboundKeepAlive(packetClass);
        boolean keepAlive = clientboundKeepAlive || serverboundKeepAlive;
        State existing = channel.attr(STATE_KEY).get();
        if (existing == null && phase == Phase.UNKNOWN && !keepAlive) {
            return;
        }
        State state = existing == null ? state(channel) : existing;
        state.observePhase(phase);
        if (LEGACY_CLIENTBOUND_KEEP_ALIVE.equals(packetClass)
                || LEGACY_SERVERBOUND_KEEP_ALIVE.equals(packetClass)) {
            state.observePhase(Phase.PLAY);
        }
        if (clientboundKeepAlive) {
            state.observeChallenge(outbound, nowMillis);
            Snapshot snapshot = state.snapshot(nowMillis);
            DiagnosticLog.info(
                    DiagnosticToolRegistry.Tool.KEEP_ALIVE_TIMEOUT,
                    "v=1 domain=connection event=keepalive_challenge channel={} phase={} endpoint={} direction={} challenges={} acks={}",
                    ChannelIdentity.shortText(channel),
                    snapshot.phase(),
                    snapshot.endpoint(),
                    outbound ? "outbound" : "inbound",
                    snapshot.challengeCount(),
                    snapshot.ackCount()
            );
        } else if (serverboundKeepAlive) {
            state.observeAck(outbound, nowMillis);
            Snapshot snapshot = state.snapshot(nowMillis);
            DiagnosticLog.info(
                    DiagnosticToolRegistry.Tool.KEEP_ALIVE_TIMEOUT,
                    "v=1 domain=connection event=keepalive_ack_observed channel={} phase={} endpoint={} direction={} challengeAgeMs={} challenges={} acks={}",
                    ChannelIdentity.shortText(channel),
                    snapshot.phase(),
                    snapshot.endpoint(),
                    outbound ? "outbound" : "inbound",
                    snapshot.challengeAgeMillis(),
                    snapshot.challengeCount(),
                    snapshot.ackCount()
            );
        }
    }

    private static boolean isClientboundKeepAlive(String packetClass) {
        return CLIENTBOUND_KEEP_ALIVE.equals(packetClass)
                || LEGACY_CLIENTBOUND_KEEP_ALIVE.equals(packetClass);
    }

    private static boolean isServerboundKeepAlive(String packetClass) {
        return SERVERBOUND_KEEP_ALIVE.equals(packetClass)
                || LEGACY_SERVERBOUND_KEEP_ALIVE.equals(packetClass);
    }

    static Snapshot snapshot(Channel channel, long nowMillis) {
        State existing = channel == null ? null : channel.attr(STATE_KEY).get();
        return existing == null ? Snapshot.EMPTY : existing.snapshot(nowMillis);
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

    private static Phase phase(String packetClass) {
        if (packetClass.startsWith("net.minecraft.network.protocol.login.")) {
            return Phase.LOGIN;
        }
        if (packetClass.startsWith("net.minecraft.network.protocol.configuration.")) {
            return Phase.CONFIGURATION;
        }
        if (packetClass.startsWith("net.minecraft.network.protocol.game.")) {
            return Phase.PLAY;
        }
        return Phase.UNKNOWN;
    }

    enum Phase {
        UNKNOWN,
        LOGIN,
        CONFIGURATION,
        PLAY
    }

    enum Endpoint {
        UNKNOWN,
        CLIENT,
        SERVER
    }

    record Snapshot(
            Phase phase,
            Endpoint endpoint,
            boolean pendingChallenge,
            long challengeAgeMillis,
            long lastAckAgeMillis,
            long challengeCount,
            long ackCount,
            boolean keepAliveTimeoutBoundaryObserved
    ) {
        private static final Snapshot EMPTY = new Snapshot(
                Phase.UNKNOWN,
                Endpoint.UNKNOWN,
                false,
                0L,
                0L,
                0L,
                0L,
                false
        );
    }

    private static final class State {
        private Phase phase = Phase.UNKNOWN;
        private Endpoint endpoint = Endpoint.UNKNOWN;
        private boolean pendingChallenge;
        private long challengeAtMillis;
        private long lastAckAtMillis;
        private long challengeCount;
        private long ackCount;
        private boolean keepAliveTimeoutBoundaryObserved;

        synchronized void observePhase(Phase observedPhase) {
            if (observedPhase.ordinal() > this.phase.ordinal()) {
                this.phase = observedPhase;
            }
        }

        synchronized void observeChallenge(boolean outbound, long nowMillis) {
            this.endpoint = outbound ? Endpoint.SERVER : Endpoint.CLIENT;
            this.pendingChallenge = true;
            this.challengeAtMillis = nowMillis;
            this.challengeCount++;
        }

        synchronized void observeAck(boolean outbound, long nowMillis) {
            if (this.endpoint == Endpoint.UNKNOWN) {
                this.endpoint = outbound ? Endpoint.CLIENT : Endpoint.SERVER;
            }
            this.pendingChallenge = false;
            this.lastAckAtMillis = nowMillis;
            this.ackCount++;
        }

        synchronized void markVanillaKeepAliveTimeout(
                long vanillaChallengeAgeMillis,
                long nowMillis,
                Phase timeoutPhase
        ) {
            this.phase = timeoutPhase;
            this.endpoint = Endpoint.SERVER;
            this.pendingChallenge = true;
            if (vanillaChallengeAgeMillis >= 0L && vanillaChallengeAgeMillis <= nowMillis) {
                this.challengeAtMillis = nowMillis - vanillaChallengeAgeMillis;
            }
            this.keepAliveTimeoutBoundaryObserved = true;
        }

        synchronized Snapshot snapshot(long nowMillis) {
            return new Snapshot(
                    this.phase,
                    this.endpoint,
                    this.pendingChallenge,
                    age(nowMillis, this.challengeAtMillis),
                    age(nowMillis, this.lastAckAtMillis),
                    this.challengeCount,
                    this.ackCount,
                    this.keepAliveTimeoutBoundaryObserved
            );
        }

        private static long age(long nowMillis, long timestampMillis) {
            return timestampMillis <= 0L ? 0L : Math.max(0L, nowMillis - timestampMillis);
        }
    }
}
