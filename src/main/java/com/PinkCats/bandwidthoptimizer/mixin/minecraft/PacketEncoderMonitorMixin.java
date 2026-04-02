package com.PinkCats.bandwidthoptimizer.mixin.minecraft;

import com.PinkCats.bandwidthoptimizer.optimise.monitor.PacketTrafficMonitor;
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
public abstract class PacketEncoderMonitorMixin<T extends PacketListener> {

    @Unique
    private int bandwidthoptimizer$writerIndexBefore;

    @Inject(method = "encode", at = @At("HEAD"))
    private void bandwidthoptimizer$captureEncodeStart(ChannelHandlerContext context, Packet<T> packet, ByteBuf out, CallbackInfo ci) {
        this.bandwidthoptimizer$writerIndexBefore = out.writerIndex();
    }

    @Inject(method = "encode", at = @At("RETURN"))
    private void bandwidthoptimizer$recordEncodedPacket(ChannelHandlerContext context, Packet<T> packet, ByteBuf out, CallbackInfo ci) {
        int bytes = out.writerIndex() - this.bandwidthoptimizer$writerIndexBefore;
        byte[] payload = PacketTrafficMonitor.copyBytes(out, this.bandwidthoptimizer$writerIndexBefore, out.writerIndex());
        PacketTrafficMonitor.recordEncoded(context.channel(), packet, bytes, payload);
    }
}
