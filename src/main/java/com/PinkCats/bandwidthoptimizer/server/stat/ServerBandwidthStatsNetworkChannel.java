package com.PinkCats.bandwidthoptimizer.server.stat;

import com.PinkCats.bandwidthoptimizer.Bandwidthoptimizer;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerPlayer;
import net.minecraftforge.network.NetworkDirection;
import net.minecraftforge.network.NetworkRegistry;
import net.minecraftforge.network.PacketDistributor;
import net.minecraftforge.network.simple.SimpleChannel;

import java.util.Optional;

public final class ServerBandwidthStatsNetworkChannel {

    private static final ResourceLocation CHANNEL_ID =
            ResourceLocation.fromNamespaceAndPath(Bandwidthoptimizer.MODID, "server_bandwidth_stats");
    private static final String PROTOCOL_VERSION = "1";

    private static boolean registered;
    private static SimpleChannel channel;

    private ServerBandwidthStatsNetworkChannel() {}

    public static synchronized void register() {
        if (registered) {
            return;
        }

        registered = true;
        channel = NetworkRegistry.newSimpleChannel(
                CHANNEL_ID,
                () -> PROTOCOL_VERSION,
                NetworkRegistry.acceptMissingOr(PROTOCOL_VERSION::equals),
                NetworkRegistry.acceptMissingOr(PROTOCOL_VERSION::equals)
        );
        channel.registerMessage(
                0,
                ServerBandwidthStatsPayload.class,
                ServerBandwidthStatsPayload::encode,
                ServerBandwidthStatsPayload::decode,
                ServerBandwidthStatsPayload::handle,
                Optional.of(NetworkDirection.PLAY_TO_CLIENT)
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
        channel.send(PacketDistributor.PLAYER.with(() -> player), payload == null ? ServerBandwidthStatsPayload.empty() : payload);
    }
}
