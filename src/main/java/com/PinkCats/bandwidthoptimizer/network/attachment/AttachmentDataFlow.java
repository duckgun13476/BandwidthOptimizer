package com.PinkCats.bandwidthoptimizer.network.attachment;

import com.PinkCats.bandwidthoptimizer.Bandwidthoptimizer;
import com.PinkCats.bandwidthoptimizer.network.message.ClientToServerAttachmentPacket;
import com.PinkCats.bandwidthoptimizer.network.message.ServerToClientAttachmentPacket;
import net.minecraft.server.level.ServerPlayer;

public final class AttachmentDataFlow {

    private AttachmentDataFlow() {
    }

    public static ServerToClientAttachmentPacket beforeServerSend(ServerPlayer player, ServerToClientAttachmentPacket packet) {
        return beforeServerSend(player.getGameProfile().getName(), packet);
    }

    public static ServerToClientAttachmentPacket beforeServerSend(String targetName, ServerToClientAttachmentPacket packet) {
        Bandwidthoptimizer.LOGGER.info(
                "[ModChannelFlow][Server][BeforeSend] target={}, key={}, correlationId={}, value={}, payload={}",
                targetName,
                packet.key(),
                packet.correlationId(),
                packet.value(),
                packet.payload()
        );
        return packet;
    }

    public static ServerToClientAttachmentPacket beforeClientHandle(ServerToClientAttachmentPacket packet, String playerName) {
        Bandwidthoptimizer.LOGGER.info(
                "[ModChannelFlow][Client][BeforeHandle] player={}, key={}, correlationId={}, value={}, payload={}",
                playerName,
                packet.key(),
                packet.correlationId(),
                packet.value(),
                packet.payload()
        );
        return packet;
    }

    public static ClientToServerAttachmentPacket beforeClientSend(ClientToServerAttachmentPacket packet, String playerName) {
        Bandwidthoptimizer.LOGGER.info(
                "[ModChannelFlow][Client][BeforeSend] player={}, key={}, correlationId={}, value={}, payload={}",
                playerName,
                packet.key(),
                packet.correlationId(),
                packet.value(),
                packet.payload()
        );
        return packet;
    }

    public static ClientToServerAttachmentPacket beforeServerHandle(ClientToServerAttachmentPacket packet, String playerName) {
        Bandwidthoptimizer.LOGGER.info(
                "[ModChannelFlow][Server][BeforeHandle] sender={}, key={}, correlationId={}, value={}, payload={}",
                playerName,
                packet.key(),
                packet.correlationId(),
                packet.value(),
                packet.payload()
        );
        return packet;
    }

    public static ClientToServerAttachmentPacket createClientResponse(ServerToClientAttachmentPacket packet, String playerName) {
        int nextValue = packet.value() + 1;
        ClientToServerAttachmentPacket responsePacket = new ClientToServerAttachmentPacket(
                packet.correlationId(),
                "ack:" + packet.key(),
                nextValue,
                "client=" + playerName + ", received=" + packet.payload()
        );

        Bandwidthoptimizer.LOGGER.info(
                "[ModChannelFlow][Client][CreateResponse] player={}, key={}, correlationId={}, value={}, payload={}",
                playerName,
                responsePacket.key(),
                responsePacket.correlationId(),
                responsePacket.value(),
                responsePacket.payload()
        );
        return responsePacket;
    }
}
