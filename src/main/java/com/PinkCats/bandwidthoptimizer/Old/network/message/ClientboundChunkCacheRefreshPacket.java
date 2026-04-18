package com.PinkCats.bandwidthoptimizer.Old.network.message;

import com.PinkCats.bandwidthoptimizer.Old.network.client.ClientChunkCacheManager;
import io.netty.buffer.Unpooled;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.resources.ResourceLocation;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.fml.DistExecutor;
import net.minecraftforge.network.NetworkEvent;

import java.util.function.Supplier;

public record ClientboundChunkCacheRefreshPacket(
        long sessionId,
        ResourceLocation dimensionId,
        int chunkX,
        int chunkZ,
        byte[] encodedPacketBytes
) {

    public static void encode(ClientboundChunkCacheRefreshPacket packet, FriendlyByteBuf buffer) {
        buffer.writeVarLong(packet.sessionId());
        buffer.writeResourceLocation(packet.dimensionId());
        buffer.writeVarInt(packet.chunkX());
        buffer.writeVarInt(packet.chunkZ());
        buffer.writeByteArray(packet.encodedPacketBytes());
    }

    public static ClientboundChunkCacheRefreshPacket decode(FriendlyByteBuf buffer) {
        return new ClientboundChunkCacheRefreshPacket(
                buffer.readVarLong(),
                buffer.readResourceLocation(),
                buffer.readVarInt(),
                buffer.readVarInt(),
                buffer.readByteArray()
        );
    }

    public int encodedSize() {
        FriendlyByteBuf buffer = new FriendlyByteBuf(Unpooled.buffer());
        try {
            encode(this, buffer);
            return buffer.readableBytes();
        } finally {
            buffer.release();
        }
    }

    public static void handle(ClientboundChunkCacheRefreshPacket packet, Supplier<NetworkEvent.Context> contextSupplier) {
        NetworkEvent.Context context = contextSupplier.get();
        context.enqueueWork(() -> DistExecutor.unsafeRunWhenOn(
                Dist.CLIENT,
                () -> () -> ClientChunkCacheManager.handleRefresh(packet)
        ));
        context.setPacketHandled(true);
    }
}
