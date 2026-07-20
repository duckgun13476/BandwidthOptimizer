package com.PinkCats.bandwidthoptimizer.idle;

import io.netty.buffer.Unpooled;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking;
import net.minecraft.network.FriendlyByteBuf;

public final class IdleGateClientNetworkSender {

    private IdleGateClientNetworkSender() {}

    public static void register() {
        IdleGateClientController.setSender(IdleGateClientNetworkSender::sendToServer);
    }

    private static void sendToServer(IdleGateStatePayload payload) {
        FriendlyByteBuf buffer = new FriendlyByteBuf(Unpooled.buffer());
        buffer.writeByteArray((payload == null ? IdleGateStatePayload.active() : payload).toBytes());
        ClientPlayNetworking.send(IdleGateNetworkChannel.CHANNEL_ID, buffer);
    }
}
