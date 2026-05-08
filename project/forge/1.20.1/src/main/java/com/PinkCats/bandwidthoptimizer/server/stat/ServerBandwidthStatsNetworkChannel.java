package com.PinkCats.bandwidthoptimizer.server.stat;

import com.PinkCats.bandwidthoptimizer.Bandwidthoptimizer;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerPlayer;
import net.minecraftforge.network.NetworkDirection;
import net.minecraftforge.network.NetworkRegistry;
import net.minecraftforge.network.PacketDistributor;
import net.minecraftforge.network.simple.SimpleChannel;

public final class ServerBandwidthStatsNetworkChannel {

    private static final ResourceLocation CHANNEL_ID =
            ResourceLocation.fromNamespaceAndPath(Bandwidthoptimizer.MODID, "server_bandwidth_stats");
    private static final String PROTOCOL_VERSION = "1";

    private static boolean registered;
    private static SimpleChannel channel;

    private ServerBandwidthStatsNetworkChannel() {}

    // 注册服务端到客户端的统计 HUD 通道，允许客户端缺失该通道以兼容旧版客户端。
    public static synchronized void register() {
        if (registered) {
            return;
        }

        registered = true;
        channel = NetworkRegistry.newSimpleChannel(
                CHANNEL_ID,
                () -> PROTOCOL_VERSION,
                PROTOCOL_VERSION::equals,
                NetworkRegistry.acceptMissingOr(PROTOCOL_VERSION)
        );
        channel.registerMessage(
                0,
                StatsBytePayload.class,
                StatsBytePayload::encode,
                StatsBytePayload::decode,
                ServerBandwidthStatsNetworkChannel::handleClientPayload,
                java.util.Optional.of(NetworkDirection.PLAY_TO_CLIENT)
        );
        Bandwidthoptimizer.LOGGER.info(
                "[ServerBandwidthStats] Registered HUD stats channel {} version={}",
                CHANNEL_ID,
                PROTOCOL_VERSION
        );
    }

    // 只向已经声明支持统计 HUD channel 的客户端发送，旧版客户端或未安装客户端直接跳过。
    public static void sendToPlayer(ServerPlayer player, ServerBandwidthStatsPayload payload) {
        if (player == null || channel == null || !channel.isRemotePresent(player.connection.connection)) {
            return;
        }
        byte[] bytes = (payload == null ? ServerBandwidthStatsPayload.empty() : payload).toBytes();
        channel.send(PacketDistributor.PLAYER.with(() -> player), new StatsBytePayload(bytes));
    }

    // 在客户端收到统计 HUD payload 后回到原有字节解码逻辑。
    private static void handleClientPayload(StatsBytePayload payload, java.util.function.Supplier<net.minecraftforge.network.NetworkEvent.Context> contextSupplier) {
        net.minecraftforge.network.NetworkEvent.Context context = contextSupplier.get();
        context.enqueueWork(() -> ServerBandwidthStatsPayload.handleClientBytes(payload.bytes()));
        context.setPacketHandled(true);
    }

    private record StatsBytePayload(byte[] bytes) {

        // 复制统计字节，避免发送后外部数组修改 payload 内容。
        private StatsBytePayload {
            bytes = bytes == null ? new byte[0] : bytes.clone();
        }

        // 返回统计字节副本，避免调用方直接修改 payload 内部数组。
        public byte[] bytes() {
            return bytes.clone();
        }

        // 将统计 HUD payload 编码到 Forge SimpleChannel。
        private static void encode(StatsBytePayload payload, FriendlyByteBuf buffer) {
            buffer.writeByteArray(payload.bytes());
        }

        // 从 Forge SimpleChannel 解码统计 HUD payload。
        private static StatsBytePayload decode(FriendlyByteBuf buffer) {
            return new StatsBytePayload(buffer.readByteArray());
        }
    }
}
