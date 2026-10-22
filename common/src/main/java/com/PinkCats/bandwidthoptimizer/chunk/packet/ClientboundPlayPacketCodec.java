package com.PinkCats.bandwidthoptimizer.chunk.packet;

import io.netty.buffer.Unpooled;
import net.minecraft.network.ConnectionProtocol;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.protocol.Packet;
import net.minecraft.network.protocol.PacketFlow;
import net.minecraft.network.protocol.game.ClientGamePacketListener;

public final class ClientboundPlayPacketCodec {

    private ClientboundPlayPacketCodec() {}

    public static byte[] encodePacket(Packet<?> packet, net.minecraft.core.RegistryAccess registryAccess) {
        return encodePacket(packet);
    }

    public static java.util.function.Function<Packet<?>, byte[]> encoderForRegistry(net.minecraft.core.RegistryAccess registryAccess) {
        return ClientboundPlayPacketCodec::encodePacket;
    }

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

    public static java.util.function.Function<byte[], Packet<ClientGamePacketListener>> decoderForRegistry(net.minecraft.core.RegistryAccess registryAccess) {
        return bytes -> decodePacket(bytes, true);
    }

    public static Packet<ClientGamePacketListener> decodePacket(byte[] bytes) {
        return decodePacket(bytes, false);
    }

    private static Packet<ClientGamePacketListener> decodePacket(byte[] bytes, boolean readOnly) {
        FriendlyByteBuf buffer = new FriendlyByteBuf(readOnly ? Unpooled.wrappedBuffer(bytes).asReadOnly() : Unpooled.wrappedBuffer(bytes));
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
