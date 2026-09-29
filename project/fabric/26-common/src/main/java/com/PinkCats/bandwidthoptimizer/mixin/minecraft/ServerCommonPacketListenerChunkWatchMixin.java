package com.PinkCats.bandwidthoptimizer.mixin.minecraft;

import com.PinkCats.bandwidthoptimizer.FabricBandwidthOptimizerLifecycle;
import io.netty.channel.ChannelFutureListener;
import net.minecraft.network.protocol.Packet;
import net.minecraft.network.protocol.game.ClientboundForgetLevelChunkPacket;
import net.minecraft.network.protocol.game.ClientboundLevelChunkWithLightPacket;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.server.network.ServerCommonPacketListenerImpl;
import net.minecraft.server.network.ServerGamePacketListenerImpl;
import net.minecraft.world.level.ChunkPos;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(ServerCommonPacketListenerImpl.class)
public abstract class ServerCommonPacketListenerChunkWatchMixin {

    @Inject(method = "send(Lnet/minecraft/network/protocol/Packet;Lio/netty/channel/ChannelFutureListener;)V", at = @At("HEAD"))
    private void bandwidthoptimizer$observeChunkWatchPackets(Packet<?> packet, ChannelFutureListener listener, CallbackInfo ci) {
        ServerPlayer serverPlayer = bandwidthoptimizer$serverPlayer();
        if (serverPlayer == null || packet == null)
            return;

        if (packet instanceof ClientboundLevelChunkWithLightPacket chunkPacket) {
            FabricBandwidthOptimizerLifecycle.onPlayerWatchChunk(
                    serverPlayer,
                    new ChunkPos(chunkPacket.getX(), chunkPacket.getZ())
            );
        } else if (packet instanceof ClientboundForgetLevelChunkPacket forgetPacket) {
            FabricBandwidthOptimizerLifecycle.onPlayerUnwatchChunk(serverPlayer, forgetPacket.pos());
        }
    }

    private ServerPlayer bandwidthoptimizer$serverPlayer() {
        Object listener = this;
        if (listener instanceof ServerGamePacketListenerImpl gamePacketListener) {
            return gamePacketListener.player;
        }
        return null;
    }
}
