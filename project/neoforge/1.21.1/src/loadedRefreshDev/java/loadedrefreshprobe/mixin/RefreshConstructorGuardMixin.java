package loadedrefreshprobe.mixin;
import net.minecraft.network.protocol.game.ClientboundLevelChunkPacketData;
import net.minecraft.world.level.chunk.LevelChunk;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
@Mixin(ClientboundLevelChunkPacketData.class)
public class RefreshConstructorGuardMixin {
 @Inject(method="<init>(Lnet/minecraft/world/level/chunk/LevelChunk;)V",at=@At("RETURN"))
 private void forbid(LevelChunk chunk,CallbackInfo ci){
  if(loadedrefreshprobe.RefreshWireProbe.rebuilding){loadedrefreshprobe.RefreshWireProbe.forbiddenConstructors++;throw new AssertionError("Cache capture called BE-serializing native constructor");}
 }
}
