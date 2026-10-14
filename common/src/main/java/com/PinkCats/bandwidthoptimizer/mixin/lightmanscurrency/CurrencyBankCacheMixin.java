package com.PinkCats.bandwidthoptimizer.mixin.lightmanscurrency;

import com.PinkCats.bandwidthoptimizer.integration.lightmanscurrency.CurrencyTradeSyncCompat;
import java.util.UUID;
import net.minecraft.server.level.ServerPlayer;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Pseudo;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Pseudo
@Mixin(targets="io.github.lightman314.lightmanscurrency.common.data.types.BankDataCache",remap=false)
public abstract class CurrencyBankCacheMixin {
    @Inject(method="syncBankAccount(Ljava/util/UUID;Lnet/minecraft/server/level/ServerPlayer;)V",at=@At("HEAD"),cancellable=true,require=0,remap=false)
    private void bandwidthoptimizer$combineAdjacentAccountState(UUID account,ServerPlayer target,CallbackInfo callback) {
        if(CurrencyTradeSyncCompat.deferBank(this,account,target))callback.cancel();
    }
}
