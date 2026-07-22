package com.PinkCats.bandwidthoptimizer.mixin.voxy;

import com.PinkCats.bandwidthoptimizer.integration.voxy.VoxyChunkBoundCompat;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Pseudo;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Coerce;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

@Pseudo
@Mixin(targets = "net.caffeinemc.mods.sodium.client.render.chunk.RenderSection", remap = false)
public abstract class VoxyRenderSectionMixin {
    @Shadow
    public abstract int getChunkX();

    @Shadow
    public abstract int getChunkY();

    @Shadow
    public abstract int getChunkZ();

    @Inject(method = "setInfo", at = @At("HEAD"), remap = false, require = 0)
    private void bo$trackVoxyChunkBoundMask(@Coerce Object info, CallbackInfoReturnable<Boolean> cir) {
        VoxyChunkBoundCompat.updateSection(this.getChunkX(), this.getChunkY(), this.getChunkZ(), info);
    }
}
