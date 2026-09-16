package com.PinkCats.bandwidthoptimizer.mixin.minecraft;

import com.PinkCats.bandwidthoptimizer.connection.KeepAliveTimeoutDiagnostic;
import com.PinkCats.bandwidthoptimizer.channel.ChannelTransportHooks;
import com.PinkCats.bandwidthoptimizer.channel.ChannelTransportStreamingControlCodec;
import com.PinkCats.bandwidthoptimizer.connection.KeepAliveGraceController;
import net.minecraft.Util;
import net.minecraft.network.Connection;
import net.minecraft.network.protocol.PacketFlow;
import net.minecraft.server.network.ServerCommonPacketListenerImpl;
import net.minecraft.server.network.ServerGamePacketListenerImpl;
import org.objectweb.asm.Opcodes;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Redirect;

@Mixin(ServerCommonPacketListenerImpl.class)
public abstract class ServerKeepAliveTimeoutBoundaryMixin {

    @Shadow private long keepAliveTime;
    @Shadow private boolean keepAlivePending;

    @Redirect(
            method = "keepConnectionAlive",
            at = @At(
                    value = "FIELD",
                    target = "Lnet/minecraft/server/network/ServerCommonPacketListenerImpl;keepAlivePending:Z",
                    opcode = Opcodes.GETFIELD,
                    ordinal = 0
            )
    )
    private boolean bandwidthoptimizer$applyKeepAliveGraceAtPendingRead(ServerCommonPacketListenerImpl listener) {
        if (!this.keepAlivePending) {
            return false;
        }
        Connection connection = ((ServerGamePacketListenerImplAccessor) this).bandwidthoptimizer$getConnection();
        io.netty.channel.Channel channel = ((ConnectionAccessor) connection).bandwidthoptimizer$getChannel();
        KeepAliveTimeoutDiagnostic.observeVanillaKeepAliveTimeoutBoundary(
                channel,
                Math.max(0L, Util.getMillis() - this.keepAliveTime),
                (Object) this instanceof ServerGamePacketListenerImpl
        );
        if (KeepAliveGraceController.shouldDeferTimeout(channel, (Object) this instanceof ServerGamePacketListenerImpl,
                nonce -> ChannelTransportHooks.writeTransportCarrierPacketToPipeline(channel, PacketFlow.CLIENTBOUND,
                        ChannelTransportStreamingControlCodec.encodeKeepAliveProbePing(nonce)) != null)) {
            return false;
        }
        return true;
    }
}
