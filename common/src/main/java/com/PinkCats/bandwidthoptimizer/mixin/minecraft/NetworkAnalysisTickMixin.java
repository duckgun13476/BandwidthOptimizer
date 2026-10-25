package com.PinkCats.bandwidthoptimizer.mixin.minecraft;

import com.PinkCats.bandwidthoptimizer.networkprobe.NetworkAnalysisClient;
import net.minecraft.client.Minecraft;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(Minecraft.class)
public abstract class NetworkAnalysisTickMixin {
    @Inject(method="tick",at=@At("TAIL"))
    private void bandwidthoptimizer$networkAnalysisTick(CallbackInfo ci){NetworkAnalysisClient.tick();}
}
