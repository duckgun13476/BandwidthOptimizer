package com.PinkCats.bandwidthoptimizer.compat.minecraft;

import net.minecraft.network.protocol.Packet;
import net.minecraft.network.protocol.game.ClientboundCustomPayloadPacket;
import net.minecraft.network.protocol.game.ServerboundCustomPayloadPacket;
import net.minecraft.resources.ResourceLocation;

public final class CustomPayloadPacketCompat {
    private CustomPayloadPacketCompat() {
    }

    public static String payloadChannel(Packet<?> packet) {
        ResourceLocation identifier = payloadIdentifier(packet);
        return identifier == null ? "" : identifier.toString();
    }

    public static Object payloadObject(Packet<?> packet) {
        return null;
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
