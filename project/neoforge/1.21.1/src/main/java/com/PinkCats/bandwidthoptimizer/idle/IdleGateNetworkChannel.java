package com.PinkCats.bandwidthoptimizer.idle;

import com.PinkCats.bandwidthoptimizer.Bandwidthoptimizer;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.neoforge.network.PacketDistributor;
import net.neoforged.neoforge.network.event.RegisterPayloadHandlersEvent;

public final class IdleGateNetworkChannel {

    static final ResourceLocation CHANNEL_ID =
            ResourceLocation.fromNamespaceAndPath(Bandwidthoptimizer.MODID, Bandwidthoptimizer.versionedNetworkPath("idle_state"));
    private static final String PROTOCOL_VERSION = Bandwidthoptimizer.networkProtocolVersion();

    private static IEventBus modEventBus;
    private static boolean registered;

    private IdleGateNetworkChannel() {}

    public static synchronized void setModEventBus(IEventBus eventBus) {
        modEventBus = eventBus;
    }

    public static synchronized void register() {
        if (registered) {
            return;
        }
        registered = true;
        if (modEventBus != null) {
            CustomPacketPayload.Type<IdleGateStateBytePayload> type = IdleGateStateBytePayload.TYPE;
            modEventBus.addListener((RegisterPayloadHandlersEvent event) ->
                    event.registrar(PROTOCOL_VERSION)
                            .optional()
                            .playToServer(
                                    type,
                                    IdleGateStateBytePayload.STREAM_CODEC,
                                    (payload, context) -> IdleGateServerState.accept(
                                            (net.minecraft.server.level.ServerPlayer) context.player(),
                                            IdleGateStatePayload.fromBytes(payload.bytes()))
                            ));
        }
        IdleGateClientController.setSender(IdleGateNetworkChannel::sendToServer);
        Bandwidthoptimizer.LOGGER.info("[IdleGate] Registered idle state channel {} version={}", CHANNEL_ID, PROTOCOL_VERSION);
    }

    private static void sendToServer(IdleGateStatePayload payload) {
        PacketDistributor.sendToServer(new IdleGateStateBytePayload(
                (payload == null ? IdleGateStatePayload.active() : payload).toBytes()));
    }
}
