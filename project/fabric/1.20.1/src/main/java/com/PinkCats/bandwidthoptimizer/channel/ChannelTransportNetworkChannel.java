package com.PinkCats.bandwidthoptimizer.channel;

import com.PinkCats.bandwidthoptimizer.Bandwidthoptimizer;
import com.pinkcats.torque.layer.TorqueLayer;
import net.minecraft.resources.ResourceLocation;

public final class ChannelTransportNetworkChannel {

    public static final ResourceLocation TRANSPORT_PAYLOAD_ID =
            new ResourceLocation(Bandwidthoptimizer.MODID, "transport");
    private static final String PROTOCOL_VERSION = "1";

    private static boolean registered;

    private ChannelTransportNetworkChannel() {}

    public static synchronized void register() {
        if (registered) {
            return;
        }

        registered = true;
        TorqueLayer.platform().network().registerAcceptedPayloadChannel(
                com.pinkcats.torque.layer.net.minecraft.resources.ResourceLocation.fromNamespaceAndPath(
                        TRANSPORT_PAYLOAD_ID.getNamespace(),
                        TRANSPORT_PAYLOAD_ID.getPath()
                ),
                PROTOCOL_VERSION
        );
        Bandwidthoptimizer.LOGGER.info(
                "[Transport] Registered Fabric network channel {} version={}",
                TRANSPORT_PAYLOAD_ID,
                PROTOCOL_VERSION
        );
    }
}
