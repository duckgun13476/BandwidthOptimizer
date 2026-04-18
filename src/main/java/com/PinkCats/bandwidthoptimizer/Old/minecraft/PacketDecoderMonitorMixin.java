package com.PinkCats.bandwidthoptimizer.Old.minecraft;

import com.PinkCats.bandwidthoptimizer.Old.optimise.monitor.PacketTrafficMonitor;
import io.netty.buffer.ByteBuf;
import io.netty.channel.ChannelHandlerContext;
import net.minecraft.network.PacketDecoder;
import net.minecraft.network.PacketListener;
import net.minecraft.network.protocol.Packet;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import java.util.List;

@Mixin(PacketDecoder.class)
public abstract class PacketDecoderMonitorMixin<T extends PacketListener> {

    @Unique
    private int bandwidthoptimizer$readerIndexBefore;

    @Unique
    private int bandwidthoptimizer$outputSizeBefore;

    @Inject(method = "decode", at = @At("HEAD"))
    private void bandwidthoptimizer$captureDecodeStart(ChannelHandlerContext context, ByteBuf in, List<Object> out, CallbackInfo ci) {
        this.bandwidthoptimizer$readerIndexBefore = in.readerIndex();
        this.bandwidthoptimizer$outputSizeBefore = out.size();
    }

    @Inject(method = "decode", at = @At("RETURN"))
    private void bandwidthoptimizer$recordDecodedPackets(ChannelHandlerContext context, ByteBuf in, List<Object> out, CallbackInfo ci) {
        int produced = out.size() - this.bandwidthoptimizer$outputSizeBefore;
        if (produced <= 0) {
            return;
        }

        int bytesConsumed = Math.max(in.readerIndex() - this.bandwidthoptimizer$readerIndexBefore, 0);
        int bytesPerPacket = Math.max(bytesConsumed / produced, 0);
        byte[] payload = PacketTrafficMonitor.copyBytes(in, this.bandwidthoptimizer$readerIndexBefore, in.readerIndex());
        for (int i = this.bandwidthoptimizer$outputSizeBefore; i < out.size(); i++) {
            Object decoded = out.get(i);
            if (decoded instanceof Packet<?> packet) {
                PacketTrafficMonitor.recordDecoded(packet, bytesPerPacket, payload, produced, i - this.bandwidthoptimizer$outputSizeBefore);
            }
        }
    }
}
