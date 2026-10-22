package com.PinkCats.bandwidthoptimizer.mixin.minecraft;

import net.minecraft.network.protocol.game.ClientboundLevelChunkPacketData;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Mutable;
import org.spongepowered.asm.mixin.gen.Accessor;

/** Only applied to a freshly decoded, private cache candidate. */
@Mixin(ClientboundLevelChunkPacketData.class)
public interface ChunkSnapshotDataAccessor {
    @Mutable @Accessor("buffer")
    void bandwidthoptimizer$replaceBuffer(byte[] bytes);
}
