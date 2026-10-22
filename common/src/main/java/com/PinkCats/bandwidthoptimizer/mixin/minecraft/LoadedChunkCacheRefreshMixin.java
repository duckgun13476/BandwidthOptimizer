package com.PinkCats.bandwidthoptimizer.mixin.minecraft;

import com.PinkCats.bandwidthoptimizer.client.cache.LoadedChunkCacheRefresh;
import com.PinkCats.bandwidthoptimizer.chunk.refresh.UpdatedChunkCacheStats;
import net.minecraft.client.multiplayer.ClientPacketListener;
import net.minecraft.network.protocol.game.ClientboundForgetLevelChunkPacket;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Coerce;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(ClientPacketListener.class)
public abstract class LoadedChunkCacheRefreshMixin {
    @Inject(method = {"handleSetChunkCacheCenter", "handleSetChunkCacheRadius"}, at = @At("HEAD"), require = 0)
    private void bandwidthoptimizer$refreshBeforeViewChange(@Coerce Object packet, CallbackInfo ci) {
        if (UpdatedChunkCacheStats.ENABLED) LoadedChunkCacheRefresh.beforeViewCenterChange();
    }
    @Inject(method = "handleLevelChunkWithLight", at = @At("TAIL"), require = 0)
    private void bandwidthoptimizer$refreshBase(@Coerce Object packet, CallbackInfo ci) {
        if (UpdatedChunkCacheStats.ENABLED) LoadedChunkCacheRefresh.observe(packet, true);
    }
    @Inject(method = {"handleBlockUpdate", "handleChunkBlocksUpdate", "handleLightUpdatePacket", "handleBlockEntityData"}, at = @At("TAIL"), require = 0)
    private void bandwidthoptimizer$refreshDirty(@Coerce Object packet, CallbackInfo ci) {
        if (UpdatedChunkCacheStats.ENABLED) LoadedChunkCacheRefresh.observe(packet, false);
    }
    @Inject(method = "handleForgetLevelChunk", at = @At("HEAD"), require = 0)
    private void bandwidthoptimizer$refreshBeforeUnload(ClientboundForgetLevelChunkPacket packet, CallbackInfo ci) {
        if (UpdatedChunkCacheStats.ENABLED) {
            var coordinate = com.PinkCats.bandwidthoptimizer.integration.minecraft.ChunkCoordinateCompat.forgetPosition(packet);
            if (coordinate != null) LoadedChunkCacheRefresh.beforeForget(
                    com.PinkCats.bandwidthoptimizer.integration.minecraft.ChunkCoordinateCompat.x(coordinate),
                    com.PinkCats.bandwidthoptimizer.integration.minecraft.ChunkCoordinateCompat.z(coordinate));
        }
    }
}
