package com.PinkCats.bandwidthoptimizer.report;

import com.PinkCats.bandwidthoptimizer.compat.minecraft.BlockEntityTypeKeyCompat;
import com.PinkCats.bandwidthoptimizer.compat.minecraft.CustomPayloadPacketCompat;
import net.minecraft.network.protocol.Packet;
import net.minecraft.network.protocol.game.ClientboundBlockEntityDataPacket;
import net.minecraft.resources.Identifier;
import net.minecraft.world.level.block.entity.BlockEntityType;

public final class ChannelTransportPacketRankSourceResolver {

    private ChannelTransportPacketRankSourceResolver() {}

    public static String resolveSourceKey(Packet<?> packet) {
        if (packet == null) {
            return "packet:<unknown>";
        }

        String payloadChannel = CustomPayloadPacketCompat.payloadChannel(packet);
        if (payloadChannel != null && !payloadChannel.isBlank()) {
            return "custom_payload:" + payloadChannel;
        }

        if (packet instanceof ClientboundBlockEntityDataPacket blockEntityDataPacket) {
            return "block_entity:" + resolveBlockEntityTypeKey(blockEntityDataPacket);
        }

        return "packet:" + simpleClassName(packet.getClass().getName());
    }

    private static String resolveBlockEntityTypeKey(ClientboundBlockEntityDataPacket packet) {
        BlockEntityType<?> blockEntityType = packet.getType();
        if (blockEntityType == null) {
            return "<unknown>";
        }
        Identifier typeKey = BlockEntityTypeKeyCompat.keyOf(blockEntityType);
        return typeKey == null ? blockEntityType.toString() : typeKey.toString();
    }

    private static String simpleClassName(String className) {
        if (className == null || className.isBlank()) {
            return "<unknown>";
        }
        int lastDotIndex = className.lastIndexOf('.');
        return lastDotIndex < 0 ? className : className.substring(lastDotIndex + 1);
    }
}
