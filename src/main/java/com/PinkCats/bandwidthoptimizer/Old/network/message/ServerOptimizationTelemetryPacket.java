package com.PinkCats.bandwidthoptimizer.Old.network.message;

import com.PinkCats.bandwidthoptimizer.Old.network.client.ClientOptimizationTelemetryHandler;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.fml.DistExecutor;
import net.minecraftforge.network.NetworkEvent;

import java.util.function.Supplier;

public record ServerOptimizationTelemetryPacket(
        long bypassTotalBytes,
        long bypassTotalPackets,
        long bypassRecentBytes,
        long bypassRecentPackets,
        long chunkCacheRawTotalBytes,
        long chunkCacheRawRecentBytes,
        long chunkCacheSentTotalBytes,
        long chunkCacheSentRecentBytes,
        long chunkCacheHitTotalPackets,
        long chunkCacheHitRecentPackets,
        long chunkCacheRefreshTotalPackets,
        long chunkCacheRefreshRecentPackets
) {

    public static void encode(ServerOptimizationTelemetryPacket packet, FriendlyByteBuf buffer) {
        buffer.writeVarLong(packet.bypassTotalBytes());
        buffer.writeVarLong(packet.bypassTotalPackets());
        buffer.writeVarLong(packet.bypassRecentBytes());
        buffer.writeVarLong(packet.bypassRecentPackets());
        buffer.writeVarLong(packet.chunkCacheRawTotalBytes());
        buffer.writeVarLong(packet.chunkCacheRawRecentBytes());
        buffer.writeVarLong(packet.chunkCacheSentTotalBytes());
        buffer.writeVarLong(packet.chunkCacheSentRecentBytes());
        buffer.writeVarLong(packet.chunkCacheHitTotalPackets());
        buffer.writeVarLong(packet.chunkCacheHitRecentPackets());
        buffer.writeVarLong(packet.chunkCacheRefreshTotalPackets());
        buffer.writeVarLong(packet.chunkCacheRefreshRecentPackets());
    }

    public static ServerOptimizationTelemetryPacket decode(FriendlyByteBuf buffer) {
        return new ServerOptimizationTelemetryPacket(
                buffer.readVarLong(),
                buffer.readVarLong(),
                buffer.readVarLong(),
                buffer.readVarLong(),
                buffer.readVarLong(),
                buffer.readVarLong(),
                buffer.readVarLong(),
                buffer.readVarLong(),
                buffer.readVarLong(),
                buffer.readVarLong(),
                buffer.readVarLong(),
                buffer.readVarLong()
        );
    }

    public static void handle(ServerOptimizationTelemetryPacket packet, Supplier<NetworkEvent.Context> contextSupplier) {
        NetworkEvent.Context context = contextSupplier.get();
        context.enqueueWork(() -> DistExecutor.unsafeRunWhenOn(
                Dist.CLIENT,
                () -> () -> ClientOptimizationTelemetryHandler.handle(packet)
        ));
        context.setPacketHandled(true);
    }
}
