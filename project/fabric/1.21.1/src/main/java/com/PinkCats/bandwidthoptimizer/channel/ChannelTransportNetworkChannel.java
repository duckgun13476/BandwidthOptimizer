package com.PinkCats.bandwidthoptimizer.channel;

import com.PinkCats.bandwidthoptimizer.Bandwidthoptimizer;
import com.pinkcats.torque.layer.TorqueLayer;
import net.fabricmc.fabric.api.networking.v1.PayloadTypeRegistry;
import net.minecraft.resources.ResourceLocation;

public final class ChannelTransportNetworkChannel {

    public static final ResourceLocation TRANSPORT_PAYLOAD_ID =
            ResourceLocation.fromNamespaceAndPath(Bandwidthoptimizer.MODID, Bandwidthoptimizer.versionedNetworkPath("transport"));
    private static final String PROTOCOL_VERSION = Bandwidthoptimizer.networkProtocolVersion();

    private static boolean registered;

    private ChannelTransportNetworkChannel() {}

    public static synchronized void register() {
        if (registered) {
            return;
        }

        registered = true;
        PayloadTypeRegistry.playS2C().register(ChannelTransportBytePayload.TYPE, ChannelTransportBytePayload.STREAM_CODEC);
        PayloadTypeRegistry.playC2S().register(ChannelTransportBytePayload.TYPE, ChannelTransportBytePayload.STREAM_CODEC);
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
