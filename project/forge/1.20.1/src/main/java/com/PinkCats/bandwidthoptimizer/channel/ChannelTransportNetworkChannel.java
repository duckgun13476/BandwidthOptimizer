package com.PinkCats.bandwidthoptimizer.channel;

import com.PinkCats.bandwidthoptimizer.Bandwidthoptimizer;
import com.pinkcats.torque.layer.TorqueLayer;
import net.minecraft.resources.ResourceLocation;
import net.minecraftforge.network.NetworkRegistry;
import net.minecraftforge.network.simple.SimpleChannel;

public final class ChannelTransportNetworkChannel {

    public static final ResourceLocation TRANSPORT_PAYLOAD_ID =
            ResourceLocation.fromNamespaceAndPath(Bandwidthoptimizer.MODID, Bandwidthoptimizer.versionedNetworkPath("transport"));
    private static final ResourceLocation VERSION_GATE_CHANNEL_ID =
            ResourceLocation.fromNamespaceAndPath(Bandwidthoptimizer.MODID, Bandwidthoptimizer.versionedNetworkPath("transport_gate"));
    private static final String PROTOCOL_VERSION = Bandwidthoptimizer.networkProtocolVersion();

    private static boolean registered;
    private static SimpleChannel versionGateChannel;

    private ChannelTransportNetworkChannel() {}

    public static synchronized void register() {
        if (registered) {
            return;
        }

        registered = true;
        versionGateChannel = NetworkRegistry.newSimpleChannel(
                VERSION_GATE_CHANNEL_ID,
                () -> PROTOCOL_VERSION,
                NetworkRegistry.acceptMissingOr(PROTOCOL_VERSION),
                NetworkRegistry.acceptMissingOr(PROTOCOL_VERSION)
        );
        TorqueLayer.platform().network().registerAcceptedPayloadChannel(
                com.pinkcats.torque.layer.net.minecraft.resources.ResourceLocation.fromNamespaceAndPath(
                        TRANSPORT_PAYLOAD_ID.getNamespace(),
                        TRANSPORT_PAYLOAD_ID.getPath()
                ),
                PROTOCOL_VERSION
        );
        Bandwidthoptimizer.LOGGER.info(
                "[Transport] Registered Forge network channel {} gate={} version={}",
                TRANSPORT_PAYLOAD_ID,
                VERSION_GATE_CHANNEL_ID,
                PROTOCOL_VERSION
        );
    }
}
