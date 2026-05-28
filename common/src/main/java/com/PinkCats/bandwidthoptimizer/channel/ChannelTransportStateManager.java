package com.PinkCats.bandwidthoptimizer.channel;

import com.PinkCats.bandwidthoptimizer.Bandwidthoptimizer;
import io.netty.channel.Channel;
import io.netty.util.AttributeKey;

import java.util.concurrent.TimeUnit;

public final class ChannelTransportStateManager {

    private static final AttributeKey<ChannelTransportSession> TRANSPORT_SESSION_KEY =
            AttributeKey.valueOf("bandwidthoptimizer:channel_transport_session");

    private static final AttributeKey<Boolean> SESSION_CLOSE_CLEANUP_ATTACHED_KEY =
            AttributeKey.valueOf("bandwidthoptimizer:channel_transport_session_close_cleanup_attached");

    private static final AttributeKey<ProxyServerSwitchBoundaryState> PROXY_SERVER_SWITCH_BOUNDARY_KEY =
            AttributeKey.valueOf("bandwidthoptimizer:proxy_server_switch_boundary");

    private ChannelTransportStateManager() {}

    public static ChannelTransportSession getOrCreateSession(Channel channel) {
        if (channel == null) {
            throw new IllegalArgumentException("channel");
        }

        ensureSessionCloseCleanup(channel);
        ChannelTransportSession existingSession = channel.attr(TRANSPORT_SESSION_KEY).get();

        if (existingSession != null)
            return existingSession;

        ChannelTransportSession newSession = new ChannelTransportSession();
        ChannelTransportSession racedSession = channel.attr(TRANSPORT_SESSION_KEY).setIfAbsent(newSession);
        return racedSession != null ? racedSession : newSession;
    }


    public static void clearSession(Channel channel, String reason) {
        if (channel == null) {
            return;
        }
        ChannelTransportSession existingSession = channel.attr(TRANSPORT_SESSION_KEY).get();
        if (existingSession != null) {
            existingSession.reset();
        }
        channel.attr(TRANSPORT_SESSION_KEY).set(null);
    }

    // Proxy switches only guard outbound stale packets; inbound transport stays enabled.
    public static void beginProxyServerSwitchBoundary(Channel channel, String reason, long durationMillis) {
        if (channel == null || durationMillis <= 0L) {
            return;
        }
        long untilNanos = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(durationMillis);
        channel.attr(PROXY_SERVER_SWITCH_BOUNDARY_KEY).set(new ProxyServerSwitchBoundaryState(reason == null ? "" : reason, untilNanos));
        Bandwidthoptimizer.LOGGER.info(
                "[Transport][ProxySwitchBoundary] channel={}, reason={}, durationMs={}",
                ChannelIdentity.shortText(channel),
                reason,
                durationMillis
        );
    }

    public static boolean isProxyServerSwitchBoundaryActive(Channel channel) {
        ProxyServerSwitchBoundaryState state = proxyServerSwitchBoundaryState(channel);
        return state != null;
    }

    public static String proxyServerSwitchBoundaryReason(Channel channel) {
        ProxyServerSwitchBoundaryState state = proxyServerSwitchBoundaryState(channel);
        return state == null ? "" : state.reason();
    }

    // End the outbound guard once the new backend login packet arrives.
    public static void endProxyServerSwitchBoundary(Channel channel, String reason) {
        if (channel == null) {
            return;
        }
        ProxyServerSwitchBoundaryState state = channel.attr(PROXY_SERVER_SWITCH_BOUNDARY_KEY).getAndSet(null);
        if (state != null) {
            Bandwidthoptimizer.LOGGER.info(
                    "[Transport][ProxySwitchBoundary][End] channel={}, reason={}, previousReason={}",
                    ChannelIdentity.shortText(channel),
                    reason,
                    state.reason()
            );
        }
    }

    private static ProxyServerSwitchBoundaryState proxyServerSwitchBoundaryState(Channel channel) {
        if (channel == null) {
            return null;
        }
        ProxyServerSwitchBoundaryState state = channel.attr(PROXY_SERVER_SWITCH_BOUNDARY_KEY).get();
        if (state == null) {
            return null;
        }
        if (System.nanoTime() <= state.untilNanos()) {
            return state;
        }
        channel.attr(PROXY_SERVER_SWITCH_BOUNDARY_KEY).set(null);
        return null;
    }

    private static void ensureSessionCloseCleanup(Channel channel) {
        Boolean alreadyAttached = channel.attr(SESSION_CLOSE_CLEANUP_ATTACHED_KEY).get();
        if (Boolean.TRUE.equals(alreadyAttached)) {
            return;
        }

        Boolean raced = channel.attr(SESSION_CLOSE_CLEANUP_ATTACHED_KEY).setIfAbsent(Boolean.TRUE);
        if (Boolean.TRUE.equals(raced)) {
            return;
        }

        channel.closeFuture().addListener(future -> clearSession(channel, "channel-close"));
    }

    private record ProxyServerSwitchBoundaryState(String reason, long untilNanos) { }
}
