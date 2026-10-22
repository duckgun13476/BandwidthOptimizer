package loadedrefreshprobe.mixin;

import com.PinkCats.bandwidthoptimizer.chunk.refresh.UpdatedChunkCacheStats;
import com.PinkCats.bandwidthoptimizer.chunk.classify.packet.ChunkPacketCoordinate;
import com.llamalad7.mixinextras.injector.wrapmethod.WrapMethod;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import org.spongepowered.asm.mixin.Mixin;

/** Test-only receipt distinguishes target success from unrelated chunk reuse. */
@Mixin(value=UpdatedChunkCacheStats.class,remap=false)
public abstract class RefreshUpdatedReuseMixin {
    @WrapMethod(method="referenceRestored")
    private static void restored(String token,ChunkPacketCoordinate coordinate,String hash,Operation<Void> original) {
        synchronized(UpdatedChunkCacheStats.class) {
            long before=UpdatedChunkCacheStats.reuses();original.call(token,coordinate,hash);
            if(UpdatedChunkCacheStats.reuses()>before && coordinate.chunkX()==loadedrefreshprobe.RefreshProbe.TARGET_X
                    && coordinate.chunkZ()==loadedrefreshprobe.RefreshProbe.TARGET_Z)
                loadedrefreshprobe.RefreshProbe.write("updated-target-reuse.json",java.util.Map.of("baselineHash",hash,"counterBefore",before,"counterAfter",UpdatedChunkCacheStats.reuses()));
        }
    }
}
