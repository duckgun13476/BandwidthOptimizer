package com.PinkCats.bandwidthoptimizer.channel;

import com.PinkCats.bandwidthoptimizer.Bandwidthoptimizer;
import net.minecraft.resources.ResourceLocation;
import net.minecraftforge.network.NetworkEvent;
import net.minecraftforge.network.NetworkRegistry;
import net.minecraftforge.network.event.EventNetworkChannel;

public final class ChannelTransportNetworkChannel {

    public static final ResourceLocation TRANSPORT_PAYLOAD_ID =
            ResourceLocation.fromNamespaceAndPath(Bandwidthoptimizer.MODID, "transport");
    private static final String PROTOCOL_VERSION = "1";

    private static boolean registered;
    private static EventNetworkChannel channel;

    private ChannelTransportNetworkChannel() {}

    public static synchronized void register() {
        if (registered) {
            return;
        }

        registered = true;
        channel = NetworkRegistry.newEventChannel(
                TRANSPORT_PAYLOAD_ID,
                () -> PROTOCOL_VERSION,
                NetworkRegistry.acceptMissingOr(PROTOCOL_VERSION::equals),
                NetworkRegistry.acceptMissingOr(PROTOCOL_VERSION::equals)
        );
        channel.addListener((NetworkEvent.ClientCustomPayloadEvent event) -> markPayloadHandled(event));
        channel.addListener((NetworkEvent.ServerCustomPayloadEvent event) -> markPayloadHandled(event));
        Bandwidthoptimizer.LOGGER.info(
                "[Transport] Registered Forge network channel {} version={}",
                TRANSPORT_PAYLOAD_ID,
                PROTOCOL_VERSION
        );
    }

    private static void markPayloadHandled(NetworkEvent event) {
        if (event == null || event.getSource() == null) {
            return;
        }
        event.getSource().get().setPacketHandled(true);
    }
}
