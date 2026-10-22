package com.PinkCats.bandwidthoptimizer.channel;

import com.PinkCats.bandwidthoptimizer.Bandwidthoptimizer;
import com.PinkCats.bandwidthoptimizer.mixin.minecraft.ConnectionAccessor;
import net.fabricmc.fabric.api.client.networking.v1.ClientLoginNetworking;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayConnectionEvents;
import net.fabricmc.fabric.api.networking.v1.PacketByteBufs;
import net.minecraft.network.chat.Component;
import java.util.concurrent.CompletableFuture;

public final class RequiredPeerClient {
    private RequiredPeerClient() {}

    public static void register() {
        ClientLoginNetworking.registerGlobalReceiver(RequiredPeerServer.ID, (client, handler, buffer, listenerAdder) -> {
            var connection = ((com.PinkCats.bandwidthoptimizer.mixin.minecraft.RequiredPeerLoginAccessor) handler).bandwidthoptimizer$getConnection();
            boolean valid = false;
            try {
                String remote = buffer.readUtf(128);
                valid = !buffer.isReadable() && RequiredPeerHandshake.matches(Bandwidthoptimizer.networkProtocolVersion(), remote);
            } catch (RuntimeException ignored) { /* Invalid challenge is rejected below. */ }
            if (!valid) {
                connection.disconnect(Component.literal(RequiredPeerHandshake.FAILURE));
                return CompletableFuture.completedFuture(null);
            }
            RequiredPeerHandshake.accept(((ConnectionAccessor) connection).bandwidthoptimizer$getChannel());
            var response = PacketByteBufs.create();
            response.writeUtf(Bandwidthoptimizer.networkProtocolVersion(), 128);
            return CompletableFuture.completedFuture(response);
        });
        // Login queries are server initiated. A server without BO sends none;
        // reject it at the first play-listener initialization, before JOIN.
        ClientPlayConnectionEvents.INIT.register((handler, client) -> {
            var connection = handler.getConnection();
            if (!RequiredPeerHandshake.accepted(((ConnectionAccessor) connection).bandwidthoptimizer$getChannel())) {
                connection.disconnect(Component.literal(RequiredPeerHandshake.FAILURE));
            }
        });
    }
}
