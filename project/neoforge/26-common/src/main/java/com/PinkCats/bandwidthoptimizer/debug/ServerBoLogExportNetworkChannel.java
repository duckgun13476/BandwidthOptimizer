package com.PinkCats.bandwidthoptimizer.debug;

import com.PinkCats.bandwidthoptimizer.Bandwidthoptimizer;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.Identifier;
import net.minecraft.server.level.ServerPlayer;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.neoforge.network.PacketDistributor;
import net.neoforged.neoforge.network.event.RegisterPayloadHandlersEvent;
import net.neoforged.neoforge.network.registration.NetworkRegistry;

import java.util.Arrays;

public final class ServerBoLogExportNetworkChannel {

    static final Identifier CHANNEL_ID =
            Identifier.fromNamespaceAndPath(Bandwidthoptimizer.MODID, Bandwidthoptimizer.versionedNetworkPath("server_bo_log_export"));
    private static final String PROTOCOL_VERSION = Bandwidthoptimizer.networkProtocolVersion();

    private static IEventBus modEventBus;
    private static boolean registered;

    private ServerBoLogExportNetworkChannel() {
    }

    public static synchronized void setModEventBus(IEventBus eventBus) {
        modEventBus = eventBus;
    }

    public static synchronized void register() {
        if (registered) {
            return;
        }

        registered = true;
        ServerBoLogExportService.setSender(ServerBoLogExportNetworkChannel::sendToPlayer);
        if (modEventBus != null) {
            CustomPacketPayload.Type<ServerBoLogExportChunkPayload> type = ServerBoLogExportChunkPayload.TYPE;
            modEventBus.addListener((RegisterPayloadHandlersEvent event) ->
                    event.registrar(PROTOCOL_VERSION)
                            .optional()
                            .playToClient(
                                    type,
                                    ServerBoLogExportChunkPayload.STREAM_CODEC,
                                    (payload, context) -> ServerBoLogExportClientReceiver.accept(
                                            payload.sessionId(),
                                            payload.fileName(),
                                            payload.chunkIndex(),
                                            payload.totalChunks(),
                                            payload.totalBytes(),
                                            payload.bytes()
                                    )
                            ));
        }
        Bandwidthoptimizer.LOGGER.info(
                "[BO:ServerLogExport] Registered channel {} version={}",
                CHANNEL_ID,
                PROTOCOL_VERSION
        );
    }

    private static boolean sendToPlayer(ServerPlayer player, ServerBoLogExport export) {
        if (player == null || export == null || !NetworkRegistry.hasChannel(player.connection, CHANNEL_ID)) {
            return false;
        }
        byte[] bytes = export.bytes();
        int chunkSize = ServerBoLogExportChunkPayload.MAX_CHUNK_BYTES;
        int totalChunks = Math.max(1, (bytes.length + chunkSize - 1) / chunkSize);
        for (int index = 0; index < totalChunks; index++) {
            int start = index * chunkSize;
            int end = Math.min(bytes.length, start + chunkSize);
            byte[] chunk = Arrays.copyOfRange(bytes, start, end);
            PacketDistributor.sendToPlayer(player, new ServerBoLogExportChunkPayload(
                    export.sessionId(),
                    export.fileName(),
                    index,
                    totalChunks,
                    bytes.length,
                    chunk
            ));
        }
        return true;
    }
}
