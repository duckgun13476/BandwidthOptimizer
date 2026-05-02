package com.PinkCats.bandwidthoptimizer.mixin.minecraft;

import com.PinkCats.bandwidthoptimizer.channel.access.PacketDecoderFlowAccess;
import com.PinkCats.bandwidthoptimizer.channel.capture.ChannelCaptureHooks;
import com.PinkCats.bandwidthoptimizer.channel.ChannelTransportHooks;
import com.PinkCats.bandwidthoptimizer.channel.capture.ChannelCapturedFrame;
import com.PinkCats.bandwidthoptimizer.channel.capture.ChannelTransportTelemetry;
import com.PinkCats.bandwidthoptimizer.chunk.classify.ChunkInboundObservationService;
import io.netty.buffer.ByteBuf;
import io.netty.channel.ChannelHandlerContext;
import net.minecraft.network.PacketDecoder;
import net.minecraft.network.PacketListener;
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
public abstract class PacketInPipeMixin<T extends PacketListener> implements PacketDecoderFlowAccess {

    @Shadow
    @Final
    private PacketFlow flow;

    @Unique
    private int bandwidthoptimizer$outputSizeBeforeDecode;

    @Unique
    private ChannelCapturedFrame bandwidthoptimizer$pendingInboundFrame;


    // Better method
    @Override
    public PacketFlow bandwidthoptimizer$getPacketFlow() {
        return this.flow;
    }

    @Inject(method = "decode", at = @At("HEAD"), cancellable = true)
    private void bandwidthoptimizer$unwrapAndCapture(ChannelHandlerContext context, ByteBuf in, List<Object> out, CallbackInfo ci) throws Exception {
        if (ChannelTransportHooks.tryDecodeInboundTransportFrame(context, in, out, this)) {
            this.bandwidthoptimizer$outputSizeBeforeDecode = out.size();
            this.bandwidthoptimizer$pendingInboundFrame = null;
            ci.cancel();
            return;
        }


        // Save use
        this.bandwidthoptimizer$outputSizeBeforeDecode = out.size();
        this.bandwidthoptimizer$pendingInboundFrame = ChannelCaptureHooks.beginInboundPreDecode(context, in);
    }

    @Inject(method = "decode", at = @At("RETURN"))
    private void bandwidthoptimizer$finishDecodeFrame(ChannelHandlerContext context, ByteBuf in, List<Object> out, CallbackInfo ci) throws Exception {
        if (ChannelTransportHooks.expandDecodedTransportCarrierPackets(
                context,
                out,
                this.bandwidthoptimizer$outputSizeBeforeDecode,
                this
        )) {
            this.bandwidthoptimizer$pendingInboundFrame = null;
            return;
        }

        ChunkInboundObservationService.observeInboundDecodedPackets(
                context,
                this.bandwidthoptimizer$pendingInboundFrame,
                out,
                this.bandwidthoptimizer$outputSizeBeforeDecode
        );

        //Log
        ChannelCaptureHooks.finishInboundDecode(this.bandwidthoptimizer$pendingInboundFrame, out, this.bandwidthoptimizer$outputSizeBeforeDecode);
        bandwidthoptimizer$recordInboundBypass(out);
        this.bandwidthoptimizer$pendingInboundFrame = null;
    }
    @Unique
    private void bandwidthoptimizer$recordInboundBypass(List<Object> out) {
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
    }
}
