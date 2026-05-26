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
            ResourceLocation.fromNamespaceAndPath(Bandwidthoptimizer.MODID, Bandwidthoptimizer.versionedNetworkPath("server_bandwidth_stats"));
    private static final String PROTOCOL_VERSION = Bandwidthoptimizer.networkProtocolVersion();

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
                NetworkRegistry.acceptMissingOr(PROTOCOL_VERSION),
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

    public static void sendToPlayer(ServerPlayer player, ServerBandwidthStatsPayload payload) {
        if (player == null || channel == null || !channel.isRemotePresent(player.connection.connection)) {
            return;
        }
        byte[] bytes = (payload == null ? ServerBandwidthStatsPayload.empty() : payload).toBytes();
        channel.send(PacketDistributor.PLAYER.with(() -> player), new StatsBytePayload(bytes));
    }

    private static void handleClientPayload(StatsBytePayload payload, java.util.function.Supplier<net.minecraftforge.network.NetworkEvent.Context> contextSupplier) {
        net.minecraftforge.network.NetworkEvent.Context context = contextSupplier.get();
        context.enqueueWork(() -> ServerBandwidthStatsPayload.handleClientBytes(payload.bytes()));
        context.setPacketHandled(true);
    }

    private record StatsBytePayload(byte[] bytes) {


        private StatsBytePayload {
            bytes = bytes == null ? new byte[0] : bytes.clone();
        }

        public byte[] bytes() {
            return bytes.clone();
        }

        private static void encode(StatsBytePayload payload, FriendlyByteBuf buffer) {
            buffer.writeByteArray(payload.bytes());
        }

        private static StatsBytePayload decode(FriendlyByteBuf buffer) {
            return new StatsBytePayload(buffer.readByteArray());
        }
    }
}
