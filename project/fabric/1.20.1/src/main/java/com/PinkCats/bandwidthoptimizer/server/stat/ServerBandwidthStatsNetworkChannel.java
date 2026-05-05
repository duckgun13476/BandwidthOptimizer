package com.PinkCats.bandwidthoptimizer.server.stat;

import com.PinkCats.bandwidthoptimizer.Bandwidthoptimizer;
import com.pinkcats.torque.layer.TorqueLayer;
import com.pinkcats.torque.layer.platform.network.BytePayloadChannel;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerPlayer;

public final class ServerBandwidthStatsNetworkChannel {

    private static final ResourceLocation CHANNEL_ID =
            new ResourceLocation(Bandwidthoptimizer.MODID, "server_bandwidth_stats");
    private static final String PROTOCOL_VERSION = "1";

    private static boolean registered;
    private static BytePayloadChannel channel;

    private ServerBandwidthStatsNetworkChannel() {}

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
                ServerBandwidthStatsPayload::handleClientBytes
        );
        Bandwidthoptimizer.LOGGER.info(
                "[ServerBandwidthStats] Registered HUD stats channel {} version={}",
                CHANNEL_ID,
                PROTOCOL_VERSION
        );
    }

    public static void sendToPlayer(ServerPlayer player, ServerBandwidthStatsPayload payload) {
        if (player == null || channel == null) {
            return;
        }
        channel.sendToPlayer(
                TorqueLayer.platform().serverPlayer(player),
                (payload == null ? ServerBandwidthStatsPayload.empty() : payload).toBytes()
        );
    }
}
