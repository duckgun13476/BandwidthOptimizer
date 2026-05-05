package com.PinkCats.bandwidthoptimizer.channel;

import io.netty.channel.Channel;
import io.netty.util.AttributeKey;

public final class ChannelTransportStateManager {

    private static final AttributeKey<ChannelTransportSession> TRANSPORT_SESSION_KEY =
            AttributeKey.valueOf("bandwidthoptimizer:channel_transport_session");


    private ChannelTransportStateManager() {}

    public static ChannelTransportSession getOrCreateSession(Channel channel) {
        if (channel == null) {
            throw new IllegalArgumentException("channel");
        }

        ChannelTransportSession existingSession = channel.attr(TRANSPORT_SESSION_KEY).get();

        if (existingSession != null)
            return existingSession;

        ChannelTransportSession newSession = new ChannelTransportSession();
        ChannelTransportSession racedSession = channel.attr(TRANSPORT_SESSION_KEY).setIfAbsent(newSession);
        return racedSession != null ? racedSession : newSession;
    }
}
