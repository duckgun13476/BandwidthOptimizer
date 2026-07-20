package com.PinkCats.bandwidthoptimizer.idle;

import com.PinkCats.bandwidthoptimizer.Bandwidthoptimizer;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerPlayer;
import net.minecraftforge.network.NetworkDirection;
import net.minecraftforge.network.NetworkRegistry;
import net.minecraftforge.network.simple.SimpleChannel;

public final class IdleGateNetworkChannel {

    private static final ResourceLocation CHANNEL_ID =
            ResourceLocation.fromNamespaceAndPath(Bandwidthoptimizer.MODID, Bandwidthoptimizer.versionedNetworkPath("idle_state"));
    private static final String PROTOCOL_VERSION = Bandwidthoptimizer.networkProtocolVersion();

    private static boolean registered;
    private static SimpleChannel channel;

    private IdleGateNetworkChannel() {}

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
                IdleStateBytePayload.class,
                IdleStateBytePayload::encode,
                IdleStateBytePayload::decode,
                IdleGateNetworkChannel::handleServerPayload,
                java.util.Optional.of(NetworkDirection.PLAY_TO_SERVER)
        );
        IdleGateClientController.setSender(IdleGateNetworkChannel::sendToServer);
        Bandwidthoptimizer.LOGGER.info("[IdleGate] Registered idle state channel {} version={}", CHANNEL_ID, PROTOCOL_VERSION);
    }

    private static void sendToServer(IdleGateStatePayload payload) {
        if (channel != null) {
            channel.sendToServer(new IdleStateBytePayload((payload == null ? IdleGateStatePayload.active() : payload).toBytes()));
        }
    }

    private static void handleServerPayload(
            IdleStateBytePayload payload,
            java.util.function.Supplier<net.minecraftforge.network.NetworkEvent.Context> contextSupplier
    ) {
        net.minecraftforge.network.NetworkEvent.Context context = contextSupplier.get();
        ServerPlayer player = context.getSender();
        context.enqueueWork(() -> IdleGateServerState.accept(player, IdleGateStatePayload.fromBytes(payload.bytes())));
        context.setPacketHandled(true);
    }

    private record IdleStateBytePayload(byte[] bytes) {
        private IdleStateBytePayload {
            bytes = bytes == null ? new byte[0] : bytes.clone();
        }

        public byte[] bytes() {
            return bytes.clone();
        }

        private static void encode(IdleStateBytePayload payload, FriendlyByteBuf buffer) {
            buffer.writeByteArray(payload.bytes());
        }

        private static IdleStateBytePayload decode(FriendlyByteBuf buffer) {
            return new IdleStateBytePayload(buffer.readByteArray(64));
        }
    }
}
