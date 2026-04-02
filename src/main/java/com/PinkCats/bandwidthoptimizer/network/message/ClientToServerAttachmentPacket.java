package com.PinkCats.bandwidthoptimizer.network.message;

import com.PinkCats.bandwidthoptimizer.Bandwidthoptimizer;
import com.PinkCats.bandwidthoptimizer.network.attachment.AttachmentDataFlow;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.server.level.ServerPlayer;
import net.minecraftforge.network.NetworkEvent;

import java.util.function.Supplier;

public record ClientToServerAttachmentPacket(
        String correlationId,
        String key,
        int value,
        String payload
) {

    public static void encode(ClientToServerAttachmentPacket packet, FriendlyByteBuf buffer) {
        buffer.writeUtf(packet.correlationId());
        buffer.writeUtf(packet.key());
        buffer.writeInt(packet.value());
        buffer.writeUtf(packet.payload());
    }

    public static ClientToServerAttachmentPacket decode(FriendlyByteBuf buffer) {
        return new ClientToServerAttachmentPacket(
                buffer.readUtf(),
                buffer.readUtf(),
                buffer.readInt(),
                buffer.readUtf()
        );
    }

    public static void handle(ClientToServerAttachmentPacket packet, Supplier<NetworkEvent.Context> contextSupplier) {
        NetworkEvent.Context context = contextSupplier.get();
        context.enqueueWork(() -> {
            ServerPlayer sender = context.getSender();
            String playerName = sender == null ? "<no-player>" : sender.getGameProfile().getName();
            ClientToServerAttachmentPacket processedPacket = AttachmentDataFlow.beforeServerHandle(packet, playerName);
            Bandwidthoptimizer.LOGGER.info(
                    "[ModChannel][Server][Receive] key={}, correlationId={}, value={}, payload={}, sender={}",
                    processedPacket.key(),
                    processedPacket.correlationId(),
                    processedPacket.value(),
                    processedPacket.payload(),
                    playerName
            );
        });
        context.setPacketHandled(true);
    }
}
