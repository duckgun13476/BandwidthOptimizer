package com.PinkCats.bandwidthoptimizer.network.message;

import com.PinkCats.bandwidthoptimizer.optimise.chunkcache.ServerChunkCacheManager;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.server.level.ServerPlayer;
import net.minecraftforge.network.NetworkEvent;

import java.util.function.Supplier;

public record ClientToServerChunkCacheMissPacket(
        long sessionId,
        int chunkX,
        int chunkZ
) {

    public static void encode(ClientToServerChunkCacheMissPacket packet, FriendlyByteBuf buffer) {
        buffer.writeVarLong(packet.sessionId());
        buffer.writeVarInt(packet.chunkX());
        buffer.writeVarInt(packet.chunkZ());
    }

    public static ClientToServerChunkCacheMissPacket decode(FriendlyByteBuf buffer) {
        return new ClientToServerChunkCacheMissPacket(
                buffer.readVarLong(),
                buffer.readVarInt(),
                buffer.readVarInt()
        );
    }

    public static void handle(ClientToServerChunkCacheMissPacket packet, Supplier<NetworkEvent.Context> contextSupplier) {
        NetworkEvent.Context context = contextSupplier.get();
        context.enqueueWork(() -> {
            ServerPlayer sender = context.getSender();
            if (sender != null) {
                ServerChunkCacheManager.handleCacheMiss(sender, packet);
            }
        });
        context.setPacketHandled(true);
    }
}
