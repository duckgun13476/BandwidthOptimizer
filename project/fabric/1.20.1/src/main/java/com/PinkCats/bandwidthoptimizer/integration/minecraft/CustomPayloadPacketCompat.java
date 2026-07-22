package com.PinkCats.bandwidthoptimizer.integration.minecraft;

import com.PinkCats.bandwidthoptimizer.mixin.minecraft.ClientboundCustomPayloadPacketAccessor;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.protocol.Packet;
import net.minecraft.network.protocol.game.ClientboundCustomPayloadPacket;
import net.minecraft.network.protocol.game.ServerboundCustomPayloadPacket;
import net.minecraft.resources.ResourceLocation;

public final class CustomPayloadPacketCompat {
    private CustomPayloadPacketCompat() {}

    public static String payloadChannel(Packet<?> packet) {
        ResourceLocation identifier = payloadIdentifier(packet);
        return identifier == null ? "" : identifier.toString();
    }

    public static Object payloadObject(Packet<?> packet) {
        return null;
    }

    public static byte[] payloadBytes(Packet<?> packet) {
        if (!(packet instanceof ClientboundCustomPayloadPacket customPayloadPacket)) {
            return null;
        }
        FriendlyByteBuf data = ((ClientboundCustomPayloadPacketAccessor) customPayloadPacket).bandwidthoptimizer$data();
        byte[] bytes = new byte[data.readableBytes()];
        data.getBytes(data.readerIndex(), bytes);
        return bytes;
    }

    private static ResourceLocation payloadIdentifier(Packet<?> packet) {
        if (packet instanceof ClientboundCustomPayloadPacket customPayloadPacket) {
            return customPayloadPacket.getIdentifier();
        }
        if (packet instanceof ServerboundCustomPayloadPacket customPayloadPacket) {
            return customPayloadPacket.getIdentifier();
        }
        return null;
    }
}
