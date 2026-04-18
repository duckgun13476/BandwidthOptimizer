package com.PinkCats.bandwidthoptimizer.mixin.minecraft;

import com.PinkCats.bandwidthoptimizer.channel.ChannelCaptureHooks;
import io.netty.buffer.ByteBuf;
import io.netty.channel.ChannelHandlerContext;
import net.minecraft.network.PacketDecoder;
import net.minecraft.network.PacketListener;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import java.util.List;

// 这个 mixin 只负责“入站插桩”。
// 它挂在原版 PacketDecoder 上，不做业务逻辑，只把时机转发到 channel 包里的函数。
@Mixin(PacketDecoder.class)
public abstract class PacketInPipeMixin<T extends PacketListener> {

    // 这个函数在 PacketDecoder.decode 开始时执行。
    // 作用是在原版还没把字节还原成 Packet 对象之前，先把当前待解码字节转发给 ChannelCaptureHooks。
    @Inject(method = "decode", at = @At("HEAD"))
    private void bandwidthoptimizer$capturePreDecodeFrame(ChannelHandlerContext context, ByteBuf in, List<Object> out, CallbackInfo ci) {
        ChannelCaptureHooks.captureInboundPreDecode(context, in);
    }
}
