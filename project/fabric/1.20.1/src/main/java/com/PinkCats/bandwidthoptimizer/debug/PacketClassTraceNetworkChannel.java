package com.PinkCats.bandwidthoptimizer.debug;

import com.PinkCats.bandwidthoptimizer.Bandwidthoptimizer;
import com.PinkCats.bandwidthoptimizer.mixin.minecraft.ConnectionAccessor;
import com.pinkcats.torque.layer.TorqueLayer;
import com.pinkcats.torque.layer.platform.network.BytePayloadChannel;
import net.minecraft.client.Minecraft;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerPlayer;

public final class PacketClassTraceNetworkChannel {

    private static final ResourceLocation CHANNEL_ID = new ResourceLocation(
            Bandwidthoptimizer.MODID,
            Bandwidthoptimizer.versionedNetworkPath("packet_class_trace_control")
    );
    private static final String PROTOCOL_VERSION = Bandwidthoptimizer.networkProtocolVersion();

    private static boolean registered;
    private static BytePayloadChannel channel;

    private PacketClassTraceNetworkChannel() {}

    public static synchronized void register() {
        if (registered) {
            return;
        }
        registered = true;
        channel = TorqueLayer.platform().network().registerClientboundBytes(
                com.pinkcats.torque.layer.net.minecraft.resources.ResourceLocation.fromNamespaceAndPath(
                        CHANNEL_ID.getNamespace(),
                        CHANNEL_ID.getPath()
                ),
                PROTOCOL_VERSION,
                PacketClassTraceNetworkChannel::acceptClient
        );
        PacketClassTraceControlService.setSender(PacketClassTraceNetworkChannel::sendToPlayer);
    }

    private static boolean sendToPlayer(ServerPlayer player, PacketClassTraceControlPayload payload) {
        if (player == null || channel == null) {
            return false;
        }
        channel.sendToPlayer(TorqueLayer.platform().serverPlayer(player), payload.toBytes());
        return true;
    }

    private static void acceptClient(byte[] bytes) {
        Minecraft minecraft = Minecraft.getInstance();
        if (minecraft.getConnection() == null) {
            return;
        }
        PacketClassTraceControlService.acceptClient(
                ((ConnectionAccessor) minecraft.getConnection().getConnection()).bandwidthoptimizer$getChannel(),
                bytes
        );
    }
}
