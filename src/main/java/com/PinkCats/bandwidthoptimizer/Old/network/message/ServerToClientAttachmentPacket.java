package com.PinkCats.bandwidthoptimizer.Old.network.message;

import com.PinkCats.bandwidthoptimizer.Old.network.client.ClientAttachmentPacketHandler;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.fml.DistExecutor;
import net.minecraftforge.network.NetworkEvent;

import java.util.function.Supplier;

public record ServerToClientAttachmentPacket(
        String correlationId,
        String key,
        int value,
        String payload
) {

    public static void encode(ServerToClientAttachmentPacket packet, FriendlyByteBuf buffer) {
        buffer.writeUtf(packet.correlationId());
        buffer.writeUtf(packet.key());
        buffer.writeInt(packet.value());
        buffer.writeUtf(packet.payload());
    }

    public static ServerToClientAttachmentPacket decode(FriendlyByteBuf buffer) {
        return new ServerToClientAttachmentPacket(
                buffer.readUtf(),
                buffer.readUtf(),
                buffer.readInt(),
                buffer.readUtf()
        );
    }

    public static void handle(ServerToClientAttachmentPacket packet, Supplier<NetworkEvent.Context> contextSupplier) {
        NetworkEvent.Context context = contextSupplier.get();
        context.enqueueWork(() -> DistExecutor.unsafeRunWhenOn(
                Dist.CLIENT,
                () -> () -> ClientAttachmentPacketHandler.handle(packet)
        ));
        context.setPacketHandled(true);
    }
}
