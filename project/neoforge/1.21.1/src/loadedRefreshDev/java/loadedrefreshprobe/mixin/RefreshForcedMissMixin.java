package loadedrefreshprobe.mixin;
import com.PinkCats.bandwidthoptimizer.chunk.integration.transport.*;
import com.PinkCats.bandwidthoptimizer.chunk.integration.ChunkInboundDecodeResult;
import com.PinkCats.bandwidthoptimizer.chunk.integration.transport.envelope.*;
import com.PinkCats.bandwidthoptimizer.chunk.protocol.hotspot.ChunkHotspotFrameOp;
import io.netty.channel.ChannelHandlerContext;
import java.util.Map;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/** Local fixture only: emulate one cache miss, exercise the real wire NACK/FULL recovery. */
@Mixin(value=ChunkTransportDispatcher.class,remap=false)
public abstract class RefreshForcedMissMixin {
    private static boolean done;
    @Inject(method="tryDecodeInboundPacket",at=@At("HEAD"),cancellable=true)
    private static void miss(ChannelHandlerContext context,byte[] bytes,CallbackInfoReturnable<ChunkInboundDecodeResult> ci) {
        if(!Boolean.getBoolean("refreshprobe.forceMiss") || done || !ChunkTransportEnvelopeCodec.looksLikeEnvelope(bytes))return;
        var envelope=ChunkTransportEnvelopeCodec.decodeEnvelope(context.channel(),bytes);
        var f=envelope.frame();
        if((f.operation()!=ChunkHotspotFrameOp.PUBLISH_REF && f.operation()!=ChunkHotspotFrameOp.PUBLISH_PATCH) || !f.coordinate().present()
                || f.coordinate().chunkX()!=loadedrefreshprobe.RefreshProbe.TARGET_X
                || f.coordinate().chunkZ()!=loadedrefreshprobe.RefreshProbe.TARGET_Z)return;
        done=true;
        loadedrefreshprobe.RefreshWireProbe.forcedMiss(Map.of("operation",f.operation().name(),"bytes",bytes.length,"compact",ChunkCompactReferenceCodec.isCompact(bytes),"hash",f.payloadHash(),"epoch",f.epoch(),"version",f.fullSnapshotVersion()));
        ChunkTransportControlFrameSender.sendNack(context,f,f.operation()==ChunkHotspotFrameOp.PUBLISH_REF?"runtime_ref_missing_base":"runtime_patch_missing_full_base");
        ci.setReturnValue(ChunkInboundDecodeResult.consumeControlFrame());
    }
}
