package com.PinkCats.bandwidthoptimizer.network.message;

import com.PinkCats.bandwidthoptimizer.network.client.ClientServerOptimizationTelemetryHandler;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.fml.DistExecutor;
import net.minecraftforge.network.NetworkEvent;

import java.util.function.Supplier;

public record ServerOverallOptimizationTelemetryPacket(
        long totalRawBytes,
        long totalBatchedBytes,
        long totalBatchCount,
        long totalPacketCount,
        long totalBypassBytes,
        long totalChunkCacheRawBytes,
        long totalChunkCacheSentBytes,
        long totalChunkCacheHitPackets,
        long totalChunkCacheRefreshPackets,
        long recentRawBytes,
        long recentBatchedBytes,
        long recentBatchCount,
        long recentPacketCount,
        long recentBypassBytes,
        long recentChunkCacheRawBytes,
        long recentChunkCacheSentBytes,
        int activeConnections,
        String algorithmId
) {

    public static void encode(ServerOverallOptimizationTelemetryPacket packet, FriendlyByteBuf buffer) {
        buffer.writeVarLong(packet.totalRawBytes());
        buffer.writeVarLong(packet.totalBatchedBytes());
        buffer.writeVarLong(packet.totalBatchCount());
        buffer.writeVarLong(packet.totalPacketCount());
        buffer.writeVarLong(packet.totalBypassBytes());
        buffer.writeVarLong(packet.totalChunkCacheRawBytes());
        buffer.writeVarLong(packet.totalChunkCacheSentBytes());
        buffer.writeVarLong(packet.totalChunkCacheHitPackets());
        buffer.writeVarLong(packet.totalChunkCacheRefreshPackets());
        buffer.writeVarLong(packet.recentRawBytes());
        buffer.writeVarLong(packet.recentBatchedBytes());
        buffer.writeVarLong(packet.recentBatchCount());
        buffer.writeVarLong(packet.recentPacketCount());
        buffer.writeVarLong(packet.recentBypassBytes());
        buffer.writeVarLong(packet.recentChunkCacheRawBytes());
        buffer.writeVarLong(packet.recentChunkCacheSentBytes());
        buffer.writeVarInt(packet.activeConnections());
        buffer.writeUtf(packet.algorithmId());
    }

    public static ServerOverallOptimizationTelemetryPacket decode(FriendlyByteBuf buffer) {
        return new ServerOverallOptimizationTelemetryPacket(
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
                buffer.readVarLong(),
                buffer.readVarLong(),
                buffer.readVarLong(),
                buffer.readVarLong(),
                buffer.readVarLong(),
                buffer.readVarInt(),
                buffer.readUtf()
        );
    }

    public static void handle(ServerOverallOptimizationTelemetryPacket packet, Supplier<NetworkEvent.Context> contextSupplier) {
        NetworkEvent.Context context = contextSupplier.get();
        context.enqueueWork(() -> DistExecutor.unsafeRunWhenOn(
                Dist.CLIENT,
                () -> () -> ClientServerOptimizationTelemetryHandler.handle(packet)
        ));
        context.setPacketHandled(true);
    }
}
