package loadedrefreshprobe.mixin;
import com.PinkCats.bandwidthoptimizer.client.cache.LoadedChunkPacketRebuilder;
import com.llamalad7.mixinextras.injector.wrapmethod.WrapMethod;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import net.minecraft.network.protocol.game.ClientboundLevelChunkWithLightPacket;
import net.minecraft.world.level.chunk.LevelChunk;
import net.minecraft.world.level.lighting.LevelLightEngine;
import org.spongepowered.asm.mixin.Mixin;
import java.util.*;

@Mixin(value=LoadedChunkPacketRebuilder.class,remap=false)
public class RefreshCaptureProbeMixin {
 private static int calls,missing,lightWork,results;
 @WrapMethod(method="rebuild")
 private static ClientboundLevelChunkWithLightPacket inspect(byte[] base,LevelChunk chunk,LevelLightEngine light,
         java.util.function.Function<byte[], ? extends net.minecraft.network.protocol.Packet<?>> decoder,
         Operation<ClientboundLevelChunkWithLightPacket> original) {
  calls++;if(base==null)missing++;if(light.hasLightWork())lightWork++;
  byte[] before=base==null?null:sha(base);
  ClientboundLevelChunkWithLightPacket value;
  loadedrefreshprobe.RefreshWireProbe.rebuilding=true;
  try {value=original.call(base,chunk,light,decoder);} finally {loadedrefreshprobe.RefreshWireProbe.rebuilding=false;}
  if(base!=null && !Arrays.equals(before,sha(base)))throw new AssertionError("Mutated original cache body");
  if(value!=null)results++;
  if(value!=null && !java.nio.file.Files.exists(java.nio.file.Path.of(System.getProperty("refreshprobe.root"),"capture-cost.json"))) {
   var encoder=com.PinkCats.bandwidthoptimizer.chunk.packet.ClientboundPlayPacketCodec.encoderForRegistry(chunk.getLevel().registryAccess());
   var bean=(com.sun.management.ThreadMXBean)java.lang.management.ManagementFactory.getThreadMXBean();
   loadedrefreshprobe.RefreshWireProbe.rebuilding=true;
   try {
    for(int i=0;i<20;i++)encoder.apply(original.call(base,chunk,light,decoder));
    long id=Thread.currentThread().getId(),a0=bean.getThreadAllocatedBytes(id),c0=bean.getCurrentThreadCpuTime(),t0=System.nanoTime(),count=0,bytes=0;
    do {var sample=encoder.apply(original.call(base,chunk,light,decoder));if(sample==null)throw new AssertionError("Cost capture failed");bytes+=sample.length;count++;} while(System.nanoTime()-t0<600000000L);
    loadedrefreshprobe.RefreshProbe.write("capture-cost.json",Map.of("iterations",count,"bytesPerCapture",bytes/count,
      "allocatedBPerCapture",(bean.getThreadAllocatedBytes(id)-a0)/(double)count,"cpuNsPerCapture",(bean.getCurrentThreadCpuTime()-c0)/(double)count,
      "wallNsPerCapture",(System.nanoTime()-t0)/(double)count,"includes","decode+rebuild+encode; excludes hash/store/disk/manifest","blockEntities",chunk.getBlockEntitiesPos().size()));
   } finally {loadedrefreshprobe.RefreshWireProbe.rebuilding=false;}
   if(!Arrays.equals(before,sha(base)))throw new AssertionError("Cost probe mutated original cache body");
  }
  loadedrefreshprobe.RefreshProbe.write("capture-probe.json",Map.of("calls",calls,"baseMissing",missing,"lightWork",lightWork,"results",results,
    "lastBytes",base==null?0:base.length,"lastChunk",chunk.getPos().toString(),"lastBECount",chunk.getBlockEntitiesPos().size(),"forbiddenConstructors",loadedrefreshprobe.RefreshWireProbe.forbiddenConstructors,"baseImmutable",true));
  return value;
 }
 private static byte[] sha(byte[] bytes){try{return java.security.MessageDigest.getInstance("SHA-256").digest(bytes);}catch(Exception e){throw new RuntimeException(e);}}
}
