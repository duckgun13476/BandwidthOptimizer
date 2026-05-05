package com.PinkCats.bandwidthoptimizer.chunk.packet;

import net.minecraft.network.protocol.Packet;
import net.minecraft.network.protocol.game.ClientboundBlockUpdatePacket;

import java.util.Set;

public final class ChunkHeavyProtocolBypassPacketList {

    private static final Set<Class<?>> PACKET_CLASSES = Set.of(
            ClientboundBlockUpdatePacket.class
    );

    private ChunkHeavyProtocolBypassPacketList() {}

    // Bypass light packet, only for <200b packet
    public static boolean shouldBypassHeavyChunkProtocol(Packet<?> packet) {
        return packet != null && PACKET_CLASSES.contains(packet.getClass());
    }
}
