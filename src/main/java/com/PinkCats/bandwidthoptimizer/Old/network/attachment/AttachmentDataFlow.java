package com.PinkCats.bandwidthoptimizer.Old.network.attachment;

import com.PinkCats.bandwidthoptimizer.Old.network.message.ClientToServerAttachmentPacket;
import com.PinkCats.bandwidthoptimizer.Old.network.message.ServerToClientAttachmentPacket;
import net.minecraft.server.level.ServerPlayer;

public final class AttachmentDataFlow {

    private AttachmentDataFlow() {
    }

    public static ServerToClientAttachmentPacket beforeServerSend(ServerPlayer player, ServerToClientAttachmentPacket packet) {
        return beforeServerSend(player.getGameProfile().getName(), packet);
    }

    public static ServerToClientAttachmentPacket beforeServerSend(String targetName, ServerToClientAttachmentPacket packet) {
        return packet;
    }

    public static ServerToClientAttachmentPacket beforeClientHandle(ServerToClientAttachmentPacket packet, String playerName) {
        return packet;
    }

    public static ClientToServerAttachmentPacket beforeClientSend(ClientToServerAttachmentPacket packet, String playerName) {
        return packet;
    }

    public static ClientToServerAttachmentPacket beforeServerHandle(ClientToServerAttachmentPacket packet, String playerName) {
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
        return responsePacket;
    }
}
