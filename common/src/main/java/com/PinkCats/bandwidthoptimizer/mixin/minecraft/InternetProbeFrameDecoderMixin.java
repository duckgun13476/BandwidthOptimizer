package com.PinkCats.bandwidthoptimizer.mixin.minecraft;

import com.PinkCats.bandwidthoptimizer.connection.ConnectionInternetProbeGuard;
import io.netty.buffer.ByteBuf;
import io.netty.channel.ChannelHandlerContext;
import net.minecraft.network.Varint21FrameDecoder;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import java.util.List;

@Mixin(Varint21FrameDecoder.class)
public abstract class InternetProbeFrameDecoderMixin {

    @Inject(method = "decode", at = @At("HEAD"), cancellable = true)
    private void bandwidthoptimizer$rejectInternetProbe(
            ChannelHandlerContext context,
            ByteBuf input,
            List<Object> output,
            CallbackInfo ci
    ) {
        if (ConnectionInternetProbeGuard.tryReject(context, input)) {
            ci.cancel();
        }
    }
}
