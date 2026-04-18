package com.PinkCats.bandwidthoptimizer.Old.network.message;

import com.PinkCats.bandwidthoptimizer.Old.network.client.ClientChunkCacheManager;
import io.netty.buffer.Unpooled;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.resources.ResourceLocation;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.fml.DistExecutor;
import net.minecraftforge.network.NetworkEvent;

import java.util.function.Supplier;

public record ClientboundChunkCacheDeltaPacket(
        long sessionId,
        ResourceLocation dimensionId,
        int chunkX,
        int chunkZ,
        byte[] encodedPacketBytes
) {

    public static void encode(ClientboundChunkCacheDeltaPacket packet, FriendlyByteBuf buffer) {
        buffer.writeVarLong(packet.sessionId());
        buffer.writeResourceLocation(packet.dimensionId());
        buffer.writeVarInt(packet.chunkX());
        buffer.writeVarInt(packet.chunkZ());
        buffer.writeByteArray(packet.encodedPacketBytes());
    }

    public static ClientboundChunkCacheDeltaPacket decode(FriendlyByteBuf buffer) {
        return new ClientboundChunkCacheDeltaPacket(
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

    public static void handle(ClientboundChunkCacheDeltaPacket packet, Supplier<NetworkEvent.Context> contextSupplier) {
        NetworkEvent.Context context = contextSupplier.get();
        context.enqueueWork(() -> DistExecutor.unsafeRunWhenOn(
                Dist.CLIENT,
                () -> () -> ClientChunkCacheManager.handleDelta(packet)
        ));
        context.setPacketHandled(true);
    }
}
