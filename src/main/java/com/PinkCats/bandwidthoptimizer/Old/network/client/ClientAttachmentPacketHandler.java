package com.PinkCats.bandwidthoptimizer.Old.network.client;

import com.PinkCats.bandwidthoptimizer.Bandwidthoptimizer;
import com.PinkCats.bandwidthoptimizer.Config;
import com.PinkCats.bandwidthoptimizer.Old.network.ModNetwork;
import com.PinkCats.bandwidthoptimizer.Old.network.attachment.AttachmentDataFlow;
import com.PinkCats.bandwidthoptimizer.Old.network.batch.AttachmentPacketBatching;
import com.PinkCats.bandwidthoptimizer.Old.network.batch.PayloadBatching;
import com.PinkCats.bandwidthoptimizer.Old.network.message.ClientToServerAttachmentPacket;
import com.PinkCats.bandwidthoptimizer.Old.network.message.ServerToClientAttachmentBatchPacket;
import com.PinkCats.bandwidthoptimizer.Old.network.message.ServerToClientAttachmentPacket;
import net.minecraft.client.Minecraft;

public final class ClientAttachmentPacketHandler {

    private static final PayloadBatching.Session BATCH_DECODE_SESSION = new PayloadBatching.Session();

    private ClientAttachmentPacketHandler() {
    }

    public static void handle(ServerToClientAttachmentPacket packet) {
        Minecraft minecraft = Minecraft.getInstance();
        String playerName = minecraft.player == null ? "<no-player>" : minecraft.player.getGameProfile().getName();
        ServerToClientAttachmentPacket processedPacket = AttachmentDataFlow.beforeClientHandle(packet, playerName);

        if (Config.optimizerDebugLoggingEnabled() && Bandwidthoptimizer.LOGGER.isDebugEnabled()) {
            Bandwidthoptimizer.LOGGER.debug(
                    "[ModChannel][Client][Receive] key={}, correlationId={}, value={}, payload={}, player={}",
                    processedPacket.key(),
                    processedPacket.correlationId(),
                    processedPacket.value(),
                    processedPacket.payload(),
                    playerName
            );

            Bandwidthoptimizer.LOGGER.debug(
                    "[ModChannel][Client][Send] key={}, correlationId={}, value={}, payload={}, player={}",
                    "ack:" + processedPacket.key(),
                    processedPacket.correlationId(),
                    processedPacket.value() + 1,
                    "incremented-from=" + processedPacket.value(),
                    playerName
            );
        }

        ClientToServerAttachmentPacket responsePacket = AttachmentDataFlow.beforeClientSend(
                AttachmentDataFlow.createClientResponse(processedPacket, playerName),
                playerName
        );
        ModNetwork.sendToServer(responsePacket);
    }

    public static void handleBatch(ServerToClientAttachmentBatchPacket batchPacket) {
        if (batchPacket.resetSession()) {
            BATCH_DECODE_SESSION.resetAll();
        }

        java.util.List<ServerToClientAttachmentPacket> packets = AttachmentPacketBatching.decodeBatch(
                batchPacket.algorithmId(),
                batchPacket.encodedBytes(),
                BATCH_DECODE_SESSION
        );

        boolean debugLogs = Config.optimizerDebugLoggingEnabled() && Bandwidthoptimizer.LOGGER.isDebugEnabled();
        if (debugLogs) {
            Bandwidthoptimizer.LOGGER.debug(
                    "[ModChannelBatch][Client][Receive] entries={}, algorithm={}, resetSession={}",
                    packets.size(),
                    batchPacket.algorithmId(),
                    batchPacket.resetSession()
            );
        }

        for (int i = 0; i < packets.size(); i++) {
            ServerToClientAttachmentPacket packet = packets.get(i);
            if (debugLogs) {
                Bandwidthoptimizer.LOGGER.debug(
                        "[ModChannelBatch][Client][Replay] index={}, key={}, correlationId={}, algorithm={}",
                        i,
                        packet.key(),
                        packet.correlationId(),
                        batchPacket.algorithmId()
                );
            }
            handle(packet);
        }
    }
}
