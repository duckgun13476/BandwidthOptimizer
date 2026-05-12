package com.PinkCats.bandwidthoptimizer.server.stat;

import net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking;

public final class ServerBandwidthStatsClientReceiver {

    private static boolean registered;

    private ServerBandwidthStatsClientReceiver() {}

    public static synchronized void register() {
        if (registered)
            return;

        registered = true;
        ClientPlayNetworking.registerGlobalReceiver(
                ServerBandwidthStatsBytePayload.TYPE,
                (payload, context) -> context.client().execute(() ->
                        ServerBandwidthStatsPayload.handleClientBytes(payload.bytes()))
        );
    }
}
