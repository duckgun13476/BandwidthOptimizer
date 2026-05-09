package com.PinkCats.bandwidthoptimizer.compat.minecraft;

import net.minecraft.network.protocol.Packet;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.network.protocol.common.ClientboundCustomPayloadPacket;
import net.minecraft.network.protocol.common.ServerboundCustomPayloadPacket;
import net.minecraft.resources.ResourceLocation;

public final class CustomPayloadPacketCompat {
    private CustomPayloadPacketCompat() {
    }

    public static String payloadChannel(Packet<?> packet) {
        ResourceLocation identifier = payloadIdentifier(packet);
        return identifier == null ? "" : identifier.toString();
    }
    private static ResourceLocation payloadIdentifier(Packet<?> packet) {
        CustomPacketPayload payload = null;
        if (packet instanceof ClientboundCustomPayloadPacket customPayloadPacket) {
            payload = customPayloadPacket.payload();
        } else if (packet instanceof ServerboundCustomPayloadPacket customPayloadPacket) {
            payload = customPayloadPacket.payload();
        }
        return payload == null ? null : payload.type().id();
    }
}
