package com.PinkCats.bandwidthoptimizer.mixin.neoforge;

import com.PinkCats.bandwidthoptimizer.integration.neoforge.BoundedPacketSplitterCompat;
import io.netty.channel.ChannelHandlerContext;
import java.util.List;
import net.minecraft.network.PacketEncoder;
import net.minecraft.network.protocol.Packet;
import net.neoforged.neoforge.network.filters.GenericPacketSplitter;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(value = GenericPacketSplitter.class, priority = 900)
public abstract class GenericPacketSplitterMixin {
    @Inject(method = "encode(Lio/netty/channel/ChannelHandlerContext;Lnet/minecraft/network/protocol/Packet;Ljava/util/List;)V", at = @At("HEAD"), cancellable = true, remap = false)
    private void bandwidthoptimizer$boundedPacket(ChannelHandlerContext context, Packet<?> packet, List<Object> output, CallbackInfo callback) {
        PacketEncoder<?> encoder = context.pipeline().get("encoder") instanceof PacketEncoder<?> nativeEncoder ? nativeEncoder : null;
        // 26.x motion uses LpVec3 (<=21 bytes including dispatch), head <=11.
        // The shared helper checks the native scalar codecs and their call sites.
        if (encoder != null && BoundedPacketSplitterCompat.canSkip(packet, encoder.getProtocolInfo().codec())) {
            output.add(packet);
            callback.cancel();
        }
    }
}
