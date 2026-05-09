package com.PinkCats.bandwidthoptimizer.channel;

import io.netty.channel.Channel;
import io.netty.util.AttributeKey;

public final class ChannelTransportStateManager {

    private static final AttributeKey<ChannelTransportSession> TRANSPORT_SESSION_KEY =
            AttributeKey.valueOf("bandwidthoptimizer:channel_transport_session");

    private static final AttributeKey<Boolean> SESSION_CLOSE_CLEANUP_ATTACHED_KEY =
            AttributeKey.valueOf("bandwidthoptimizer:channel_transport_session_close_cleanup_attached");

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
}
