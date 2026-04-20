package com.PinkCats.bandwidthoptimizer.mixin.minecraft;

import com.PinkCats.bandwidthoptimizer.channel.capture.ChannelCaptureHooks;
import com.PinkCats.bandwidthoptimizer.channel.ChannelTransportHooks;
import io.netty.buffer.ByteBuf;
import io.netty.channel.ChannelHandlerContext;
import net.minecraft.network.PacketEncoder;
import net.minecraft.network.PacketListener;
import net.minecraft.network.protocol.Packet;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;


@Mixin(PacketEncoder.class)
public abstract class PacketOutPipeMixin<T extends PacketListener> {

    @Unique
    private int bandwidthoptimizer$writerIndexBefore;

    @Inject(method = "encode*", at = @At("HEAD"))
    private void bandwidthoptimizer$rememberWriterIndex(ChannelHandlerContext context, Packet<T> packet, ByteBuf out, CallbackInfo ci) {

        //Index
        this.bandwidthoptimizer$writerIndexBefore = out.writerIndex();
    }


    @Inject(method = "encode*", at = @At("RETURN"))
    private void bandwidthoptimizer$captureAndMaybeWrap(ChannelHandlerContext context, Packet<T> packet, ByteBuf out, CallbackInfo ci) {
        //Patch
        ChannelCaptureHooks.captureOutboundEncodedPacket(context, packet, out, this.bandwidthoptimizer$writerIndexBefore);

        //handle
        ChannelTransportHooks.maybeWrapOutboundPacket(context, out, this.bandwidthoptimizer$writerIndexBefore);
    }
}
