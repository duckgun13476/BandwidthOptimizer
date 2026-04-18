package com.PinkCats.bandwidthoptimizer.Old.minecraft;

import com.PinkCats.bandwidthoptimizer.Old.optimise.monitor.PacketTrafficMonitor;
import net.minecraft.server.MinecraftServer;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(MinecraftServer.class)
public abstract class MinecraftServerPacketStatsMixin {

    @Unique
    private static long bandwidthoptimizer$packetStatTicks;

    @Inject(method = "tickServer", at = @At("HEAD"))
    private void bandwidthoptimizer$flushPacketStats(CallbackInfo ci) {
        bandwidthoptimizer$packetStatTicks++;
        PacketTrafficMonitor.onServerTick((MinecraftServer) (Object) this, bandwidthoptimizer$packetStatTicks);
    }
}
