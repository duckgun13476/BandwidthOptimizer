package com.PinkCats.bandwidthoptimizer.chunk.packet;

import io.netty.buffer.Unpooled;
import net.minecraft.network.ConnectionProtocol;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.protocol.Packet;
import net.minecraft.network.protocol.PacketFlow;
import net.minecraft.network.protocol.game.ClientGamePacketListener;

public final class ClientboundPlayPacketCodec {

    private ClientboundPlayPacketCodec() {
    }

    // 这个函数把原版 clientbound PLAY 包编码成“packet id + packet body”字节，供 chunk cache 直接复用。
    public static byte[] encodePacket(Packet<?> packet) {
        FriendlyByteBuf buffer = new FriendlyByteBuf(Unpooled.buffer());
        try {
            int packetId = ConnectionProtocol.PLAY.getPacketId(PacketFlow.CLIENTBOUND, packet);
            buffer.writeVarInt(packetId);
            @SuppressWarnings("unchecked")
            Packet<ClientGamePacketListener> typedPacket = (Packet<ClientGamePacketListener>) packet;
            typedPacket.write(buffer);
            byte[] bytes = new byte[buffer.readableBytes()];
            buffer.getBytes(0, bytes);
            return bytes;
        } finally {
            buffer.release();
        }
    }

    // 这个函数把缓存里的原始字节恢复成原版 clientbound PLAY 包，后续可以继续回到原版分发链。
    public static Packet<ClientGamePacketListener> decodePacket(byte[] bytes) {
        FriendlyByteBuf buffer = new FriendlyByteBuf(Unpooled.wrappedBuffer(bytes));
        try {
            int packetId = buffer.readVarInt();
            Packet<?> packet = ConnectionProtocol.PLAY.createPacket(PacketFlow.CLIENTBOUND, packetId, buffer);
            if (packet == null) {
                throw new IllegalStateException("Failed to decode clientbound play packet id " + packetId);
            }
            if (buffer.readableBytes() != 0) {
                throw new IllegalStateException("Clientbound play packet id " + packetId + " left " + buffer.readableBytes() + " unread bytes");
            }
            @SuppressWarnings("unchecked")
            Packet<ClientGamePacketListener> typedPacket = (Packet<ClientGamePacketListener>) packet;
            return typedPacket;
        } finally {
            buffer.release();
        }
    }
}
