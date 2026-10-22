package com.PinkCats.bandwidthoptimizer.mixin.minecraft;

import net.minecraft.network.protocol.game.ClientboundLevelChunkWithLightPacket;
import net.minecraft.network.protocol.game.ClientboundLightUpdatePacketData;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Mutable;
import org.spongepowered.asm.mixin.gen.Accessor;

@Mixin(ClientboundLevelChunkWithLightPacket.class)
public interface ChunkSnapshotLightAccessor {
    @Mutable @Accessor("lightData")
    void bandwidthoptimizer$replaceLight(ClientboundLightUpdatePacketData light);
}
