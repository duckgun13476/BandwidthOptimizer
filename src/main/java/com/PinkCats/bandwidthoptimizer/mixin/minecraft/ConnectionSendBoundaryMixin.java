package com.PinkCats.bandwidthoptimizer.mixin.minecraft;

import com.PinkCats.bandwidthoptimizer.chunk.integration.transport.ChunkTransportBoundaryController;
import io.netty.channel.Channel;
import net.minecraft.network.Connection;
import net.minecraft.network.PacketSendListener;
import net.minecraft.network.protocol.Packet;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(Connection.class)
public abstract class ConnectionSendBoundaryMixin {

    @Shadow
    private Channel channel;

    // Chunk send
    @Inject(method = "sendPacket", at = @At("HEAD"))
    private void bandwidthoptimizer$notePacketSendBoundary(Packet<?> packet, PacketSendListener listener, CallbackInfo ci) {
        ChunkTransportBoundaryController.notePacketSendListener(this.channel, listener);
    }
}
