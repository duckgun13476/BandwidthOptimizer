package com.PinkCats.bandwidthoptimizer.integration.minecraft;

import io.netty.buffer.ByteBuf;
import net.minecraft.network.protocol.Packet;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.network.protocol.common.ClientboundCustomPayloadPacket;
import net.minecraft.network.protocol.common.ServerboundCustomPayloadPacket;
import net.minecraft.resources.ResourceLocation;

import java.lang.reflect.Field;

public final class CustomPayloadPacketCompat {
    private CustomPayloadPacketCompat() {
    }

    public static String payloadChannel(Packet<?> packet) {
        ResourceLocation identifier = payloadIdentifier(packet);
        return identifier == null ? "" : identifier.toString();
    }

    public static Object payloadObject(Packet<?> packet) {
        if (packet instanceof ClientboundCustomPayloadPacket customPayloadPacket) {
            return customPayloadPacket.payload();
        } else if (packet instanceof ServerboundCustomPayloadPacket customPayloadPacket) {
            return customPayloadPacket.payload();
        }
        return null;
    }

    public static byte[] payloadBytes(Packet<?> packet) {
        Object payload = payloadObject(packet);
        if (payload == null) {
            return null;
        }
        for (Class<?> type = payload.getClass(); type != null; type = type.getSuperclass()) {
            for (Field field : type.getDeclaredFields()) {
                if (!ByteBuf.class.isAssignableFrom(field.getType())) {
                    continue;
                }
                try {
                    field.setAccessible(true);
                    ByteBuf buffer = (ByteBuf) field.get(payload);
                    if (buffer == null) {
                        continue;
                    }
                    byte[] bytes = new byte[buffer.readableBytes()];
                    buffer.getBytes(buffer.readerIndex(), bytes);
                    return bytes;
                } catch (ReflectiveOperationException | RuntimeException ignored) {
                    // Fall through to direct delivery when the loader payload has no readable buffer.
                }
            }
        }
        return null;
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
