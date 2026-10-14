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
        // Motion: VarInt ID + three shorts + dispatch VarInt <= 16 bytes.
        // Head rotation: VarInt ID + byte + dispatch VarInt <= 11 bytes.
        // Both are below BOTH native compressed/uncompressed splitter limits.
        if (encoder != null && BoundedPacketSplitterCompat.canSkip(packet, encoder.getProtocolInfo().codec())) {
            output.add(packet);
            callback.cancel();
        }
    }
}
