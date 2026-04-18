package com.PinkCats.bandwidthoptimizer.Old.minecraft;

import com.PinkCats.bandwidthoptimizer.Old.optimise.chunkcache.ServerChunkCacheManager;
import it.unimi.dsi.fastutil.shorts.ShortSet;
import net.minecraft.core.BlockPos;
import net.minecraft.core.SectionPos;
import net.minecraft.network.protocol.Packet;
import net.minecraft.network.protocol.game.ClientboundBlockUpdatePacket;
import net.minecraft.network.protocol.game.ClientboundLightUpdatePacket;
import net.minecraft.network.protocol.game.ClientboundSectionBlocksUpdatePacket;
import net.minecraft.server.level.ChunkHolder;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.LevelHeightAccessor;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.chunk.LevelChunk;
import net.minecraft.world.level.chunk.LevelChunkSection;
import net.minecraft.world.level.lighting.LevelLightEngine;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import java.util.BitSet;

@Mixin(ChunkHolder.class)
public abstract class ChunkHolderChunkCacheDeltaMixin {

    @Shadow
    @Final
    private ChunkPos pos;

    @Shadow
    private boolean hasChangedSections;

    @Shadow
    @Final
    private ShortSet[] changedBlocksPerSection;

    @Shadow
    @Final
    private BitSet blockChangedLightSectionFilter;

    @Shadow
    @Final
    private BitSet skyChangedLightSectionFilter;

    @Shadow
    @Final
    private LevelLightEngine lightEngine;

    @Shadow
    @Final
    private LevelHeightAccessor levelHeightAccessor;

    @Inject(method = "broadcastChanges", at = @At("HEAD"))
    private void bandwidthoptimizer$routeChunkCacheDeltas(LevelChunk levelChunk, CallbackInfo ci) {
        if (!this.hasChangedSections && this.skyChangedLightSectionFilter.isEmpty() && this.blockChangedLightSectionFilter.isEmpty()) {
            return;
        }

        Level level = levelChunk.getLevel();
        if (!(level instanceof ServerLevel serverLevel)) {
            return;
        }

        if (!this.skyChangedLightSectionFilter.isEmpty() || !this.blockChangedLightSectionFilter.isEmpty()) {
            ServerChunkCacheManager.routeDeltaToCachedPlayers(
                    serverLevel,
                    this.pos,
                    new ClientboundLightUpdatePacket(levelChunk.getPos(), this.lightEngine, this.skyChangedLightSectionFilter, this.blockChangedLightSectionFilter)
            );
        }

        if (!this.hasChangedSections) {
            return;
        }

        for (int sectionIndex = 0; sectionIndex < this.changedBlocksPerSection.length; ++sectionIndex) {
            ShortSet changedBlocks = this.changedBlocksPerSection[sectionIndex];
            if (changedBlocks == null) {
                continue;
            }

            int sectionY = this.levelHeightAccessor.getSectionYFromSectionIndex(sectionIndex);
            SectionPos sectionPos = SectionPos.of(levelChunk.getPos(), sectionY);
            if (changedBlocks.size() == 1) {
                BlockPos blockPos = sectionPos.relativeToBlockPos(changedBlocks.iterator().nextShort());
                BlockState blockState = level.getBlockState(blockPos);
                ServerChunkCacheManager.routeDeltaToCachedPlayers(
                        serverLevel,
                        new ChunkPos(blockPos.getX() >> 4, blockPos.getZ() >> 4),
                        new ClientboundBlockUpdatePacket(blockPos, blockState)
                );
                routeBlockEntityIfNeeded(serverLevel, blockPos, blockState);
                continue;
            }

            LevelChunkSection section = levelChunk.getSection(sectionIndex);
            ClientboundSectionBlocksUpdatePacket sectionPacket = new ClientboundSectionBlocksUpdatePacket(sectionPos, changedBlocks, section);
            ServerChunkCacheManager.routeDeltaToCachedPlayers(serverLevel, this.pos, sectionPacket);
            sectionPacket.runUpdates((blockPos, blockState) -> routeBlockEntityIfNeeded(serverLevel, blockPos, blockState));
        }
    }

    private static void routeBlockEntityIfNeeded(ServerLevel level, BlockPos blockPos, BlockState blockState) {
        if (!blockState.hasBlockEntity()) {
            return;
        }

        BlockEntity blockEntity = level.getBlockEntity(blockPos);
        if (blockEntity == null) {
            return;
        }

        Packet<?> packet = blockEntity.getUpdatePacket();
        if (packet != null) {
            ServerChunkCacheManager.routeDeltaToCachedPlayers(
                    level,
                    new ChunkPos(blockPos.getX() >> 4, blockPos.getZ() >> 4),
                    packet
            );
        }
    }
}
