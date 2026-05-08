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
            ResourceLocation.fromNamespaceAndPath(Bandwidthoptimizer.MODID, "server_bandwidth_stats");
    private static final String PROTOCOL_VERSION = "1";

    private static IEventBus modEventBus;
    private static boolean registered;

    private ServerBandwidthStatsNetworkChannel() {}

    // 保存 mod 事件总线，让统计 HUD payload 能在 NeoForge payload 注册事件期间完成方向注册。
    public static synchronized void setModEventBus(IEventBus eventBus) {
        modEventBus = eventBus;
    }

    // 注册服务端到客户端的统计 HUD payload，使用 optional 兼容未安装客户端或未协商出该通道的连接。
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

    // 只向已经协商出统计 HUD channel 的客户端发送，避免 NeoForge 拒绝未知 payload 导致服务端 tick 崩溃。
    public static void sendToPlayer(ServerPlayer player, ServerBandwidthStatsPayload payload) {
        if (player == null || !NetworkRegistry.hasChannel(player.connection, CHANNEL_ID)) {
            return;
        }
        byte[] bytes = (payload == null ? ServerBandwidthStatsPayload.empty() : payload).toBytes();
        PacketDistributor.sendToPlayer(player, new ServerBandwidthStatsBytePayload(bytes));
    }
}
