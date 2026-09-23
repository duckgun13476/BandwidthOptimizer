package com.PinkCats.bandwidthoptimizer.debug;

import com.PinkCats.bandwidthoptimizer.Bandwidthoptimizer;
import com.PinkCats.bandwidthoptimizer.mixin.minecraft.ConnectionAccessor;
import net.minecraft.client.Minecraft;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.Identifier;
import net.minecraft.server.level.ServerPlayer;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.neoforge.network.PacketDistributor;
import net.neoforged.neoforge.network.event.RegisterPayloadHandlersEvent;
import net.neoforged.neoforge.network.registration.NetworkRegistry;

public final class PacketClassTraceNetworkChannel {

    static final Identifier CHANNEL_ID = Identifier.fromNamespaceAndPath(
            Bandwidthoptimizer.MODID,
            Bandwidthoptimizer.versionedNetworkPath("packet_class_trace_control")
    );
    private static final String PROTOCOL_VERSION = Bandwidthoptimizer.networkProtocolVersion();

    private static IEventBus modEventBus;
    private static boolean registered;

    private PacketClassTraceNetworkChannel() {}

    public static synchronized void setModEventBus(IEventBus eventBus) {
        modEventBus = eventBus;
    }

    public static synchronized void register() {
        if (registered) {
            return;
        }
        registered = true;
        PacketClassTraceControlService.setSender(PacketClassTraceNetworkChannel::sendToPlayer);
        if (modEventBus != null) {
            CustomPacketPayload.Type<PacketClassTraceControlBytePayload> type =
                    PacketClassTraceControlBytePayload.TYPE;
            modEventBus.addListener((RegisterPayloadHandlersEvent event) ->
                    event.registrar(PROTOCOL_VERSION)
                            .optional()
                            .playToClient(
                                    type,
                                    PacketClassTraceControlBytePayload.STREAM_CODEC,
                                    (payload, context) -> acceptClient(payload.bytes())
                            ));
        }
    }

    private static boolean sendToPlayer(ServerPlayer player, PacketClassTraceControlPayload payload) {
        if (player == null || !NetworkRegistry.hasChannel(player.connection, CHANNEL_ID)) {
            return false;
        }
        PacketDistributor.sendToPlayer(player, new PacketClassTraceControlBytePayload(payload.toBytes()));
        return true;
    }

    private static void acceptClient(byte[] bytes) {
        Minecraft minecraft = Minecraft.getInstance();
        if (minecraft.getConnection() == null) {
            return;
        }
        PacketClassTraceControlService.acceptClient(
                ((ConnectionAccessor) minecraft.getConnection().getConnection()).bandwidthoptimizer$getChannel(),
                bytes
        );
    }
}
