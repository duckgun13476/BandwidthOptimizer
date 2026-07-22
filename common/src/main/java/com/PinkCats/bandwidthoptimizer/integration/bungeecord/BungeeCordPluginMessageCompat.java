package com.PinkCats.bandwidthoptimizer.integration.bungeecord;

import java.util.Locale;
import java.util.Set;

public final class BungeeCordPluginMessageCompat {

    private static final Set<String> BUNGEE_CORD_CHANNELS = Set.of(
            "bungeecord",
            "bungeecord:main",
            "bungee:main"
    );

    private BungeeCordPluginMessageCompat() {}

    public static boolean isProxyControlChannel(String payloadChannel) {
        if (payloadChannel == null) {
            return false;
        }
        return BUNGEE_CORD_CHANNELS.contains(payloadChannel.trim().toLowerCase(Locale.ROOT));
    }
}
