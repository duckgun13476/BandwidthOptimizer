package com.PinkCats.bandwidthoptimizer.server.stat;

import com.PinkCats.bandwidthoptimizer.Bandwidthoptimizer;
import net.fabricmc.fabric.api.networking.v1.PayloadTypeRegistry;
import net.fabricmc.fabric.api.networking.v1.ServerPlayNetworking;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerPlayer;

public final class ServerBandwidthStatsNetworkChannel {

    static final ResourceLocation CHANNEL_ID =
            ResourceLocation.fromNamespaceAndPath(Bandwidthoptimizer.MODID, Bandwidthoptimizer.versionedNetworkPath("server_bandwidth_stats"));

    private static boolean registered;
    private ServerBandwidthStatsNetworkChannel() {}

    public static synchronized void register() {
        if (registered)
            return;

        registered = true;
        PayloadTypeRegistry.playS2C().register(ServerBandwidthStatsBytePayload.TYPE, ServerBandwidthStatsBytePayload.STREAM_CODEC);
        Bandwidthoptimizer.LOGGER.info(
                "[ServerBandwidthStats] Registered Fabric HUD stats channel {} version={}",
                CHANNEL_ID,
                Bandwidthoptimizer.networkProtocolVersion()
        );
    }

    public static void sendToPlayer(ServerPlayer player, ServerBandwidthStatsPayload payload) {
        if (player == null || !ServerPlayNetworking.canSend(player, ServerBandwidthStatsBytePayload.TYPE)) {
            return;
        }
        byte[] bytes = (payload == null ? ServerBandwidthStatsPayload.empty() : payload).toBytes();
        ServerPlayNetworking.send(player, new ServerBandwidthStatsBytePayload(bytes));
    }
}
