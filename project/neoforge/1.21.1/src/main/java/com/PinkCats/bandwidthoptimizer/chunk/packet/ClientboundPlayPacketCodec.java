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
        return encodePacket(packet, RegistryAccess.EMPTY, CLIENTBOUND_PLAY_PROTOCOL);
    }

    public static byte[] encodePacket(Packet<?> packet, RegistryAccess registryAccess) {
        return encoderForRegistry(registryAccess).apply(packet);
    }

    public static java.util.function.Function<Packet<?>, byte[]> encoderForRegistry(RegistryAccess registryAccess) {
        var protocol = registryAccess == RegistryAccess.EMPTY ? CLIENTBOUND_PLAY_PROTOCOL
                : GameProtocols.CLIENTBOUND_TEMPLATE.bind(RegistryFriendlyByteBuf.decorator(registryAccess, ConnectionType.OTHER));
        return packet -> encodePacket(packet, registryAccess, protocol);
    }

    private static byte[] encodePacket(Packet<?> packet, RegistryAccess registryAccess, ProtocolInfo<ClientGamePacketListener> protocol) {
        RegistryFriendlyByteBuf buffer = new RegistryFriendlyByteBuf(Unpooled.buffer(), registryAccess, ConnectionType.OTHER);
        try {
            @SuppressWarnings("unchecked")
            Packet<ClientGamePacketListener> typedPacket = (Packet<ClientGamePacketListener>) packet;
            protocol.codec().encode(buffer, typedPacket);
            byte[] bytes = new byte[buffer.readableBytes()];
            buffer.getBytes(0, bytes);
            return bytes;
        } catch (RuntimeException exception) {
            return null;
        } finally {
            buffer.release();
        }
    }

    public static java.util.function.Function<byte[], Packet<ClientGamePacketListener>> decoderForRegistry(RegistryAccess registryAccess) {
        var protocol = registryAccess == RegistryAccess.EMPTY ? CLIENTBOUND_PLAY_PROTOCOL
                : GameProtocols.CLIENTBOUND_TEMPLATE.bind(RegistryFriendlyByteBuf.decorator(registryAccess, ConnectionType.OTHER));
        return bytes -> decodePacket(bytes, registryAccess, protocol, true);
    }

    public static Packet<ClientGamePacketListener> decodePacket(byte[] bytes) {
        return decodePacket(bytes, RegistryAccess.EMPTY, CLIENTBOUND_PLAY_PROTOCOL, false);
    }

    private static Packet<ClientGamePacketListener> decodePacket(byte[] bytes, RegistryAccess registryAccess, ProtocolInfo<ClientGamePacketListener> protocol, boolean readOnly) {
        RegistryFriendlyByteBuf buffer = new RegistryFriendlyByteBuf((readOnly ? Unpooled.wrappedBuffer(bytes).asReadOnly() : Unpooled.wrappedBuffer(bytes)), registryAccess, ConnectionType.OTHER);
        try {
            Packet<?> packet = protocol.codec().decode(buffer);
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
