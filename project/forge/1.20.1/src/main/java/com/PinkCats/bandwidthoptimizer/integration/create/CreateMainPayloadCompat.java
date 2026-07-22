package com.PinkCats.bandwidthoptimizer.integration.create;

import com.PinkCats.bandwidthoptimizer.integration.minecraft.CustomPayloadPacketCompat;
import net.minecraft.network.protocol.Packet;

/** Classifies presentation-only packets in Create 0.5.1's Forge main channel. */
public final class CreateMainPayloadCompat {

    private static final String CHANNEL = "create:main";

    private CreateMainPayloadCompat() {
    }

    public static String presentationOnlyKey(Packet<?> packet) {
        if (!CHANNEL.equals(CustomPayloadPacketCompat.payloadChannel(packet))) {
            return null;
        }
        int discriminator = readVarInt(CustomPayloadPacketCompat.payloadBytes(packet));
        return switch (discriminator) {
            case 58 -> "glue_effect";
            case 62 -> "fluid_splash";
            case 65 -> "block_highlight";
            case 66 -> "tunnel_flap";
            case 67 -> "funnel_flap";
            case 69 -> "soul_pulse";
            default -> null;
        };
    }

    private static int readVarInt(byte[] payload) {
        if (payload == null || payload.length == 0) {
            return -1;
        }
        int value = 0;
        for (int index = 0, shift = 0; index < payload.length && shift < 35; index++, shift += 7) {
            int next = payload[index] & 0xFF;
            value |= (next & 0x7F) << shift;
            if ((next & 0x80) == 0) {
                return value;
            }
        }
        return -1;
    }
}
