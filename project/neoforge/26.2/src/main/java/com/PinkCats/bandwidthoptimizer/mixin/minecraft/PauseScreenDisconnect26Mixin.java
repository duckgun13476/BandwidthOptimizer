package com.PinkCats.bandwidthoptimizer.mixin.minecraft;

import com.PinkCats.bandwidthoptimizer.connection.ClientDisconnectHooks;
import net.minecraft.client.gui.screens.PauseScreen;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(PauseScreen.class)
public abstract class PauseScreenDisconnect26Mixin {

    @Inject(method = "lambda$createPauseMenu$13()V", at = @At("HEAD"))
    private void bandwidthoptimizer$markLocalDisconnect(CallbackInfo ci) {
        ClientDisconnectHooks.markCurrentConnectionLocal();
    }
}
