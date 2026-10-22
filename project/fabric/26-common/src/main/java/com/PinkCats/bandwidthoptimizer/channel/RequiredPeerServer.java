package com.PinkCats.bandwidthoptimizer.channel;

import com.PinkCats.bandwidthoptimizer.Bandwidthoptimizer;
import net.minecraft.network.FriendlyByteBuf;
import io.netty.buffer.Unpooled;
import net.fabricmc.fabric.api.networking.v1.ServerLoginConnectionEvents;
import net.fabricmc.fabric.api.networking.v1.ServerLoginNetworking;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.Identifier;

public final class RequiredPeerServer {
    public static final Identifier ID = Identifier.fromNamespaceAndPath("bandwidthoptimizer", "required_peer");
    private RequiredPeerServer() {}

    public static void register() {
        ServerLoginNetworking.registerGlobalReceiver(ID, (server, handler, understood, buffer, synchronizer, sender) -> {
            boolean valid = false;
            if (understood) {
                try {
                    String remote = buffer.readUtf(128);
                    valid = !buffer.isReadable() && RequiredPeerHandshake.matches(Bandwidthoptimizer.networkProtocolVersion(), remote);
                } catch (RuntimeException ignored) { /* Invalid response is rejected below. */ }
            }
            if (!valid) handler.disconnect(Component.literal(RequiredPeerHandshake.FAILURE));
        });
        ServerLoginConnectionEvents.QUERY_START.register((handler, server, sender, synchronizer) -> {
            var buffer = new FriendlyByteBuf(Unpooled.buffer());
            buffer.writeUtf(Bandwidthoptimizer.networkProtocolVersion(), 128);
            sender.sendPacket(ID, buffer);
        });
    }
}
