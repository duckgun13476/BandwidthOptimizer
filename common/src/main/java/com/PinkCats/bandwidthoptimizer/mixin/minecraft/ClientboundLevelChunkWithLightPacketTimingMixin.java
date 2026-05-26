package com.PinkCats.bandwidthoptimizer.mixin.minecraft;

import com.PinkCats.bandwidthoptimizer.chunk.debug.ChunkLoadDelayProbe;
import net.minecraft.network.protocol.game.ClientboundLevelChunkWithLightPacket;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(ClientboundLevelChunkWithLightPacket.class)
public class ClientboundLevelChunkWithLightPacketTimingMixin {

    // Constructor signatures differ across Forge versions; read stable packet coordinates after return.
    @Inject(
            method = "<init>",
            at = @At("RETURN"),
            require = 0
    )
    private void bandwidthoptimizer$recordChunkPacketTiming(CallbackInfo ci) {
        ClientboundLevelChunkWithLightPacket packet = (ClientboundLevelChunkWithLightPacket) (Object) this;
        ChunkLoadDelayProbe.recordVanillaLevelChunkPacketConstructed(
                packet,
                packet.getX(),
                packet.getZ(),
                0L
        );
    }
}
