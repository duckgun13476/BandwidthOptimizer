package com.PinkCats.bandwidthoptimizer.Old.network.message;

import net.minecraft.network.FriendlyByteBuf;
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
        // 旧 attachment 客户端处理器已经随 legacy batch 算法删除，这里保留消息结构给命令层做最小兼容占位。
        context.setPacketHandled(true);
    }
}
