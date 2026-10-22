package compactrefprobe.mixin;

import com.PinkCats.bandwidthoptimizer.chunk.integration.ChunkInboundDecodeResult;
import com.PinkCats.bandwidthoptimizer.chunk.integration.transport.ChunkTransportControlFrameSender;
import com.PinkCats.bandwidthoptimizer.chunk.integration.transport.ChunkTransportDispatcher;
import com.PinkCats.bandwidthoptimizer.chunk.integration.transport.envelope.ChunkCompactReferenceCodec;
import com.PinkCats.bandwidthoptimizer.chunk.integration.transport.envelope.ChunkTransportEnvelopeCodec;
import com.PinkCats.bandwidthoptimizer.chunk.protocol.hotspot.ChunkHotspotFrameOp;
import com.PinkCats.bandwidthoptimizer.chunk.snapshot.ChunkSnapshotFingerprintService;
import compactrefprobe.CompactRefProbe;
import io.netty.channel.ChannelHandlerContext;
import java.util.Map;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/** Test-only cache miss; no malformed network input or cache mutation. */
@Mixin(value = ChunkTransportDispatcher.class, remap = false)
public abstract class RecoveryProbeMixin {
    private static String missingHash;
    private static boolean recovered;

    @Inject(method = "tryDecodeInboundPacket", at = @At("HEAD"), cancellable = true)
    private static void observe(ChannelHandlerContext context, byte[] bytes, CallbackInfoReturnable<ChunkInboundDecodeResult> result) {
        if (!Boolean.getBoolean("compactrefprobe.forceMiss")) return;
        if (!ChunkTransportEnvelopeCodec.looksLikeEnvelope(bytes)) return;
        var envelope = ChunkTransportEnvelopeCodec.decodeEnvelope(context.channel(), bytes);
        var frame = envelope.frame();
        if (missingHash == null && frame.operation() == ChunkHotspotFrameOp.PUBLISH_REF) {
            missingHash = frame.payloadHash();
            CompactRefProbe.write("forced-miss.json", Map.of("compact", ChunkCompactReferenceCodec.isCompact(bytes), "bytes", bytes.length,
                    "epoch", frame.epoch(), "hash", missingHash));
            if (!ChunkTransportControlFrameSender.sendNack(context, frame, "runtime_ref_missing_base")) throw new AssertionError("NACK not admitted");
            result.setReturnValue(ChunkInboundDecodeResult.consumeControlFrame());
        } else if (!recovered && missingHash != null && frame.operation() == ChunkHotspotFrameOp.PUBLISH_FULL && missingHash.equals(frame.payloadHash())) {
            String actual = ChunkSnapshotFingerprintService.fingerprintOutboundPacket(envelope.copyOriginalPacketBytes()).hashHex();
            if (!actual.equals(missingHash)) throw new AssertionError("Recovered FULL bytes differ from referenced SHA256");
            recovered = true;
            CompactRefProbe.write("recovered-full.json", Map.of("exactHash", true, "hash", actual, "bytes", envelope.copyOriginalPacketBytes().length));
        }
    }
}
