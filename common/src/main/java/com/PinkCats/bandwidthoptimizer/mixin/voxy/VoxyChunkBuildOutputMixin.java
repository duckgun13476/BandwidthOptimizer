package com.PinkCats.bandwidthoptimizer.mixin.voxy;

import com.PinkCats.bandwidthoptimizer.compat.voxy.VoxyChunkBoundCompat;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Pseudo;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Coerce;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import java.util.Map;

@Pseudo
@Mixin(targets = "net.caffeinemc.mods.sodium.client.render.chunk.compile.ChunkBuildOutput", remap = false)
public abstract class VoxyChunkBuildOutputMixin {
    @Inject(method = "<init>", at = @At("RETURN"), remap = false, require = 0)
    private void bo$recordVoxyChunkMeshSize(
            @Coerce Object renderSection,
            int submitTime,
            @Coerce Object translucentData,
            @Coerce Object info,
            Map<?, ?> meshes,
            CallbackInfo ci
    ) {
        VoxyChunkBoundCompat.recordMeshSize(renderSection, meshes);
    }
}
