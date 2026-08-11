package com.PinkCats.bandwidthoptimizer.debug;

import com.PinkCats.bandwidthoptimizer.Bandwidthoptimizer;
import com.PinkCats.bandwidthoptimizer.mixin.minecraft.ConnectionAccessor;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerPlayer;
import net.minecraftforge.network.NetworkDirection;
import net.minecraftforge.network.NetworkRegistry;
import net.minecraftforge.network.PacketDistributor;
import net.minecraftforge.network.simple.SimpleChannel;

public final class PacketClassTraceNetworkChannel {

    private static final ResourceLocation CHANNEL_ID = ResourceLocation.fromNamespaceAndPath(
            Bandwidthoptimizer.MODID,
            Bandwidthoptimizer.versionedNetworkPath("packet_class_trace_control")
    );
    private static final String PROTOCOL_VERSION = Bandwidthoptimizer.networkProtocolVersion();

    private static boolean registered;
    private static SimpleChannel channel;

    private PacketClassTraceNetworkChannel() {}

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
                ControlBytes.class,
                ControlBytes::encode,
                ControlBytes::decode,
                PacketClassTraceNetworkChannel::handleClientPayload,
                java.util.Optional.of(NetworkDirection.PLAY_TO_CLIENT)
        );
        PacketClassTraceControlService.setSender(PacketClassTraceNetworkChannel::sendToPlayer);
    }

    private static boolean sendToPlayer(ServerPlayer player, PacketClassTraceControlPayload payload) {
        if (player == null || channel == null || !channel.isRemotePresent(player.connection.connection)) {
            return false;
        }
        channel.send(PacketDistributor.PLAYER.with(() -> player), new ControlBytes(payload.toBytes()));
        return true;
    }

    private static void handleClientPayload(
            ControlBytes payload,
            java.util.function.Supplier<net.minecraftforge.network.NetworkEvent.Context> contextSupplier
    ) {
        net.minecraftforge.network.NetworkEvent.Context context = contextSupplier.get();
        context.enqueueWork(() -> PacketClassTraceControlService.acceptClient(
                ((ConnectionAccessor) context.getNetworkManager()).bandwidthoptimizer$getChannel(),
                payload.bytes()
        ));
        context.setPacketHandled(true);
    }

    private record ControlBytes(byte[] bytes) {
        private ControlBytes {
            bytes = bytes == null ? new byte[0] : bytes.clone();
        }

        public byte[] bytes() {
            return bytes.clone();
        }

        private static void encode(ControlBytes payload, FriendlyByteBuf buffer) {
            buffer.writeByteArray(payload.bytes());
        }

        private static ControlBytes decode(FriendlyByteBuf buffer) {
            return new ControlBytes(buffer.readByteArray(32 * 1024));
        }
    }
}
