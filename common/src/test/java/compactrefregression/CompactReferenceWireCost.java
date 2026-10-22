package compactrefregression;

import com.PinkCats.bandwidthoptimizer.channel.ChannelTransportPacketCodec;
import com.PinkCats.bandwidthoptimizer.channel.ChannelTransportSession;
import com.PinkCats.bandwidthoptimizer.chunk.integration.transport.envelope.ChunkTransportEnvelope;
import com.PinkCats.bandwidthoptimizer.chunk.integration.transport.envelope.ChunkTransportEnvelopeCodec;
import com.PinkCats.bandwidthoptimizer.chunk.protocol.hotspot.ChunkHotspotFrame;
import com.PinkCats.bandwidthoptimizer.chunk.protocol.hotspot.ChunkHotspotFrameOp;
import io.netty.channel.embedded.EmbeddedChannel;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/** Matched synthetic corpus through the actual mapping/Zstd/carrier codec. */
final class CompactReferenceWireCost {
    private record Cost(long envelopes, long carriers) {}

    static void verify() {
        for(boolean dynamic:new boolean[]{false,true}) for (int reuses : new int[]{0, 1, 4, 16}) {
            Cost legacy = run(false,reuses,dynamic), compact = run(true,reuses,dynamic);
            System.out.println("compact-ref matched corpus dynamic="+dynamic+" chunks=64 reuses="+reuses+" legacyEnvelopeB="+legacy.envelopes
                    +" compactEnvelopeB="+compact.envelopes+" legacyCarrierB="+legacy.carriers+" compactCarrierB="+compact.carriers);
            // Deliberately keep the perfectly repeating corpus as a hostile
            // compression case; never hide a one-time binding cost in averages.
            // FULL remains unchanged; a non-reused connection pays only one
            // capability exchange. Include the finite binding cost for reused
            // chunks, rather than claiming every compressed corpus improves.
            if(reuses==0 && compact.carriers-legacy.carriers>64) throw new AssertionError("No-reuse cost must stay connection-constant");
            if(compact.carriers-legacy.carriers>64*8) throw new AssertionError("Matched corpus binding cost regressed");
            if(dynamic && reuses>=4 && compact.carriers>=legacy.carriers) throw new AssertionError("Advancing repeated reuse must save compressed bytes");
        }
    }

