package loadedrefreshprobe.mixin;
import com.PinkCats.bandwidthoptimizer.chunk.plan.*;
import com.PinkCats.bandwidthoptimizer.chunk.classify.packet.ChunkPacketDescriptor;
import com.PinkCats.bandwidthoptimizer.chunk.snapshot.ChunkSnapshotFingerprint;
import com.PinkCats.bandwidthoptimizer.chunk.peer.ChunkPeerChunkStateSnapshot;
import com.PinkCats.bandwidthoptimizer.chunk.store.global.ChunkGlobalStoreObservation;
import com.PinkCats.bandwidthoptimizer.chunk.patch.ChunkPatchBuilder;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;
@Mixin(value=ChunkTransportPlanner.class,remap=false)
public abstract class RefreshPlannerMixin {
    @Inject(method="planOutboundTransport(Lcom/PinkCats/bandwidthoptimizer/chunk/classify/packet/ChunkPacketDescriptor;Lcom/PinkCats/bandwidthoptimizer/chunk/snapshot/ChunkSnapshotFingerprint;Lcom/PinkCats/bandwidthoptimizer/chunk/peer/ChunkPeerChunkStateSnapshot;Lcom/PinkCats/bandwidthoptimizer/chunk/store/global/ChunkGlobalStoreObservation;Lcom/PinkCats/bandwidthoptimizer/chunk/patch/ChunkPatchBuilder$ChunkPatchBuildResult;)Lcom/PinkCats/bandwidthoptimizer/chunk/plan/ChunkPlanDecision;",at=@At("RETURN"))
    private static void probe(ChunkPacketDescriptor descriptor,ChunkSnapshotFingerprint fingerprint,ChunkPeerChunkStateSnapshot snapshot,ChunkGlobalStoreObservation observation,ChunkPatchBuilder.ChunkPatchBuildResult patch,CallbackInfoReturnable<ChunkPlanDecision> ci){
        if(descriptor!=null&&descriptor.coordinate().present()) loadedrefreshprobe.RefreshWireProbe.planned(descriptor.coordinate().chunkX(),descriptor.coordinate().chunkZ(),fingerprint,snapshot,ci.getReturnValue());
    }
}
