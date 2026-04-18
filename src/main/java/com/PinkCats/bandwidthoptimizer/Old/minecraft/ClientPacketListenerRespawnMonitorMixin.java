package com.PinkCats.bandwidthoptimizer.Old.minecraft;

import com.PinkCats.bandwidthoptimizer.Old.network.client.ClientRespawnTransportBarrier;
import net.minecraft.client.multiplayer.ClientPacketListener;
import net.minecraft.network.protocol.game.ClientboundRespawnPacket;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(ClientPacketListener.class)
public abstract class ClientPacketListenerRespawnMonitorMixin {

    @Inject(method = "handleRespawn", at = @At("HEAD"))
    private void bandwidthoptimizer$armTransportBarrierOnRespawn(ClientboundRespawnPacket packet, CallbackInfo ci) {
        ClientRespawnTransportBarrier.onRespawnBoundary();
    }
}
