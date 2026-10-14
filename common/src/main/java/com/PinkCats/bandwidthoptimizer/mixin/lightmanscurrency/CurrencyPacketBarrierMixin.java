package com.PinkCats.bandwidthoptimizer.mixin.lightmanscurrency;

import com.PinkCats.bandwidthoptimizer.integration.lightmanscurrency.CurrencyBankSyncTransaction;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Pseudo;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Pseudo
@Mixin(targets="io.github.lightman314.lightmanscurrency.network.packet.ServerToClientPacket",remap=false)
public abstract class CurrencyPacketBarrierMixin {
    @Inject(method={"sendTo","sendToAll","sendToPlayersNear","sendToPlayersInDimension","sendToPlayersTrackingChunk","sendToPlayersTrackingEntity","sendToPlayersTrackingEntityAndSelf"},at=@At("HEAD"),require=0,remap=false)
    private void bandwidthoptimizer$flushBeforePacket(CallbackInfo callback) { CurrencyBankSyncTransaction.barrier(); }
}
