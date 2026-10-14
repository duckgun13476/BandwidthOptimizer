package com.PinkCats.bandwidthoptimizer.mixin.lightmanscurrency;

import com.PinkCats.bandwidthoptimizer.integration.lightmanscurrency.CurrencyBankSyncTransaction;
import net.minecraft.network.Connection;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(Connection.class)
public abstract class CurrencyConnectionBarrierMixin {
    @Inject(method="send",at=@At("HEAD"))
    private void bandwidthoptimizer$flushBeforeOtherPacket(CallbackInfo callback) { CurrencyBankSyncTransaction.barrier(); }
}
