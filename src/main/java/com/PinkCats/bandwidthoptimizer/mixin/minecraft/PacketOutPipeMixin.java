package com.PinkCats.bandwidthoptimizer.mixin.minecraft;

import com.PinkCats.bandwidthoptimizer.channel.ChannelCaptureHooks;
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

// 这个 mixin 只负责“出站插桩”。
// 它挂在原版 PacketEncoder 上，不做业务逻辑，只把时机转发到 channel 包里的函数。
@Mixin(PacketEncoder.class)
public abstract class PacketOutPipeMixin<T extends PacketListener> {

    // 记录当前这个包开始编码前的 writerIndex，用来确定这个包在输出缓冲区里的字节范围。
    @Unique
    private int bandwidthoptimizer$writerIndexBefore;

    // 这个函数在 PacketEncoder.encode 开始时执行。
    // 作用是记住“这个包开始写入前”的位置，后面才能切出这一包自己的编码字节。
    @Inject(method = "encode", at = @At("HEAD"))
    private void bandwidthoptimizer$rememberWriterIndex(ChannelHandlerContext context, Packet<T> packet, ByteBuf out, CallbackInfo ci) {
        this.bandwidthoptimizer$writerIndexBefore = out.writerIndex();
    }

    // 这个函数在 PacketEncoder.encode 结束时执行。
    // 作用是把这一包刚编码出来的字节范围转发给 ChannelCaptureHooks。
    @Inject(method = "encode", at = @At("RETURN"))
    private void bandwidthoptimizer$captureEncodedPacket(ChannelHandlerContext context, Packet<T> packet, ByteBuf out, CallbackInfo ci) {
        ChannelCaptureHooks.captureOutboundEncodedPacket(context, packet, out, this.bandwidthoptimizer$writerIndexBefore);
    }
}
