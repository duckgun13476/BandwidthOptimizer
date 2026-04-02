package com.PinkCats.bandwidthoptimizer.network.message;

import com.PinkCats.bandwidthoptimizer.network.client.ClientChunkCacheManager;
import io.netty.buffer.Unpooled;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.fml.DistExecutor;
import net.minecraftforge.network.NetworkEvent;

import java.util.function.Supplier;

public record ClientboundChunkCacheUsePacket(
        long sessionId,
        int chunkX,
        int chunkZ
) {

    public static void encode(ClientboundChunkCacheUsePacket packet, FriendlyByteBuf buffer) {
        buffer.writeVarLong(packet.sessionId());
        buffer.writeVarInt(packet.chunkX());
        buffer.writeVarInt(packet.chunkZ());
    }

    public static ClientboundChunkCacheUsePacket decode(FriendlyByteBuf buffer) {
        return new ClientboundChunkCacheUsePacket(
                buffer.readVarLong(),
                buffer.readVarInt(),
                buffer.readVarInt()
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

    public static void handle(ClientboundChunkCacheUsePacket packet, Supplier<NetworkEvent.Context> contextSupplier) {
        NetworkEvent.Context context = contextSupplier.get();
        context.enqueueWork(() -> DistExecutor.unsafeRunWhenOn(
                Dist.CLIENT,
                () -> () -> ClientChunkCacheManager.handleUse(packet)
        ));
        context.setPacketHandled(true);
    }
}
