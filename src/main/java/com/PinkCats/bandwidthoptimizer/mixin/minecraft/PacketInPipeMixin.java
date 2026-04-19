package com.PinkCats.bandwidthoptimizer.mixin.minecraft;

import com.PinkCats.bandwidthoptimizer.channel.ChannelCaptureHooks;
import com.PinkCats.bandwidthoptimizer.channel.mes.ChannelCapturedFrame;
import com.PinkCats.bandwidthoptimizer.channel.ChannelTransportHooks;
import io.netty.buffer.ByteBuf;
import io.netty.channel.ChannelHandlerContext;
import net.minecraft.network.PacketDecoder;
import net.minecraft.network.PacketListener;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import java.util.List;

@Mixin(PacketDecoder.class)
public abstract class PacketInPipeMixin<T extends PacketListener> {


    @Unique
    private int bandwidthoptimizer$outputSizeBeforeDecode;

    @Unique
    private ChannelCapturedFrame bandwidthoptimizer$pendingInboundFrame;


    @Inject(method = "decode", at = @At("HEAD"), cancellable = true)
    private void bandwidthoptimizer$unwrapAndCapture(ChannelHandlerContext context, ByteBuf in, List<Object> out, CallbackInfo ci) throws Exception {

        // Take from framework
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
    private void bandwidthoptimizer$finishDecodeFrame(ChannelHandlerContext context, ByteBuf in, List<Object> out, CallbackInfo ci) {

        //Log
        ChannelCaptureHooks.finishInboundDecode(this.bandwidthoptimizer$pendingInboundFrame, out, this.bandwidthoptimizer$outputSizeBeforeDecode);
        this.bandwidthoptimizer$pendingInboundFrame = null;
    }
}
