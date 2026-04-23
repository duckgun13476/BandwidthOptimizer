package com.PinkCats.bandwidthoptimizer.Old.network.message;

import net.minecraft.network.FriendlyByteBuf;
import net.minecraftforge.network.NetworkEvent;

import java.util.function.Supplier;

public record ClientToServerOptimizationTelemetrySubscriptionPacket(boolean subscribed) {

    public static void encode(ClientToServerOptimizationTelemetrySubscriptionPacket packet, FriendlyByteBuf buffer) {
        buffer.writeBoolean(packet.subscribed());
    }

    public static ClientToServerOptimizationTelemetrySubscriptionPacket decode(FriendlyByteBuf buffer) {
        return new ClientToServerOptimizationTelemetrySubscriptionPacket(buffer.readBoolean());
    }

    public static void handle(
            ClientToServerOptimizationTelemetrySubscriptionPacket packet,
            Supplier<NetworkEvent.Context> contextSupplier
    ) {
        NetworkEvent.Context context = contextSupplier.get();
        // 旧 telemetry 订阅链已经和被删除的 legacy transport 算法一起下线，这里只保留空处理壳避免遗留引用断编译。
        context.setPacketHandled(true);
    }
}
