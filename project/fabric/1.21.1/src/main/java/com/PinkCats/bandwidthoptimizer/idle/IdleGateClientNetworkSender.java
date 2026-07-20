package com.PinkCats.bandwidthoptimizer.idle;

import net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking;

public final class IdleGateClientNetworkSender {

    private IdleGateClientNetworkSender() {}

    public static void register() {
        IdleGateClientController.setSender(IdleGateClientNetworkSender::sendToServer);
    }

    private static void sendToServer(IdleGateStatePayload payload) {
        ClientPlayNetworking.send(new IdleGateStateBytePayload(
                (payload == null ? IdleGateStatePayload.active() : payload).toBytes()));
    }
}
