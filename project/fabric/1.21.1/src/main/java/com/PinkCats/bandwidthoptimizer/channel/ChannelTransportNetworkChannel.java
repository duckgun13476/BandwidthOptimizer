package com.PinkCats.bandwidthoptimizer.channel;

import com.PinkCats.bandwidthoptimizer.Bandwidthoptimizer;
import net.fabricmc.fabric.api.networking.v1.PayloadTypeRegistry;
import net.fabricmc.fabric.api.networking.v1.ServerPlayNetworking;
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
        Bandwidthoptimizer.LOGGER.info(
                "[Transport] Registered Fabric network channel {} version={}",
                TRANSPORT_PAYLOAD_ID,
                PROTOCOL_VERSION
        );
    }

    public static void registerServerReceiver() {
        ServerPlayNetworking.registerGlobalReceiver(ChannelTransportBytePayload.TYPE, (payload, context) -> {
            // Transport carriers are consumed by the Netty decode hook before play dispatch.
        });
    }
}
