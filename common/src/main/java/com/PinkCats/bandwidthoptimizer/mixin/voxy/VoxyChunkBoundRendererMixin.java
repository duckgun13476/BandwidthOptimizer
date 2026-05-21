package com.PinkCats.bandwidthoptimizer.mixin.voxy;

import com.PinkCats.bandwidthoptimizer.compat.voxy.VoxyChunkBoundCompat;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Pseudo;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Coerce;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Pseudo
@Mixin(targets = "me.cortex.voxy.client.core.rendering.ChunkBoundRenderer", remap = false)
public abstract class VoxyChunkBoundRendererMixin {
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
            this.removeSection(pos);
        }
    }
}
