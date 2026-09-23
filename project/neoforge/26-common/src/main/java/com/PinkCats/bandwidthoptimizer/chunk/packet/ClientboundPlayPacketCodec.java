package com.PinkCats.bandwidthoptimizer.chunk.packet;

import io.netty.buffer.Unpooled;
import net.minecraft.core.RegistryAccess;
import net.minecraft.network.ProtocolInfo;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.protocol.Packet;
import net.minecraft.network.protocol.game.ClientGamePacketListener;
import net.minecraft.network.protocol.game.GameProtocols;
import net.neoforged.neoforge.network.connection.ConnectionType;

public final class ClientboundPlayPacketCodec {

    private static final ProtocolInfo<ClientGamePacketListener> CLIENTBOUND_PLAY_PROTOCOL =
            GameProtocols.CLIENTBOUND_TEMPLATE.bind(RegistryFriendlyByteBuf.decorator(RegistryAccess.EMPTY, ConnectionType.OTHER));

    private ClientboundPlayPacketCodec() {}

    public static byte[] encodePacket(Packet<?> packet) {
        RegistryFriendlyByteBuf buffer = new RegistryFriendlyByteBuf(Unpooled.buffer(), RegistryAccess.EMPTY, ConnectionType.OTHER);
        try {
            @SuppressWarnings("unchecked")
            Packet<ClientGamePacketListener> typedPacket = (Packet<ClientGamePacketListener>) packet;
            CLIENTBOUND_PLAY_PROTOCOL.codec().encode(buffer, typedPacket);
            byte[] bytes = new byte[buffer.readableBytes()];
            buffer.getBytes(0, bytes);
            return bytes;
        } catch (RuntimeException exception) {
            return null;
        } finally {
            buffer.release();
        }
    }

    public static Packet<ClientGamePacketListener> decodePacket(byte[] bytes) {
        RegistryFriendlyByteBuf buffer = new RegistryFriendlyByteBuf(Unpooled.wrappedBuffer(bytes), RegistryAccess.EMPTY, ConnectionType.OTHER);
        try {
            Packet<?> packet = CLIENTBOUND_PLAY_PROTOCOL.codec().decode(buffer);
            if (packet == null) {
                throw new IllegalStateException("Failed to decode clientbound play packet");
            }
            if (buffer.readableBytes() != 0) {
                throw new IllegalStateException("Clientbound play packet left " + buffer.readableBytes() + " unread bytes");
            }
            @SuppressWarnings("unchecked")
            Packet<ClientGamePacketListener> typedPacket = (Packet<ClientGamePacketListener>) packet;
            return typedPacket;
        } catch (RuntimeException exception) {
            return null;
        } finally {
            buffer.release();
        }
    }
}
