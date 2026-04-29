package com.PinkCats.bandwidthoptimizer.chunk.snapshot;

import com.PinkCats.bandwidthoptimizer.chunk.classify.packet.ChunkPacketDescriptor;
import com.PinkCats.bandwidthoptimizer.mixin.minecraft.ClientboundSectionBlocksUpdatePacketAccessor;
import net.minecraft.core.BlockPos;
import net.minecraft.core.SectionPos;
import net.minecraft.network.protocol.Packet;
import net.minecraft.network.protocol.game.ClientboundBlockEntityDataPacket;
import net.minecraft.network.protocol.game.ClientboundBlockUpdatePacket;
import net.minecraft.network.protocol.game.ClientboundSectionBlocksUpdatePacket;

public final class ChunkSnapshotSemanticKeyResolver {

    private ChunkSnapshotSemanticKeyResolver() {}

    public static String resolveSemanticKey(ChunkPacketDescriptor descriptor, Packet<?> packet) {
        if (descriptor == null || descriptor.hotspotKind() == null)
            return "default";

        return switch (descriptor.hotspotKind()) {
            case FULL_CHUNK -> "full";
            case LIGHT_UPDATE -> "light";
            case SECTION_BLOCKS_UPDATE -> resolveSectionSemanticKey(packet);
            case BLOCK_UPDATE, BLOCK_ENTITY_UPDATE -> resolveBlockSemanticKey(packet);
        };
    }

    private static String resolveSectionSemanticKey(Packet<?> packet) {
        if (!(packet instanceof ClientboundSectionBlocksUpdatePacket sectionPacket)) {
            return "section:unknown";
        }
        SectionPos sectionPos = ((ClientboundSectionBlocksUpdatePacketAccessor) sectionPacket).bandwidthoptimizer$getSectionPos();
        if (sectionPos == null)
            return "section:unknown";
        return "section:" + sectionPos.x() + "," + sectionPos.y() + "," + sectionPos.z();
    }

    private static String resolveBlockSemanticKey(Packet<?> packet) {
        if (packet instanceof ClientboundBlockUpdatePacket blockUpdatePacket) {
            return resolveBlockPosSemanticKey(blockUpdatePacket.getPos());
        }
        if (packet instanceof ClientboundBlockEntityDataPacket blockEntityDataPacket) {
            return resolveBlockPosSemanticKey(blockEntityDataPacket.getPos());
        }
        return "block:unknown";
    }

    private static String resolveBlockPosSemanticKey(BlockPos blockPos) {
        if (blockPos == null)
            return "block:unknown";
        return "block:" + blockPos.getX() + "," + blockPos.getY() + "," + blockPos.getZ();
    }
}
