package com.PinkCats.bandwidthoptimizer.mixin.neoforge;

import com.PinkCats.bandwidthoptimizer.connection.ConnectionPayloadTaskGuard;
import net.neoforged.neoforge.network.handling.ClientPayloadContext;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.ModifyVariable;

import java.util.function.Supplier;

@Mixin(ClientPayloadContext.class)
public abstract class ClientPayloadContextMixin {

    @ModifyVariable(
            method = "enqueueWork(Ljava/lang/Runnable;)Ljava/util/concurrent/CompletableFuture;",
            at = @At("HEAD"),
            argsOnly = true
    )
    private Runnable bandwidthoptimizer$guardRunnable(Runnable task) {
        ClientPayloadContext context = (ClientPayloadContext) (Object) this;
        return ConnectionPayloadTaskGuard.guard(
                context.connection().channel(),
                context.payloadId().toString(),
                task
        );
    }

    @ModifyVariable(
            method = "enqueueWork(Ljava/util/function/Supplier;)Ljava/util/concurrent/CompletableFuture;",
            at = @At("HEAD"),
            argsOnly = true
    )
    private <T> Supplier<T> bandwidthoptimizer$guardSupplier(Supplier<T> task) {
        ClientPayloadContext context = (ClientPayloadContext) (Object) this;
        return ConnectionPayloadTaskGuard.guard(
                context.connection().channel(),
                context.payloadId().toString(),
                task
        );
    }
}
