package com.PinkCats.bandwidthoptimizer.Old.network.algorithm.play;

import com.PinkCats.bandwidthoptimizer.Old.network.client.ClientPlayPacketBatchHandler;
import com.PinkCats.bandwidthoptimizer.Old.network.client.ClientRespawnTransportBarrier;
import io.netty.buffer.Unpooled;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.fml.DistExecutor;
import net.minecraftforge.network.NetworkEvent;

import java.util.function.Supplier;

public record ClientboundPlayPacketBatchPacket(
        long sessionId,
        long sequence,
        int[] packetIdTable,
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

    public static void encode(ClientboundPlayPacketBatchPacket packet, FriendlyByteBuf buffer) {
        buffer.writeVarLong(packet.sessionId());
        buffer.writeVarLong(packet.sequence());
        buffer.writeVarInt(packet.packetIdTable().length);
        for (int packetId : packet.packetIdTable()) {
            buffer.writeVarInt(packetId);
        }
        buffer.writeUtf(packet.algorithmId());
        buffer.writeBoolean(packet.resetSession());
        buffer.writeVarInt(packet.packetCount());
        buffer.writeVarInt(packet.encodedBytes().length);
        buffer.writeBytes(packet.encodedBytes());
    }

    public static ClientboundPlayPacketBatchPacket decode(FriendlyByteBuf buffer) {
        long sessionId = buffer.readVarLong();
        long sequence = buffer.readVarLong();
        int[] packetIdTable = new int[buffer.readVarInt()];
        for (int index = 0; index < packetIdTable.length; index++) {
            packetIdTable[index] = buffer.readVarInt();
        }
        String algorithmId = buffer.readUtf();
        boolean resetSession = buffer.readBoolean();
        int packetCount = buffer.readVarInt();
        byte[] encodedBytes = new byte[buffer.readVarInt()];
        buffer.readBytes(encodedBytes);
        return new ClientboundPlayPacketBatchPacket(sessionId, sequence, packetIdTable, algorithmId, resetSession, encodedBytes, packetCount);
    }

    public static void handle(ClientboundPlayPacketBatchPacket packet, Supplier<NetworkEvent.Context> contextSupplier) {
        NetworkEvent.Context context = contextSupplier.get();
        if (ClientRespawnTransportBarrier.shouldDropInternalTransport()) {
            context.setPacketHandled(true);
            return;
        }
        DistExecutor.unsafeRunWhenOn(
                Dist.CLIENT,
                () -> () -> ClientPlayPacketBatchHandler.handle(packet)
        );
        context.setPacketHandled(true);
    }
}
