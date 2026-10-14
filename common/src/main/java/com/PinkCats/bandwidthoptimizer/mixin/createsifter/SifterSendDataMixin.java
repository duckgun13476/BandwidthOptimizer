package com.PinkCats.bandwidthoptimizer.mixin.createsifter;

import com.PinkCats.bandwidthoptimizer.integration.createsifter.CreateSifterSyncCompat;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Pseudo;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Pseudo
@Mixin(targets = "com.simibubi.create.foundation.blockEntity.SyncedBlockEntity", remap = false)
public abstract class SifterSendDataMixin {
    @Inject(method = "sendData", at = @At("HEAD"), cancellable = true, require = 0, remap = false)
    private void bandwidthoptimizer$deferProcessingSync(CallbackInfo ci) {
        if (CreateSifterSyncCompat.defer(this)) ci.cancel();
    }
}
