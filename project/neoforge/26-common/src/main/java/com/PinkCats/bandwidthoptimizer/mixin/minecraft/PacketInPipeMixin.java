package com.PinkCats.bandwidthoptimizer.mixin.minecraft;

import com.PinkCats.bandwidthoptimizer.channel.access.PacketDecoderFlowAccess;
import com.PinkCats.bandwidthoptimizer.channel.access.PacketDecoderProtocolInfoAccess;
import com.PinkCats.bandwidthoptimizer.channel.capture.ChannelCaptureHooks;
import com.PinkCats.bandwidthoptimizer.channel.ChannelTransportHooks;
import com.PinkCats.bandwidthoptimizer.channel.capture.ChannelCapturedFrame;
import com.PinkCats.bandwidthoptimizer.channel.capture.ChannelTransportTelemetry;
import com.PinkCats.bandwidthoptimizer.chunk.classify.ChunkInboundObservationService;
import com.PinkCats.bandwidthoptimizer.connection.ConnectionDisconnectClassifier;
import com.PinkCats.bandwidthoptimizer.connection.ConnectionInternetProbeGuard;
import com.PinkCats.bandwidthoptimizer.debug.MovementDiagnosticProbe;
import com.PinkCats.bandwidthoptimizer.debug.PacketClassTraceDiagnostic;
import com.PinkCats.bandwidthoptimizer.integration.trueuuid.TrueUuidLateLoginQueryGuard;
import com.PinkCats.bandwidthoptimizer.server.stat.ChannelBandwidthStats;
import com.PinkCats.bandwidthoptimizer.server.stat.ServerBandwidthStatsRegistry;
import io.netty.buffer.ByteBuf;
import io.netty.channel.ChannelHandlerContext;
import net.minecraft.network.PacketDecoder;
import net.minecraft.network.PacketListener;
import net.minecraft.network.ProtocolInfo;
import net.minecraft.network.protocol.PacketFlow;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import java.util.List;

@Mixin(PacketDecoder.class)
public abstract class PacketInPipeMixin<T extends PacketListener> implements PacketDecoderFlowAccess, PacketDecoderProtocolInfoAccess {

    @Shadow
    @Final
    private ProtocolInfo<T> protocolInfo;

    @Unique
    private int bandwidthoptimizer$outputSizeBeforeDecode;

    @Unique
    private ChannelCapturedFrame bandwidthoptimizer$pendingInboundFrame;


    // Better method
    @Override
    public PacketFlow bandwidthoptimizer$getPacketFlow() {
        return this.protocolInfo.flow();
    }

    @Override
    public ProtocolInfo<? extends PacketListener> bandwidthoptimizer$getProtocolInfo() {
        return this.protocolInfo;
    }

    @Inject(method = "decode", at = @At("HEAD"), cancellable = true)
    private void bandwidthoptimizer$unwrapAndCapture(ChannelHandlerContext context, ByteBuf in, List<Object> out, CallbackInfo ci) throws Exception {
        this.bandwidthoptimizer$outputSizeBeforeDecode = out.size();
        this.bandwidthoptimizer$pendingInboundFrame = ChannelCaptureHooks.beginInboundPreDecode(context, in);
        if (TrueUuidLateLoginQueryGuard.tryDropInboundPlayCustomQueryAck(context, this.protocolInfo.flow(), in)) {
            ChannelCaptureHooks.clearInboundDecodeCandidate(context.channel());
            this.bandwidthoptimizer$pendingInboundFrame = null;
            ci.cancel();
            return;
        }
        if (ChannelTransportHooks.tryDecodeInboundTransportFrame(context, in, out, this)) {
            ChannelCaptureHooks.clearInboundDecodeCandidate(context.channel());
            this.bandwidthoptimizer$pendingInboundFrame = null;
            ci.cancel();
            return;
        }

        // Save use
        bandwidthoptimizer$recordInboundRawEncoded(context, in);
    }

    @Inject(method = "decode", at = @At("RETURN"))
    private void bandwidthoptimizer$finishDecodeFrame(ChannelHandlerContext context, ByteBuf in, List<Object> out, CallbackInfo ci) throws Exception {
        ConnectionInternetProbeGuard.markMinecraftPacketDecoded(context);
        if (ChannelTransportHooks.expandDecodedTransportCarrierPackets(
                context,
                out,
                this.bandwidthoptimizer$outputSizeBeforeDecode,
                this
        )) {
            ConnectionDisconnectClassifier.observeInboundDecodedPackets(
                    context,
                    out,
                    this.bandwidthoptimizer$outputSizeBeforeDecode
            );
            MovementDiagnosticProbe.BO_Diag_movementBurst(
                    context,
                    this.protocolInfo.flow(),
                    out,
                    this.bandwidthoptimizer$outputSizeBeforeDecode
            );
            ChannelCaptureHooks.clearInboundDecodeCandidate(context.channel());
            this.bandwidthoptimizer$pendingInboundFrame = null;
            return;
        }

        ConnectionDisconnectClassifier.observeInboundDecodedPackets(
                context,
                out,
                this.bandwidthoptimizer$outputSizeBeforeDecode
        );
        TrueUuidLateLoginQueryGuard.observeInboundDecodedPackets(context, out, this.bandwidthoptimizer$outputSizeBeforeDecode);
        ChunkInboundObservationService.observeInboundDecodedPackets(
                context,
                this.bandwidthoptimizer$pendingInboundFrame,
                out,
                this.bandwidthoptimizer$outputSizeBeforeDecode
        );
        MovementDiagnosticProbe.BO_Diag_movementBurst(
                context,
                this.protocolInfo.flow(),
                out,
                this.bandwidthoptimizer$outputSizeBeforeDecode
        );
        PacketClassTraceDiagnostic.recordInboundDirect(
                context,
                this.protocolInfo.flow(),
                this.bandwidthoptimizer$pendingInboundFrame,
                out,
                this.bandwidthoptimizer$outputSizeBeforeDecode
        );

        //Log
        ChannelCaptureHooks.finishInboundDecode(context, this.bandwidthoptimizer$pendingInboundFrame, out, this.bandwidthoptimizer$outputSizeBeforeDecode);
        bandwidthoptimizer$recordInboundBypass(context, out);
        this.bandwidthoptimizer$pendingInboundFrame = null;
    }
    @Unique
    private void bandwidthoptimizer$recordInboundBypass(ChannelHandlerContext context, List<Object> out) {
        if (this.bandwidthoptimizer$pendingInboundFrame == null || out == null) {
            return;
        }

        int decodedPacketCount = Math.max(out.size() - this.bandwidthoptimizer$outputSizeBeforeDecode, 0);
        if (decodedPacketCount <= 0) {
            return;
        }

        ChannelTransportTelemetry.recordInboundBypass(
                this.bandwidthoptimizer$pendingInboundFrame.protocolName(),
                this.bandwidthoptimizer$pendingInboundFrame.byteLength(),
                decodedPacketCount
        );
        ChannelBandwidthStats stats = ServerBandwidthStatsRegistry.getOrCreate(context);
        if (stats != null) {
            stats.recordInboundBypass(this.bandwidthoptimizer$pendingInboundFrame.byteLength(), decodedPacketCount);
        }
    }

    @Unique
    private void bandwidthoptimizer$recordInboundRawEncoded(ChannelHandlerContext context, ByteBuf in) {
        ChannelBandwidthStats stats = ServerBandwidthStatsRegistry.getOrCreate(context);
        if (stats != null && in != null) {
            stats.recordInboundRawEncoded(in.readableBytes(), 1);
        }
    }
}
