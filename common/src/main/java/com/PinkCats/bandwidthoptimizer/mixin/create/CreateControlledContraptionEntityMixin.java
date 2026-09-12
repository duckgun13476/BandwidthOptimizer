package com.PinkCats.bandwidthoptimizer.mixin.create;

import com.PinkCats.bandwidthoptimizer.gate.integration.create.CreateContraptionSnapshotRegistry;
import net.minecraft.core.BlockPos;
import net.minecraft.world.entity.Entity;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Pseudo;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Pseudo
@Mixin(targets = "com.simibubi.create.content.contraptions.ControlledContraptionEntity", remap = false)
public abstract class CreateControlledContraptionEntityMixin {

    @Shadow
    protected BlockPos controllerPos;

    @Inject(method = "tickContraption", at = @At("TAIL"), require = 0, remap = false)
    private void bandwidthoptimizer$trackControllerSnapshot(CallbackInfo callbackInfo) {
        if (com.PinkCats.bandwidthoptimizer.gate.integration.create.CreateBlockEntityUpdateGate.shouldTrackContraptions()) {
            CreateContraptionSnapshotRegistry.trackControlled((Entity) (Object) this, this.controllerPos);
        }
    }
}
