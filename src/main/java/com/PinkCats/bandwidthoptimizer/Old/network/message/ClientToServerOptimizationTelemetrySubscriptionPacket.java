package com.PinkCats.bandwidthoptimizer.Old.network.message;

import com.PinkCats.bandwidthoptimizer.Old.network.algorithm.play.ServerOptimizationTelemetryManager;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.server.level.ServerPlayer;
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
        context.enqueueWork(() -> {
            ServerPlayer sender = context.getSender();
            if (sender != null) {
                ServerOptimizationTelemetryManager.setSubscribed(sender, packet.subscribed());
            }
        });
        context.setPacketHandled(true);
    }
}
