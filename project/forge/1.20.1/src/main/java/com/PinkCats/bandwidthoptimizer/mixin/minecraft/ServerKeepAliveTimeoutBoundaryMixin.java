package com.PinkCats.bandwidthoptimizer.mixin.minecraft;

import com.PinkCats.bandwidthoptimizer.connection.KeepAliveTimeoutDiagnostic;
import com.PinkCats.bandwidthoptimizer.channel.ChannelTransportHooks;
import com.PinkCats.bandwidthoptimizer.channel.ChannelTransportStreamingControlCodec;
import com.PinkCats.bandwidthoptimizer.connection.KeepAliveGraceController;
import net.minecraft.Util;
import net.minecraft.network.Connection;
import net.minecraft.network.chat.Component;
import net.minecraft.network.protocol.PacketFlow;
import net.minecraft.server.network.ServerGamePacketListenerImpl;
import org.objectweb.asm.Opcodes;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.Redirect;
import org.spongepowered.asm.mixin.injection.Slice;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(ServerGamePacketListenerImpl.class)
public abstract class ServerKeepAliveTimeoutBoundaryMixin {

    @Shadow private long keepAliveTime;
    @Shadow private boolean keepAlivePending;

    @Inject(
            method = "tick",
            at = @At(
                    value = "FIELD",
                    target = "Lnet/minecraft/server/network/ServerGamePacketListenerImpl;keepAlivePending:Z",
                    opcode = Opcodes.GETFIELD,
                    ordinal = 0
            )
    )
    private void bandwidthoptimizer$observeKeepAliveTimeoutBoundary(CallbackInfo ci) {
        if (!this.keepAlivePending) {
            return;
        }
        Connection connection = ((ServerGamePacketListenerImplAccessor) this).bandwidthoptimizer$getConnection();
        KeepAliveTimeoutDiagnostic.observeVanillaKeepAliveTimeoutBoundary(
                ((ConnectionAccessor) connection).bandwidthoptimizer$getChannel(),
                Math.max(0L, Util.getMillis() - this.keepAliveTime),
                true
        );
    }

    @Redirect(
            method = "tick",
            slice = @Slice(from = @At(
                    value = "FIELD",
                    target = "Lnet/minecraft/server/network/ServerGamePacketListenerImpl;keepAlivePending:Z",
                    opcode = Opcodes.GETFIELD,
                    ordinal = 0
            )),
            at = @At(
                    value = "INVOKE",
                    target = "Lnet/minecraft/server/network/ServerGamePacketListenerImpl;disconnect(Lnet/minecraft/network/chat/Component;)V",
                    ordinal = 0
            )
    )
    private void bandwidthoptimizer$applyKeepAliveGrace(ServerGamePacketListenerImpl listener, Component reason) {
        Connection connection = ((ServerGamePacketListenerImplAccessor) this).bandwidthoptimizer$getConnection();
        io.netty.channel.Channel channel = ((ConnectionAccessor) connection).bandwidthoptimizer$getChannel();
        if (KeepAliveGraceController.shouldDeferTimeout(
                channel,
                true,
                nonce -> ChannelTransportHooks.writeTransportCarrierPacketToPipeline(
                        channel,
                        PacketFlow.CLIENTBOUND,
                        ChannelTransportStreamingControlCodec.encodeKeepAliveProbePing(nonce)
                ) != null
        )) {
            return;
        }
        listener.disconnect(reason);
    }
}