    private static Cost run(boolean compact, int reuseRounds, boolean dynamic) {
        var server=new EmbeddedChannel(); var client=new EmbeddedChannel();
        try (var downSender=new ChannelTransportSession(); var downReceiver=new ChannelTransportSession();
             var upSender=new ChannelTransportSession(); var upReceiver=new ChannelTransportSession()) {
            for(var session:List.of(downSender,downReceiver,upSender,upReceiver)) session.setCrossFrameZstdEnabled(true);
            long envelopes=0,carriers=0;
            var scope=CompactReferenceRegressionMain.envelope(CompactReferenceRegressionMain.frame(ChunkHotspotFrameOp.SERVER_CACHE_SCOPE,0,0,0,CompactReferenceRegressionMain.HASH,"scope"));
            var bloom=CompactReferenceRegressionMain.envelope(CompactReferenceRegressionMain.frame(ChunkHotspotFrameOp.CLIENT_CACHE_BLOOM,0,0,0,CompactReferenceRegressionMain.HASH,"bloom"));
            for(var control:List.of(scope,bloom)) {
                boolean down=control==scope;
                var endpoint=down?server:client;
                byte[] wire=compact?ChunkTransportEnvelopeCodec.encodeEnvelope(endpoint,control):ChunkTransportEnvelopeCodec.encodeEnvelope(control);
                int before=lastCarrierBytes;
                byte[] restored=transfer(down?downSender:upSender,down?downReceiver:upReceiver,List.of(wire)).get(0);
                carriers+=lastCarrierBytes-before;
                if(compact) ChunkTransportEnvelopeCodec.decodeEnvelope(down?client:server,restored);
                envelopes+=wire.length;
            }
            List<ChunkHotspotFrame> identities=new ArrayList<>();
            for(int i=0;i<64;i++) {
                String hash=com.PinkCats.bandwidthoptimizer.chunk.snapshot.ChunkSnapshotFingerprintService.fingerprintOutboundPacket(payload(i)).hashHex();
                identities.add(CompactReferenceRegressionMain.frame(ChunkHotspotFrameOp.PUBLISH_FULL,1,i,-i,hash,"native_full"));
            }
            // Include initial FULL bindings and their explicit ACKs; not just warmed references.
            for(int round=-1;round<reuseRounds;round++) {
                var packets=new ArrayList<byte[]>(); var expected=new ArrayList<ChunkTransportEnvelope>();
                for(int i=0;i<64;i++) {
                    var identity=identities.get(i);
                    var source=round<0?identity:CompactReferenceRegressionMain.frame(ChunkHotspotFrameOp.PUBLISH_REF,1,i,-i,identity.payloadHash(),"reuse_persistent_client_cache_manifest");
                    if(dynamic) source=counter(source,300L+(round+1)*64+i);
                    byte[] payload=round<0?payload(i):new byte[0];
                    var envelope=new ChunkTransportEnvelope(source,payload); expected.add(envelope);
                    byte[] wire=compact?ChunkTransportEnvelopeCodec.encodeEnvelope(server,envelope):ChunkTransportEnvelopeCodec.encodeEnvelope(envelope);
                    packets.add(wire); envelopes+=wire.length;
                }
                int before=lastCarrierBytes;
                List<byte[]> restored=transfer(downSender,downReceiver,packets);
                carriers+=lastCarrierBytes-before;
                var acks=new ArrayList<byte[]>();
                for(int i=0;i<64;i++) {
                    var decoded=compact?ChunkTransportEnvelopeCodec.decodeEnvelope(client,restored.get(i)):ChunkTransportEnvelopeCodec.decodeEnvelope(restored.get(i));
                    CompactReferenceRegressionMain.equal(expected.get(i),decoded);
                    var source=decoded.frame();
                    var ack=CompactReferenceRegressionMain.envelope(CompactReferenceRegressionMain.frame(ChunkHotspotFrameOp.ACK,source.epoch(),i,-i,source.payloadHash(),"runtime_ref_reused_after_persistent_manifest"));
                    if(dynamic) ack=CompactReferenceRegressionMain.envelope(counter(ack.frame(),source.observedPacketCount()));
                    byte[] wire=compact?ChunkTransportEnvelopeCodec.encodeEnvelope(client,ack):ChunkTransportEnvelopeCodec.encodeEnvelope(ack);
                    acks.add(wire); envelopes+=wire.length;
                }
                before=lastCarrierBytes;
                for(byte[] ack:transfer(upSender,upReceiver,acks)) {
                    if(compact) ChunkTransportEnvelopeCodec.decodeEnvelope(server,ack);
                    else ChunkTransportEnvelopeCodec.decodeEnvelope(ack);
                }
                carriers+=lastCarrierBytes-before;
            }
            return new Cost(envelopes,carriers);
        } finally { server.finishAndReleaseAll(); client.finishAndReleaseAll(); }
    }

    private static int lastCarrierBytes;

    private static byte[] payload(int i) {
        byte[] payload=new byte[2048];
        for(int n=0;n<payload.length;n+=17) payload[n]=(byte)(i+n);
        return payload;
    }

    private static ChunkHotspotFrame counter(ChunkHotspotFrame f,long count) {
        return new ChunkHotspotFrame(f.protocolVersion(),f.operation(),f.epoch(),count,f.protocolName(),f.packetClassName(),
                f.hotspotKind(),f.laneKind(),f.coordinate(),f.originalEncodedBytes(),f.fullSnapshotVersion(),f.laneVersion(),
                f.baseSnapshotHash(),f.payloadHash(),f.deltaBytesSinceFullSnapshot(),f.reason());
    }

    private static List<byte[]> transfer(ChannelTransportSession sender,ChannelTransportSession receiver,List<byte[]> packets) {
        var wrapped=ChannelTransportPacketCodec.wrapBatchPackets(sender,packets);
        if(wrapped==null || wrapped.frameKind()!=ChannelTransportPacketCodec.FrameKind.STREAM_BATCH) throw new AssertionError("Not actual streaming carrier");
        var restored=ChannelTransportPacketCodec.tryUnwrapPacket(receiver,wrapped.transportFrameBytes());
        if(restored==null || restored.restoredPacketBytesList().size()!=packets.size()) throw new AssertionError("Carrier count changed");
        for(int i=0;i<packets.size();i++) if(!Arrays.equals(packets.get(i),restored.restoredPacketBytesList().get(i))) throw new AssertionError("Carrier order/bytes changed");
        lastCarrierBytes+=wrapped.transportFrameLength();
        return restored.restoredPacketBytesList();
    }
}
