package com.PinkCats.bandwidthoptimizer.server.stat;

import com.PinkCats.bandwidthoptimizer.Bandwidthoptimizer;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerPlayer;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.neoforge.network.PacketDistributor;
import net.neoforged.neoforge.network.event.RegisterPayloadHandlersEvent;
import net.neoforged.neoforge.network.registration.NetworkRegistry;

public final class ServerBandwidthStatsNetworkChannel {

    static final ResourceLocation CHANNEL_ID =
            ResourceLocation.fromNamespaceAndPath(Bandwidthoptimizer.MODID, Bandwidthoptimizer.versionedNetworkPath("server_bandwidth_stats"));
    private static final String PROTOCOL_VERSION = Bandwidthoptimizer.networkProtocolVersion();

    private static IEventBus modEventBus;
    private static boolean registered;

    private ServerBandwidthStatsNetworkChannel() {}

    public static synchronized void setModEventBus(IEventBus eventBus) {
        modEventBus = eventBus;
    }

    public static synchronized void register() {
        if (registered) {
            return;
        }

        registered = true;
        if (modEventBus != null) {
            CustomPacketPayload.Type<ServerBandwidthStatsBytePayload> type = ServerBandwidthStatsBytePayload.TYPE;
            modEventBus.addListener((RegisterPayloadHandlersEvent event) ->
                    event.registrar(PROTOCOL_VERSION)
                            .optional()
                            .playToClient(
                                    type,
                                    ServerBandwidthStatsBytePayload.STREAM_CODEC,
                                    (payload, context) -> ServerBandwidthStatsPayload.handleClientBytes(payload.bytes())
                            ));
        }
        Bandwidthoptimizer.LOGGER.info(
                "[ServerBandwidthStats] Registered HUD stats channel {} version={}",
                CHANNEL_ID,
                PROTOCOL_VERSION
        );
    }

    public static void sendToPlayer(ServerPlayer player, ServerBandwidthStatsPayload payload) {
        if (player == null || !NetworkRegistry.hasChannel(player.connection, CHANNEL_ID)) {
            return;
        }
        byte[] bytes = (payload == null ? ServerBandwidthStatsPayload.empty() : payload).toBytes();
        PacketDistributor.sendToPlayer(player, new ServerBandwidthStatsBytePayload(bytes));
    }
}
