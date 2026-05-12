package com.PinkCats.bandwidthoptimizer.compat.minecraft;

import io.netty.channel.Channel;
import net.minecraft.network.Connection;

public final class ConnectionProtocolNameCompat {

    private ConnectionProtocolNameCompat() {}

    public static String readProtocolName(Channel channel) {
        if (channel == null) {
            return "null";
        }
        Object protocol = channel.attr(Connection.ATTRIBUTE_PROTOCOL).get();
        return protocol == null ? "null" : String.valueOf(protocol);
    }
}
