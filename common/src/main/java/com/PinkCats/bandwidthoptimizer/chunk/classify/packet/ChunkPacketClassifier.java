package com.PinkCats.bandwidthoptimizer.chunk.classify.packet;

import com.PinkCats.bandwidthoptimizer.chunk.classify.ChunkHotspotKind;
import com.PinkCats.bandwidthoptimizer.mixin.minecraft.ClientboundSectionBlocksUpdatePacketAccessor;
import net.minecraft.core.BlockPos;
import net.minecraft.core.SectionPos;
import net.minecraft.network.protocol.Packet;
import net.minecraft.network.protocol.game.ClientboundBlockEntityDataPacket;
import net.minecraft.network.protocol.game.ClientboundBlockUpdatePacket;
import net.minecraft.network.protocol.game.ClientboundLevelChunkWithLightPacket;
import net.minecraft.network.protocol.game.ClientboundLightUpdatePacket;
import net.minecraft.network.protocol.game.ClientboundSectionBlocksUpdatePacket;

public final class ChunkPacketClassifier {

    private ChunkPacketClassifier() {}

    public static ChunkPacketDescriptor classifyOutboundPlayPacket(String protocolName, Packet<?> packet) {
        if (packet == null || !"PLAY".equalsIgnoreCase(protocolName)) {
            return null;
        }

        ChunkHotspotKind hotspotKind = resolveHotspotKind(packet);
        if (hotspotKind == null) {
            return null;
        }

        return new ChunkPacketDescriptor(
                protocolName,
                packet.getClass().getName(),
                hotspotKind,
                hotspotKind.laneKind(),
                resolveCoordinate(packet, hotspotKind)
        );
    }

    // What kind of chunk is?
    private static ChunkHotspotKind resolveHotspotKind(Packet<?> packet) {
        if (packet instanceof ClientboundLevelChunkWithLightPacket) {
            return ChunkHotspotKind.FULL_CHUNK;
        }
        if (packet instanceof ClientboundLightUpdatePacket) {
            return ChunkHotspotKind.LIGHT_UPDATE;
        }
        if (packet instanceof ClientboundSectionBlocksUpdatePacket) {
            return ChunkHotspotKind.SECTION_BLOCKS_UPDATE;
        }
        if (packet instanceof ClientboundBlockUpdatePacket) {
            return ChunkHotspotKind.BLOCK_UPDATE;
        }
        if (packet instanceof ClientboundBlockEntityDataPacket) {
            return ChunkHotspotKind.BLOCK_ENTITY_UPDATE;
        }
        return null;
    }

    private static ChunkPacketCoordinate resolveCoordinate(Packet<?> packet, ChunkHotspotKind hotspotKind) {
        return switch (hotspotKind) {
            case FULL_CHUNK -> readFullChunkCoordinate((ClientboundLevelChunkWithLightPacket) packet);
            case LIGHT_UPDATE -> readLightUpdateCoordinate((ClientboundLightUpdatePacket) packet);
            case SECTION_BLOCKS_UPDATE -> readSectionCoordinate((ClientboundSectionBlocksUpdatePacket) packet);
            case BLOCK_UPDATE -> readBlockCoordinate(((ClientboundBlockUpdatePacket) packet).getPos());
            case BLOCK_ENTITY_UPDATE -> readBlockCoordinate(((ClientboundBlockEntityDataPacket) packet).getPos());
        };
    }

    private static ChunkPacketCoordinate readFullChunkCoordinate(ClientboundLevelChunkWithLightPacket packet) {
        if (packet == null) {
            return ChunkPacketCoordinate.unknown();
        }
        return ChunkPacketCoordinate.ofChunk(packet.getX(), packet.getZ());
    }


    private static ChunkPacketCoordinate readLightUpdateCoordinate(ClientboundLightUpdatePacket packet) {
        if (packet == null) {
            return ChunkPacketCoordinate.unknown();
        }
        return ChunkPacketCoordinate.ofChunk(packet.getX(), packet.getZ());
    }

    private static ChunkPacketCoordinate readSectionCoordinate(ClientboundSectionBlocksUpdatePacket packet) {
        if (packet == null) {
            return ChunkPacketCoordinate.unknown();
        }
        SectionPos sectionPos = ((ClientboundSectionBlocksUpdatePacketAccessor) packet).bandwidthoptimizer$getSectionPos();
        if (sectionPos == null) {
            return ChunkPacketCoordinate.unknown();
        }
        return ChunkPacketCoordinate.ofChunk(sectionPos.x(), sectionPos.z());
    }

    private static ChunkPacketCoordinate readBlockCoordinate(BlockPos blockPos) {
        if (blockPos == null) {
            return ChunkPacketCoordinate.unknown();
        }
        return ChunkPacketCoordinate.ofChunk(blockPos.getX() >> 4, blockPos.getZ() >> 4);
    }
}
