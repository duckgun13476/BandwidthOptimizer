package loadedrefreshprobe.mixin;

import com.PinkCats.bandwidthoptimizer.chunk.patch.*;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.*;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/** Test-only receipt of the actual production patch applier output. */
@Mixin(value=ChunkPatchApplier.class,remap=false)
public abstract class RefreshCoreRestoreMixin {
    @Inject(method="applyPatch",at=@At("RETURN"))
    private static void receipt(ChunkPatch patch,byte[] base,String hash,CallbackInfoReturnable<byte[]> ci) {
        byte[] bytes=ci.getReturnValue();
        try {
            // Target coordinates from the fixture constants (the fixture factory) are read directly
            // from the restored native packet, after its packet ID varint.
            int i=0;while((bytes[i++]&128)!=0){}
            var body=java.nio.ByteBuffer.wrap(bytes,i,8);
            if(body.getInt()!=loadedrefreshprobe.RefreshProbe.TARGET_X || body.getInt()!=loadedrefreshprobe.RefreshProbe.TARGET_Z)return;
            java.nio.file.Files.write(loadedrefreshprobe.RefreshProbe.ROOT.resolve("patch-restored.bin"),bytes);
            if(patch.patchMode()!=ChunkPatchMode.CORE_OVERLAY)return;
            java.nio.file.Files.write(loadedrefreshprobe.RefreshProbe.ROOT.resolve("core-restored.bin"),bytes);
            loadedrefreshprobe.RefreshProbe.write("core-restored.json",java.util.Map.of("targetHash",hash,"fullBytes",bytes.length,"patchBytes",patch.encode().length,"baseHash",patch.basePayloadHash()));
        }catch(java.io.IOException failure){throw new RuntimeException(failure);}
    }
}
