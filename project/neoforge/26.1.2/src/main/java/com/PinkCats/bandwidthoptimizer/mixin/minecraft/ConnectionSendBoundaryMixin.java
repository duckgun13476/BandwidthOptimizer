package com.PinkCats.bandwidthoptimizer.mixin.minecraft;

import com.PinkCats.bandwidthoptimizer.chunk.integration.transport.ChunkTransportBoundaryController;
import com.PinkCats.bandwidthoptimizer.channel.ChannelTransportBypassRankLogger;
import com.PinkCats.bandwidthoptimizer.channel.ChannelTransportControlPlane;
import com.PinkCats.bandwidthoptimizer.channel.ChannelTransportTraceJournal;
import com.PinkCats.bandwidthoptimizer.connection.ConnectionDisconnectClassifier;
import com.PinkCats.bandwidthoptimizer.connection.ConnectionPayloadTaskGuard;
import com.PinkCats.bandwidthoptimizer.channel.capture.ChannelDecoderExceptionDumper;
import com.PinkCats.bandwidthoptimizer.gate.integration.create.CreateBlockEntityUpdateGate;
import com.PinkCats.bandwidthoptimizer.debug.MovementDiagnosticProbe;
import com.PinkCats.bandwidthoptimizer.report.ChannelTransportSourceRankCore;
import io.netty.channel.Channel;
import io.netty.channel.ChannelFutureListener;
import io.netty.channel.ChannelHandlerContext;
import net.minecraft.network.Connection;
import net.minecraft.network.DisconnectionDetails;
import net.minecraft.network.PacketListener;
import net.minecraft.network.ProtocolInfo;
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
    @Inject(method = "sendPacket", at = @At("HEAD"), cancellable = true)
    private void bandwidthoptimizer$notePacketSendBoundary(Packet<?> packet, ChannelFutureListener listener, boolean flush, CallbackInfo ci) {
        ConnectionDisconnectClassifier.observeOutboundPacket(this.channel, packet);
        MovementDiagnosticProbe.BO_Diag_movementCorrection(this.channel, packet);
        CreateBlockEntityUpdateGate.observeConnectionSend(this.channel, packet);
        if (CreateBlockEntityUpdateGate.tryDelayConnectionSend(this.channel, packet, listener)) {
            ci.cancel();
            return;
        }
        ChannelTransportControlPlane.observeConnectionSend(this.channel, packet, listener);
        ChunkTransportBoundaryController.notePacketSendListener(this.channel, packet, listener);
    }

    @Inject(method = "channelInactive", at = @At("HEAD"))
    private void bandwidthoptimizer$dumpTransportTraceOnInactive(ChannelHandlerContext context, CallbackInfo ci) {
        ConnectionPayloadTaskGuard.close(this.channel);
        ConnectionDisconnectClassifier.onChannelInactive(this.channel);
        ChannelTransportBypassRankLogger.dumpNow("channelInactive");
        ChannelTransportSourceRankCore.dumpNow("channelInactive");
        ChannelTransportTraceJournal.dumpAndClear(this.channel, "channelInactive", null);
    }

    @Inject(method = "setupInboundProtocol", at = @At("HEAD"))
    private <T extends PacketListener> void bandwidthoptimizer$advancePayloadTaskGeneration(
            ProtocolInfo<T> protocolInfo,
            T packetListener,
            CallbackInfo ci
    ) {
        ConnectionPayloadTaskGuard.advanceProtocolGeneration(this.channel);
    }

    @Inject(method = "disconnect(Lnet/minecraft/network/DisconnectionDetails;)V", at = @At("HEAD"))
    private void bandwidthoptimizer$closePayloadTaskGeneration(DisconnectionDetails details, CallbackInfo ci) {
        ConnectionPayloadTaskGuard.close(this.channel);
    }

    @Inject(method = "exceptionCaught", at = @At("HEAD"))
    private void bandwidthoptimizer$dumpTransportTraceOnException(
            ChannelHandlerContext context,
            Throwable throwable,
            CallbackInfo ci
    ) {
        ConnectionDisconnectClassifier.observeException(this.channel, throwable);
        ChannelDecoderExceptionDumper.dumpIfDecoderException(context, this.channel, throwable);
        ChannelTransportBypassRankLogger.dumpNow("exceptionCaught");
        ChannelTransportSourceRankCore.dumpNow("exceptionCaught");
        ChannelTransportTraceJournal.dumpAndClear(this.channel, "exceptionCaught", throwable);
    }
}
