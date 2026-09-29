package com.PinkCats.bandwidthoptimizer.debug;

import com.PinkCats.bandwidthoptimizer.Bandwidthoptimizer;
import net.fabricmc.fabric.api.networking.v1.PayloadTypeRegistry;
import net.fabricmc.fabric.api.networking.v1.ServerPlayNetworking;
import net.minecraft.resources.Identifier;
import net.minecraft.server.level.ServerPlayer;

public final class PacketClassTraceNetworkChannel {

    static final Identifier CHANNEL_ID = Identifier.fromNamespaceAndPath(
            Bandwidthoptimizer.MODID,
            Bandwidthoptimizer.versionedNetworkPath("packet_class_trace_control")
    );
    private static boolean registered;

    private PacketClassTraceNetworkChannel() {}

    public static synchronized void register() {
        if (registered) {
            return;
        }
        registered = true;
        PayloadTypeRegistry.clientboundPlay().register(
                PacketClassTraceControlBytePayload.TYPE,
                PacketClassTraceControlBytePayload.STREAM_CODEC
        );
        PacketClassTraceControlService.setSender(PacketClassTraceNetworkChannel::sendToPlayer);
    }

    private static boolean sendToPlayer(ServerPlayer player, PacketClassTraceControlPayload payload) {
        if (player == null || !ServerPlayNetworking.canSend(player, PacketClassTraceControlBytePayload.TYPE)) {
            return false;
        }
        ServerPlayNetworking.send(player, new PacketClassTraceControlBytePayload(payload.toBytes()));
        return true;
    }
}
