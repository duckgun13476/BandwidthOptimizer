package com.PinkCats.bandwidthoptimizer.mixin.minecraft;

import com.PinkCats.bandwidthoptimizer.chunk.integration.transport.ChunkTransportBoundaryController;
import com.PinkCats.bandwidthoptimizer.channel.ChannelTransportBypassRankLogger;
import com.PinkCats.bandwidthoptimizer.channel.ChannelTransportControlPlane;
import com.PinkCats.bandwidthoptimizer.channel.ChannelTransportTraceJournal;
import com.PinkCats.bandwidthoptimizer.channel.capture.ChannelDecoderExceptionDumper;
import io.netty.channel.Channel;
import io.netty.channel.ChannelHandlerContext;
import net.minecraft.network.Connection;
import net.minecraft.network.PacketSendListener;
import net.minecraft.network.protocol.Packet;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(Connection.class)
public abstract class ConnectionSendBoundaryMixin {

    @Shadow
    private Channel channel;

    // Chunk send
    @Inject(method = "sendPacket", at = @At("HEAD"))
    private void bandwidthoptimizer$notePacketSendBoundary(Packet<?> packet, PacketSendListener listener, CallbackInfo ci) {
        ChannelTransportControlPlane.observeConnectionSend(this.channel, packet, listener);
        ChunkTransportBoundaryController.notePacketSendListener(this.channel, packet, listener);
    }

    @Inject(method = "channelInactive", at = @At("HEAD"))
    private void bandwidthoptimizer$dumpTransportTraceOnInactive(ChannelHandlerContext context, CallbackInfo ci) {
        ChannelTransportBypassRankLogger.dumpNow("channelInactive");
        ChannelTransportTraceJournal.dumpAndClear(this.channel, "channelInactive", null);
    }

    @Inject(method = "exceptionCaught", at = @At("HEAD"))
    private void bandwidthoptimizer$dumpTransportTraceOnException(
            ChannelHandlerContext context,
            Throwable throwable,
            CallbackInfo ci
    ) {
        ChannelDecoderExceptionDumper.dumpIfDecoderException(context, this.channel, throwable);
        ChannelTransportBypassRankLogger.dumpNow("exceptionCaught");
        ChannelTransportTraceJournal.dumpAndClear(this.channel, "exceptionCaught", throwable);
    }
}
