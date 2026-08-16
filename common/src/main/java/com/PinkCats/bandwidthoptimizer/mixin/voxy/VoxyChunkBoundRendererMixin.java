package com.PinkCats.bandwidthoptimizer.mixin.voxy;

import com.PinkCats.bandwidthoptimizer.integration.voxy.VoxyChunkBoundCompat;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Pseudo;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Coerce;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Pseudo
@Mixin(targets = "me.cortex.voxy.client.core.rendering.ChunkBoundRenderer", remap = false)
public abstract class VoxyChunkBoundRendererMixin {
    @Shadow @Final
    private it.unimi.dsi.fastutil.longs.Long2IntOpenHashMap chunk2idx;

    @Shadow @Final
    private it.unimi.dsi.fastutil.longs.LongOpenHashSet addQueue;

    @Shadow @Final
    private it.unimi.dsi.fastutil.longs.LongOpenHashSet remQueue;

    @Shadow @Final
    private it.unimi.dsi.fastutil.longs.LongArrayList[] delayedRemovalQueue;

    @Shadow
    public abstract void removeSection(long pos);

    @Inject(method = "addSection", at = @At("HEAD"), cancellable = true, remap = false, require = 0)
    private void bo$filterChunkBoundAdd(long pos, CallbackInfo ci) {
        if (!VoxyChunkBoundCompat.allowMaskAdd(pos)) {
            ci.cancel();
        }
    }

    @Inject(method = "removeSection", at = @At("HEAD"), cancellable = true, remap = false, require = 0)
    private void bo$filterChunkBoundRemove(long pos, CallbackInfo ci) {
        if (!VoxyChunkBoundCompat.allowMaskRemove(pos)) {
            ci.cancel();
        }
    }

    @Inject(method = "render", at = @At("HEAD"), remap = false, require = 0)
    private void bo$removeInvalidatedChunkBounds(@Coerce Object viewport, CallbackInfo ci) {
        for (long pos : VoxyChunkBoundCompat.drainInvalidatedMasks()) {
            this.bo$removeSectionImmediately(pos);
        }
        for (long pos : VoxyChunkBoundCompat.drainReadyMasks()) {
            this.addSection(pos);
        }
    }

    @Shadow
    public abstract void addSection(long pos);

    @Unique
    private void bo$removeSectionImmediately(long pos) {
        this.addQueue.remove(pos);
        this.remQueue.remove(pos);
        for (it.unimi.dsi.fastutil.longs.LongArrayList queue : this.delayedRemovalQueue) {
            while (queue.rem(pos)) {
                // Remove every delayed copy before scheduling this frame's removal.
            }
        }
        if (this.chunk2idx.containsKey(pos)) {
            this.remQueue.add(pos);
        }
    }
}
