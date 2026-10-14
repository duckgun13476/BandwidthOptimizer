package com.PinkCats.bandwidthoptimizer.mixin.lightmanscurrency;

import com.PinkCats.bandwidthoptimizer.integration.lightmanscurrency.CurrencyBankSyncTransaction;
import com.PinkCats.bandwidthoptimizer.integration.lightmanscurrency.CurrencyTradeSyncCompat;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Pseudo;
import org.spongepowered.asm.mixin.injection.*;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Pseudo
@Mixin(targets="io.github.lightman314.lightmanscurrency.api.traders.TraderData",remap=false)
public abstract class CurrencyTraderSyncMixin {
    @Coerce
    @Redirect(method="TryExecuteTradeWithResults",at=@At(value="INVOKE",target="Lio/github/lightman314/lightmanscurrency/api/traders/TraderData;TryExecuteTrade(Lio/github/lightman314/lightmanscurrency/api/traders/TradeContext;I)Lio/github/lightman314/lightmanscurrency/api/traders/TradeResult;"),require=0,remap=false)
    private Object bandwidthoptimizer$adjacentBankUpdates(@Coerce Object trader,@Coerce Object context,int index) { return CurrencyTradeSyncCompat.execute(trader,context,index); }

    @Inject(method="markStatsDirty",at=@At("HEAD"),require=0,remap=false)
    private void bandwidthoptimizer$statsBoundary(CallbackInfo callback) { CurrencyBankSyncTransaction.barrier(); }

    @Inject(method={"runPreTradeEvent","runPostTradeEvent"},at=@At("HEAD"),require=0,remap=false)
    private void bandwidthoptimizer$eventBoundary(CallbackInfo callback) { CurrencyBankSyncTransaction.barrier(); }
}
