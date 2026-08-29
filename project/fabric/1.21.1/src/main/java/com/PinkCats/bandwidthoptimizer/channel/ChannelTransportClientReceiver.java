package com.PinkCats.bandwidthoptimizer.channel;

import net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking;

public final class ChannelTransportClientReceiver {

    private static boolean registered;

    private ChannelTransportClientReceiver() {}

    public static synchronized void register() {
        if (registered) {
            return;
        }
        registered = true;
        ClientPlayNetworking.registerGlobalReceiver(ChannelTransportBytePayload.TYPE, (payload, context) -> {
            // Transport carriers are consumed by the Netty decode hook before play dispatch.
        });
    }
}
