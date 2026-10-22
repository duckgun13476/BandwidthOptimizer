package com.PinkCats.bandwidthoptimizer.channel;

import io.netty.channel.Channel;
import io.netty.util.AttributeKey;

/** Connection-local receipt of the required Fabric login challenge. */
public final class RequiredPeerHandshake {
    private static final AttributeKey<Boolean> ACCEPTED = AttributeKey.valueOf("bandwidthoptimizer.requiredPeer.v1");
    public static final String FAILURE = "BandwidthOptimizer is required on both client and server, with matching versions.";

    private RequiredPeerHandshake() {}

    public static boolean matches(String expected, String received) {
        return expected != null && !expected.isEmpty() && expected.equals(received);
    }

    public static void accept(Channel channel) {
        if (channel != null && channel.isOpen()) channel.attr(ACCEPTED).set(Boolean.TRUE);
    }

    public static boolean accepted(Channel channel) {
        return channel != null && channel.isOpen() && Boolean.TRUE.equals(channel.attr(ACCEPTED).get());
    }
}
