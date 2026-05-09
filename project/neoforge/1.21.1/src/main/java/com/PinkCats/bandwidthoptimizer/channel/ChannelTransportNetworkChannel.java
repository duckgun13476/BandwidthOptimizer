package com.PinkCats.bandwidthoptimizer.channel;

import com.PinkCats.bandwidthoptimizer.Bandwidthoptimizer;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.neoforge.network.event.RegisterPayloadHandlersEvent;

public final class ChannelTransportNetworkChannel {

    public static final ResourceLocation TRANSPORT_PAYLOAD_ID =
            ResourceLocation.fromNamespaceAndPath(Bandwidthoptimizer.MODID, Bandwidthoptimizer.versionedNetworkPath("transport"));
    private static final String PROTOCOL_VERSION = Bandwidthoptimizer.networkProtocolVersion();

    private static IEventBus modEventBus;
    private static boolean registered;

    private ChannelTransportNetworkChannel() {}

    public static synchronized void setModEventBus(IEventBus eventBus) {
        modEventBus = eventBus;
    }

    public static synchronized void register() {
        if (registered) {
            return;
        }

        registered = true;
        if (modEventBus != null) {
            CustomPacketPayload.Type<ChannelTransportBytePayload> type = ChannelTransportBytePayload.TYPE;
            StreamCodec<RegistryFriendlyByteBuf, ChannelTransportBytePayload> codec = ChannelTransportBytePayload.STREAM_CODEC;
            modEventBus.addListener((RegisterPayloadHandlersEvent event) ->
                    event.registrar(PROTOCOL_VERSION)
                            .playBidirectional(type, codec, (payload, context) -> {}));
        }
        Bandwidthoptimizer.LOGGER.info(
                "[Transport] Registered NeoForge network channel {} version={}",
                TRANSPORT_PAYLOAD_ID,
                PROTOCOL_VERSION
        );
    }
}
