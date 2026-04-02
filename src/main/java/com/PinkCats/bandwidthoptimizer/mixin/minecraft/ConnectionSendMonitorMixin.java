package com.PinkCats.bandwidthoptimizer.mixin.minecraft;

import com.PinkCats.bandwidthoptimizer.network.algorithm.play.BypassedPlayPacketStats;
import com.PinkCats.bandwidthoptimizer.network.algorithm.play.PlayPacketReplaySupport;
import com.PinkCats.bandwidthoptimizer.network.algorithm.play.ServerOptimizationTelemetryManager;
import com.PinkCats.bandwidthoptimizer.network.algorithm.play.UnhandledPlayPacketDump;
import com.PinkCats.bandwidthoptimizer.network.server.ServerPlayPacketBatchingManager;
import com.PinkCats.bandwidthoptimizer.optimise.chunkcache.ServerChunkCacheManager;
import com.PinkCats.bandwidthoptimizer.optimise.monitor.PacketTrafficMonitor;
import io.netty.channel.Channel;
import io.netty.channel.local.LocalChannel;
import net.minecraft.network.Connection;
import net.minecraft.network.ConnectionProtocol;
import net.minecraft.network.PacketSendListener;
import net.minecraft.network.protocol.Packet;
import net.minecraft.network.protocol.game.ClientboundLevelChunkWithLightPacket;
import net.minecraft.server.network.ServerGamePacketListenerImpl;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(Connection.class)
public abstract class ConnectionSendMonitorMixin {

    @Shadow
    private Channel channel;

    @Shadow
    private net.minecraft.network.PacketListener packetListener;

    @Inject(method = "send(Lnet/minecraft/network/protocol/Packet;Lnet/minecraft/network/PacketSendListener;)V", at = @At("HEAD"))
    private void bandwidthoptimizer$capturePacketSourceWithListener(Packet<?> packet, PacketSendListener listener, CallbackInfo ci) {
        PacketTrafficMonitor.captureSendSource(this.channel, packet);
    }

    @Inject(method = "send(Lnet/minecraft/network/protocol/Packet;)V", at = @At("HEAD"))
    private void bandwidthoptimizer$capturePacketSource(Packet<?> packet, CallbackInfo ci) {
        PacketTrafficMonitor.captureSendSource(this.channel, packet);
    }

    @Inject(method = "sendPacket(Lnet/minecraft/network/protocol/Packet;Lnet/minecraft/network/PacketSendListener;)V", at = @At("HEAD"))
    private void bandwidthoptimizer$captureQueuedPacketSource(Packet<?> packet, PacketSendListener listener, CallbackInfo ci) {
        PacketTrafficMonitor.captureSendSource(this.channel, packet);
    }

    @Inject(method = "sendPacket(Lnet/minecraft/network/protocol/Packet;Lnet/minecraft/network/PacketSendListener;)V", at = @At("HEAD"), cancellable = true)
    private void bandwidthoptimizer$batchClientboundPlayPackets(Packet<?> packet, PacketSendListener listener, CallbackInfo ci) {
        if (this.channel == null || this.channel.attr(Connection.ATTRIBUTE_PROTOCOL).get() != ConnectionProtocol.PLAY) {
            return;
        }
        if (!(this.packetListener instanceof ServerGamePacketListenerImpl serverGamePacketListener)) {
            return;
        }
        if (this.channel instanceof LocalChannel) {
            ServerPlayPacketBatchingManager.flushPendingNow(serverGamePacketListener.player);
            return;
        }

        if (PlayPacketReplaySupport.isInternalTransport(packet)) {
            return;
        }

        if (ServerPlayPacketBatchingManager.shouldBypassForConnectionWarmup(serverGamePacketListener.player)) {
            ServerPlayPacketBatchingManager.flushPendingNow(serverGamePacketListener.player);
            return;
        }

        if (listener == null
                && packet instanceof ClientboundLevelChunkWithLightPacket levelChunkPacket
                && ServerChunkCacheManager.tryHandle(serverGamePacketListener.player, levelChunkPacket)) {
            ci.cancel();
            return;
        }

        if (listener != null || !PlayPacketReplaySupport.shouldReplay(packet)) {
            if (PlayPacketReplaySupport.isClientboundPlayPacket(packet)) {
                String replayBypassReason = PlayPacketReplaySupport.bypassReason(packet);
                String reason = listener != null ? "listener_present" : replayBypassReason;
                int estimatedBytes = PlayPacketReplaySupport.estimatedEncodedBytes(packet);
                BypassedPlayPacketStats.record(packet, reason, estimatedBytes);
                ServerOptimizationTelemetryManager.recordBypass(serverGamePacketListener.player, estimatedBytes);
                UnhandledPlayPacketDump.record(serverGamePacketListener.player, packet, listener, reason, estimatedBytes);
            }
            ServerPlayPacketBatchingManager.flushPendingNow(serverGamePacketListener.player);
            return;
        }

        ServerPlayPacketBatchingManager.enqueue(serverGamePacketListener.player, packet);
        ci.cancel();
    }
}
