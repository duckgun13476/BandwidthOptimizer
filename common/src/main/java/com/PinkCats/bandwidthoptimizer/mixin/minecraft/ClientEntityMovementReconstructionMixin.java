package com.PinkCats.bandwidthoptimizer.mixin.minecraft;

import com.PinkCats.bandwidthoptimizer.gate.EntityMovementReconstruction;
import com.PinkCats.bandwidthoptimizer.integration.minecraft.EntityMotionPacketCompat;
import net.minecraft.client.multiplayer.ClientPacketListener;
import net.minecraft.network.protocol.game.ClientboundMoveEntityPacket;
import net.minecraft.network.protocol.game.ClientboundRemoveEntitiesPacket;
import net.minecraft.network.protocol.game.ClientboundTeleportEntityPacket;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(ClientPacketListener.class)
public abstract class ClientEntityMovementReconstructionMixin {
    @Inject(method = "handleMoveEntity", at = @At("HEAD"), cancellable = true)
    private void bandwidthoptimizer$reconstructMovement(ClientboundMoveEntityPacket packet, CallbackInfo ci) {
        if (EntityMovementReconstruction.onMovement((ClientPacketListener) (Object) this, packet)) {
            ci.cancel();
        }
    }

    @Inject(method = "handleTeleportEntity", at = @At("HEAD"))
    private void bandwidthoptimizer$discardOnTeleport(ClientboundTeleportEntityPacket packet, CallbackInfo ci) {
        EntityMovementReconstruction.discard((ClientPacketListener) (Object) this, EntityMotionPacketCompat.entityId(packet));
    }

    @Inject(method = "handleRemoveEntities", at = @At("HEAD"))
    private void bandwidthoptimizer$discardOnRemove(ClientboundRemoveEntitiesPacket packet, CallbackInfo ci) {
        for (int entityId : packet.getEntityIds()) {
            EntityMovementReconstruction.discard((ClientPacketListener) (Object) this, entityId);
        }
    }
}
