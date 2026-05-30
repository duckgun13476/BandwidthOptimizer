package com.PinkCats.bandwidthoptimizer.mixin.watut;

import com.PinkCats.bandwidthoptimizer.compat.watut.WatutDynamicGuiCompat;
import org.spongepowered.asm.mixin.Dynamic;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Pseudo;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Coerce;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Pseudo
@Mixin(targets = "com.corosus.watut.PlayerStatusManagerClient", remap = false)
public abstract class WatutPlayerStatusManagerClientMixin {

    @Dynamic("Optional WATUT compat")
    @Inject(method = "tickGame", at = @At("TAIL"), require = 0, remap = false)
    private void bandwidthoptimizer$throttleWatutDynamicGui(CallbackInfo ci) {
        WatutDynamicGuiCompat.afterTickGame(this);
    }

    @Dynamic("Optional WATUT compat")
    @Inject(method = "sendScreenRenderData", at = @At("TAIL"), require = 0, remap = false)
    private void bandwidthoptimizer$restoreWatutTextureBuffer(@Coerce Object status, CallbackInfo ci) {
        WatutDynamicGuiCompat.afterSendScreenRenderData(status);
    }
}
