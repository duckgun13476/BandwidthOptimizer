package com.PinkCats.bandwidthoptimizer.mixin.minecraft;

import com.PinkCats.bandwidthoptimizer.connection.KeepAliveTimeoutDiagnostic;
import net.minecraft.Util;
import net.minecraft.network.Connection;
import net.minecraft.server.network.ServerGamePacketListenerImpl;
import org.objectweb.asm.Opcodes;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
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
}
