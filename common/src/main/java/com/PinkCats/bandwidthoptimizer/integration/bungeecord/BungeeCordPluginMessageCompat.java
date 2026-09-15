package com.PinkCats.bandwidthoptimizer.integration.bungeecord;

public final class BungeeCordPluginMessageCompat {
    private BungeeCordPluginMessageCompat() {}

    public static boolean isProxyControlChannel(String payloadChannel) {
        return ProxyControlChannelRegistry.contains(payloadChannel);
    }
}
