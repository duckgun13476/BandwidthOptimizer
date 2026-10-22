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
        if(f.operation()!=ChunkHotspotFrameOp.PUBLISH_REF || !f.coordinate().present() || f.coordinate().chunkX()!=2 || f.coordinate().chunkZ()!=0)return;
        done=true;
        loadedrefreshprobe.RefreshWireProbe.forcedMiss(Map.of("bytes",bytes.length,"compact",ChunkCompactReferenceCodec.isCompact(bytes),"hash",f.payloadHash(),"epoch",f.epoch(),"version",f.fullSnapshotVersion()));
        ChunkTransportControlFrameSender.sendNack(context,f,"runtime_ref_missing_base");
        ci.setReturnValue(ChunkInboundDecodeResult.consumeControlFrame());
    }
}
