package com.PinkCats.bandwidthoptimizer.network.algorithm.play;

import com.PinkCats.bandwidthoptimizer.Bandwidthoptimizer;
import io.netty.buffer.Unpooled;
import net.minecraft.network.ConnectionProtocol;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.protocol.Packet;
import net.minecraft.network.protocol.PacketFlow;
import net.minecraft.network.protocol.game.ClientGamePacketListener;
import net.minecraft.network.protocol.game.ClientboundBlockEntityDataPacket;
import net.minecraft.network.protocol.game.ClientboundBlockUpdatePacket;
import net.minecraft.network.protocol.game.ClientboundContainerSetContentPacket;
import net.minecraft.network.protocol.game.ClientboundContainerSetSlotPacket;
import net.minecraft.network.protocol.game.ClientboundCustomPayloadPacket;
import net.minecraft.network.protocol.game.ClientboundLevelChunkWithLightPacket;
import net.minecraft.network.protocol.game.ClientboundLightUpdatePacket;
import net.minecraft.network.protocol.game.ClientboundMoveEntityPacket;
import net.minecraft.network.protocol.game.ClientboundSetTimePacket;
import net.minecraft.network.protocol.game.ClientboundRotateHeadPacket;
import net.minecraft.network.protocol.game.ClientboundSectionBlocksUpdatePacket;
import net.minecraft.network.protocol.game.ClientboundSetEntityDataPacket;
import net.minecraft.network.protocol.game.ClientboundSetEntityMotionPacket;
import net.minecraft.network.protocol.game.ClientboundSetEquipmentPacket;
import net.minecraft.network.protocol.game.ClientboundTeleportEntityPacket;
import net.minecraft.network.protocol.game.ClientboundTabListPacket;
import net.minecraft.network.protocol.game.ClientboundUpdateMobEffectPacket;
import net.minecraft.network.protocol.game.ClientboundUpdateAttributesPacket;
import net.minecraft.resources.ResourceLocation;

import java.util.List;
import java.util.Set;

public final class PlayPacketReplaySupport {

    public static final Set<Class<? extends Packet<?>>> WHITELIST = Set.of(
            ClientboundBlockEntityDataPacket.class,
            ClientboundUpdateAttributesPacket.class,
            ClientboundSectionBlocksUpdatePacket.class,
            ClientboundBlockUpdatePacket.class,
            ClientboundContainerSetContentPacket.class,
            ClientboundContainerSetSlotPacket.class,
            ClientboundCustomPayloadPacket.class,
            ClientboundLevelChunkWithLightPacket.class,
            ClientboundLightUpdatePacket.class,
            ClientboundMoveEntityPacket.Pos.class,
            ClientboundMoveEntityPacket.PosRot.class,
            ClientboundMoveEntityPacket.Rot.class,
            ClientboundSetEntityMotionPacket.class,
            ClientboundSetEntityDataPacket.class,
            ClientboundSetEquipmentPacket.class,
            ClientboundRotateHeadPacket.class,
            ClientboundTeleportEntityPacket.class,
            ClientboundSetTimePacket.class,
            ClientboundTabListPacket.class,
            ClientboundUpdateMobEffectPacket.class
    );

    private static final ResourceLocation INTERNAL_CHANNEL = ResourceLocation.fromNamespaceAndPath(Bandwidthoptimizer.MODID, "main");
    private static final Set<ResourceLocation> CUSTOM_PAYLOAD_BYPASS_CHANNELS = Set.of(
            ResourceLocation.fromNamespaceAndPath("yes_steve_model", "2_6_0")
    );

    private PlayPacketReplaySupport() {
    }

    public static boolean isClientboundPlayPacket(Packet<?> packet) {
        if (packet == null) {
            return false;
        }
        return ConnectionProtocol.getProtocolForPacket(packet) == ConnectionProtocol.PLAY
                && ConnectionProtocol.PLAY.getPacketId(PacketFlow.CLIENTBOUND, packet) >= 0;
    }

    public static boolean shouldReplay(Packet<?> packet) {
        return bypassReason(packet) == null && WHITELIST.contains(packet.getClass());
    }

    public static String bypassReason(Packet<?> packet) {
        if (packet == null) {
            return "packet_null";
        }
        if (!isClientboundPlayPacket(packet)) {
            return "not_clientbound_play";
        }
        if (packet instanceof ClientboundCustomPayloadPacket customPayloadPacket
                && INTERNAL_CHANNEL.equals(customPayloadPacket.getIdentifier())) {
            return "internal_transport";
        }
        if (packet instanceof ClientboundCustomPayloadPacket customPayloadPacket
                && CUSTOM_PAYLOAD_BYPASS_CHANNELS.contains(customPayloadPacket.getIdentifier())) {
            return "custom_payload_bypass_channel:" + customPayloadPacket.getIdentifier();
        }
        if (!WHITELIST.contains(packet.getClass())) {
            return "not_in_whitelist";
        }
        return null;
    }

    public static boolean isInternalTransport(Packet<?> packet) {
        return packet instanceof ClientboundCustomPayloadPacket customPayloadPacket
                && INTERNAL_CHANNEL.equals(customPayloadPacket.getIdentifier());
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

    public static int packetId(Packet<?> packet) {
        return ConnectionProtocol.PLAY.getPacketId(PacketFlow.CLIENTBOUND, packet);
    }

    public static byte[] encodePacketBody(Packet<?> packet) {
        FriendlyByteBuf buffer = new FriendlyByteBuf(Unpooled.buffer());
        try {
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

    public static Packet<ClientGamePacketListener> decodePacket(int packetId, byte[] bodyBytes) {
        FriendlyByteBuf buffer = new FriendlyByteBuf(Unpooled.wrappedBuffer(bodyBytes));
        try {
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

    public static long totalEncodedBytes(List<? extends Packet<?>> packets) {
        long total = 0L;
        for (Packet<?> packet : packets) {
            total += encodePacket(packet).length;
        }
        return total;
    }

    public static int estimatedEncodedBytes(Packet<?> packet) {
        return encodePacket(packet).length;
    }
}
