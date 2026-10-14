package com.PinkCats.bandwidthoptimizer.mixin.createsifter;

import com.PinkCats.bandwidthoptimizer.integration.createsifter.CreateSifterSyncCompat;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Pseudo;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Coerce;
import org.spongepowered.asm.mixin.injection.Redirect;

@Pseudo
@Mixin(targets = "com.oierbravo.mechanicals.foundation.blockEntity.behaviour.DynamicCycleBehavior", remap = false)
public abstract class SifterCycleSyncMixin {
    @Redirect(method = "tick", at = @At(value = "INVOKE", target = "Lcom/oierbravo/mechanicals/foundation/blockEntity/behaviour/DynamicCycleBehavior;apply()V"), require = 0, remap = false)
    private void bandwidthoptimizer$batchProcessingSync(@Coerce Object behavior) {
        CreateSifterSyncCompat.apply(behavior, () -> CreateSifterSyncCompat.invokeApply(behavior));
    }
}
