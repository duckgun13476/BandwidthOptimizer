package com.PinkCats.bandwidthoptimizer.mixin.watut;

import com.PinkCats.bandwidthoptimizer.integration.watut.WatutServerFrameCompat;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.world.entity.player.Player;
import org.spongepowered.asm.mixin.Dynamic;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Pseudo;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Pseudo
@Mixin(targets = "com.corosus.watut.PlayerStatusManagerServer", remap = false)
public abstract class WatutPlayerStatusManagerServerMixin {
    @Dynamic("Optional WATUT complete-frame reuse")
    @Inject(method = "receiveAny", at = @At("HEAD"), cancellable = true, require = 0, remap = false)
    private void bandwidthoptimizer$reuseFrame(Player player, CompoundTag data, CallbackInfo ci) {
        if (WatutServerFrameCompat.beforeReceive(this, player, data)) ci.cancel();
    }

    @Dynamic("Optional WATUT state snapshot cleanup")
    @Inject(method = "receiveAny", at = @At("TAIL"), require = 0, remap = false)
    private void bandwidthoptimizer$separateFrameFromState(Player player, CompoundTag data, CallbackInfo ci) {
        WatutServerFrameCompat.afterReceive(this, player);
    }

    @Dynamic("Optional WATUT nearby complete-frame login replay")
    @Inject(method = "playerLoggedIn", at = @At("TAIL"), require = 0, remap = false)
    private void bandwidthoptimizer$replayFrame(Player player, CallbackInfo ci) {
        WatutServerFrameCompat.afterLogin(this, player);
    }
}
