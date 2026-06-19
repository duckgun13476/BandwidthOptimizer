package com.PinkCats.bandwidthoptimizer.compat.minecraft;

import io.netty.channel.Channel;
import net.minecraft.network.ConnectionProtocol;

public final class ConnectionProtocolNameCompat {

    private ConnectionProtocolNameCompat() {}

    public static String readProtocolName(Channel channel) {
        Object protocol = ConnectionProtocol.PLAY;
        return protocol == null ? "null" : String.valueOf(protocol);
    }
}
