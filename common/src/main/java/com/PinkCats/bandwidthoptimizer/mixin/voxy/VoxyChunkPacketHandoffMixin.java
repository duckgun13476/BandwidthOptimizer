package com.PinkCats.bandwidthoptimizer.mixin.voxy;

import com.PinkCats.bandwidthoptimizer.integration.minecraft.ChunkCoordinateCompat;
import com.PinkCats.bandwidthoptimizer.integration.voxy.VoxyChunkBoundCompat;
import net.minecraft.client.multiplayer.ClientPacketListener;
import net.minecraft.network.protocol.game.ClientboundForgetLevelChunkPacket;
import net.minecraft.network.protocol.game.ClientboundLevelChunkWithLightPacket;
import net.minecraft.world.level.ChunkPos;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(ClientPacketListener.class)
public abstract class VoxyChunkPacketHandoffMixin {

    @Inject(method = "handleLevelChunkWithLight", at = @At(
            value = "INVOKE",
            target = "Lnet/minecraft/network/protocol/PacketUtils;ensureRunningOnSameThread(Lnet/minecraft/network/protocol/Packet;Lnet/minecraft/network/PacketListener;Lnet/minecraft/util/thread/BlockableEventLoop;)V",
            shift = At.Shift.AFTER
    ), require = 0)
    private void bo$beginVoxyChunkApply(ClientboundLevelChunkWithLightPacket packet, CallbackInfo ci) {
        if (packet != null) {
            VoxyChunkBoundCompat.beginChunkApply(packet.getX(), packet.getZ());
        }
    }

    @Inject(method = "handleLevelChunkWithLight", at = @At("RETURN"), require = 0)
    private void bo$completeVoxyChunkApply(ClientboundLevelChunkWithLightPacket packet, CallbackInfo ci) {
        if (packet != null) {
            VoxyChunkBoundCompat.completeChunkApply(packet.getX(), packet.getZ());
        }
    }

    @Inject(method = "handleForgetLevelChunk", at = @At("RETURN"), require = 0)
    private void bo$cancelVoxyChunkHandoff(ClientboundForgetLevelChunkPacket packet, CallbackInfo ci) {
        ChunkPos position = packet == null ? null : ChunkCoordinateCompat.forgetPosition(packet);
        if (position != null) {
            VoxyChunkBoundCompat.cancelVisualHandoff(
                    ChunkCoordinateCompat.x(position),
                    ChunkCoordinateCompat.z(position)
            );
        }
    }
}
