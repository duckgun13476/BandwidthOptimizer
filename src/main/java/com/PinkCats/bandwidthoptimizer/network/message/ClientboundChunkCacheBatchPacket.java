package com.PinkCats.bandwidthoptimizer.network.message;

import com.PinkCats.bandwidthoptimizer.network.client.ClientChunkCacheBatchHandler;
import com.PinkCats.bandwidthoptimizer.network.client.ClientRespawnTransportBarrier;
import io.netty.buffer.Unpooled;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.fml.DistExecutor;
import net.minecraftforge.network.NetworkEvent;

import java.util.function.Supplier;

public record ClientboundChunkCacheBatchPacket(
        String algorithmId,
        boolean resetSession,
        byte[] encodedBytes,
        int packetCount
) {

    public int encodedSize() {
        FriendlyByteBuf buffer = new FriendlyByteBuf(Unpooled.buffer());
        try {
            encode(this, buffer);
            return buffer.readableBytes();
        } finally {
            buffer.release();
        }
    }

    public static void encode(ClientboundChunkCacheBatchPacket packet, FriendlyByteBuf buffer) {
        buffer.writeUtf(packet.algorithmId());
        buffer.writeBoolean(packet.resetSession());
        buffer.writeVarInt(packet.packetCount());
        buffer.writeVarInt(packet.encodedBytes().length);
        buffer.writeBytes(packet.encodedBytes());
    }

    public static ClientboundChunkCacheBatchPacket decode(FriendlyByteBuf buffer) {
        String algorithmId = buffer.readUtf();
        boolean resetSession = buffer.readBoolean();
        int packetCount = buffer.readVarInt();
        byte[] encodedBytes = new byte[buffer.readVarInt()];
        buffer.readBytes(encodedBytes);
        return new ClientboundChunkCacheBatchPacket(
                algorithmId,
                resetSession,
                encodedBytes,
                packetCount
        );
    }

    public static void handle(ClientboundChunkCacheBatchPacket packet, Supplier<NetworkEvent.Context> contextSupplier) {
        NetworkEvent.Context context = contextSupplier.get();
        if (ClientRespawnTransportBarrier.shouldDropInternalTransport()) {
            context.setPacketHandled(true);
            return;
        }
        context.enqueueWork(() -> DistExecutor.unsafeRunWhenOn(
                Dist.CLIENT,
                () -> () -> ClientChunkCacheBatchHandler.handleBatch(packet)
        ));
        context.setPacketHandled(true);
    }
}
