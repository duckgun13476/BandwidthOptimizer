package com.PinkCats.bandwidthoptimizer.mixin.minecraft;

import com.PinkCats.bandwidthoptimizer.connection.ClientDisconnectHooks;
import net.minecraft.client.Minecraft;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(Minecraft.class)
public abstract class MinecraftDisconnect26Mixin {

    @Inject(method = "disconnectFromWorld(Lnet/minecraft/network/chat/Component;)V", at = @At("HEAD"))
    private void bandwidthoptimizer$markLocalDisconnect(CallbackInfo ci) {
        ClientDisconnectHooks.markCurrentConnectionLocal();
    }
}
