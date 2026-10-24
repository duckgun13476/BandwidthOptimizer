package compactrefregression;

import com.PinkCats.bandwidthoptimizer.chunk.classify.ChunkHotspotKind;
import com.PinkCats.bandwidthoptimizer.chunk.classify.ChunkLaneKind;
import com.PinkCats.bandwidthoptimizer.chunk.classify.packet.ChunkPacketCoordinate;
import com.PinkCats.bandwidthoptimizer.chunk.integration.transport.envelope.ChunkTransportEnvelope;
import com.PinkCats.bandwidthoptimizer.chunk.integration.transport.envelope.ChunkTransportEnvelopeCodec;
import com.PinkCats.bandwidthoptimizer.chunk.protocol.hotspot.ChunkHotspotFrame;
import com.PinkCats.bandwidthoptimizer.chunk.protocol.hotspot.ChunkHotspotFrameOp;
import io.netty.channel.embedded.EmbeddedChannel;
import io.netty.util.AttributeKey;
import com.PinkCats.bandwidthoptimizer.channel.ChannelTransportSession;
import java.lang.management.ManagementFactory;
import java.util.Arrays;
import java.util.Map;

public final class CompactReferenceRegressionMain {
    static final String HASH="a".repeat(64), OTHER="b".repeat(64);
    static ChunkHotspotFrame frame(ChunkHotspotFrameOp op,long epoch,int x,int z,String hash,String reason) {
        return new ChunkHotspotFrame(1,op,epoch,300,"PLAY","net.minecraft.network.protocol.game.ClientboundLevelChunkWithLightPacket",
                ChunkHotspotKind.FULL_CHUNK,ChunkLaneKind.FULL,ChunkPacketCoordinate.ofChunk(x,z),0,2,0,hash,hash,0,reason);
    }
    static ChunkTransportEnvelope envelope(ChunkHotspotFrame f){return new ChunkTransportEnvelope(f,new byte[0]);}
    static void equal(ChunkTransportEnvelope a,ChunkTransportEnvelope b) {
        check(a.frame().equals(b.frame()),"every recovery/ACK field preserved");
        check(Arrays.equals(a.copyOriginalPacketBytes(),b.copyOriginalPacketBytes()),"payload exact");
    }
    static void negotiate(EmbeddedChannel server,EmbeddedChannel client) {
        var scope=envelope(frame(ChunkHotspotFrameOp.SERVER_CACHE_SCOPE,0,0,0,HASH,"scope"));
        var bloom=new ChunkTransportEnvelope(frame(ChunkHotspotFrameOp.CLIENT_CACHE_BLOOM,0,0,0,HASH,"bloom"),new byte[]{1,2,3});
        equal(scope,ChunkTransportEnvelopeCodec.decodeEnvelope(client,ChunkTransportEnvelopeCodec.encodeEnvelope(server,scope)));
        equal(bloom,ChunkTransportEnvelopeCodec.decodeEnvelope(server,ChunkTransportEnvelopeCodec.encodeEnvelope(client,bloom)));
    }
    static void acknowledge(EmbeddedChannel server,EmbeddedChannel client,ChunkHotspotFrame f) {
        var ack=envelope(new ChunkHotspotFrame(1,ChunkHotspotFrameOp.ACK,f.epoch(),f.observedPacketCount(),f.protocolName(),f.packetClassName(),
                f.hotspotKind(),f.laneKind(),f.coordinate(),0,f.fullSnapshotVersion(),f.laneVersion(),f.baseSnapshotHash(),f.payloadHash(),0,"runtime_ref_reused_after_persistent_manifest"));
        equal(ack,ChunkTransportEnvelopeCodec.decodeEnvelope(server,ChunkTransportEnvelopeCodec.encodeEnvelope(client,ack)));
    }
    public static void main(String[] args) {
        var server=new EmbeddedChannel();var client=new EmbeddedChannel();
        try {
            var ref=envelope(frame(ChunkHotspotFrameOp.PUBLISH_REF,1,1,0,HASH,"reuse_persistent_client_cache_manifest"));
            byte[] legacy=ChunkTransportEnvelopeCodec.encodeEnvelope(ref);
            check(Arrays.equals(legacy,ChunkTransportEnvelopeCodec.encodeEnvelope(server,ref)),"unnegotiated peer stays legacy");
            var earlyBloom=envelope(frame(ChunkHotspotFrameOp.CLIENT_CACHE_BLOOM,0,0,0,HASH,"bloom"));
            check(Arrays.equals(ChunkTransportEnvelopeCodec.encodeEnvelope(earlyBloom),ChunkTransportEnvelopeCodec.encodeEnvelope(client,earlyBloom)),"no unsolicited acceptance before server offer");
            if(args.length>0 && args[0].equals("disabled")) {
                negotiate(server,client);
                check(Arrays.equals(legacy,ChunkTransportEnvelopeCodec.encodeEnvelope(server,ref)),"disabled keeps old wire");
                System.out.println("Compact REF disabled regression PASS");return;
            }
            negotiate(server,client);
            verifyBackendReplacement();
            verifyMixedPeers();
            CompactReferenceWireCost.verify();
            byte[] definition=ChunkTransportEnvelopeCodec.encodeEnvelope(server,ref);
            acknowledge(server,client,ref.frame()); // ordinary old ACK cannot confirm a dictionary binding
            check(Arrays.equals(definition,ChunkTransportEnvelopeCodec.encodeEnvelope(server,ref)),"unacknowledged definition is resent, not referenced");
            equal(ref,ChunkTransportEnvelopeCodec.decodeEnvelope(client,definition));
            // Drop a genuine binding ACK; the next self-contained definition must rearm it.
            var ackSource=ref.frame();
            var droppedAck=envelope(new ChunkHotspotFrame(1,ChunkHotspotFrameOp.ACK,ackSource.epoch(),ackSource.observedPacketCount(),ackSource.protocolName(),ackSource.packetClassName(),ackSource.hotspotKind(),ackSource.laneKind(),ackSource.coordinate(),0,ackSource.fullSnapshotVersion(),0,HASH,HASH,0,"runtime_ref_reused_after_persistent_manifest"));
            ChunkTransportEnvelopeCodec.encodeEnvelope(client,droppedAck);
            byte[] retry=ChunkTransportEnvelopeCodec.encodeEnvelope(server,ref);
            check(Arrays.equals(definition,retry),"lost ACK keeps self-contained retransmission");
            equal(ref,ChunkTransportEnvelopeCodec.decodeEnvelope(client,retry));
            acknowledge(server,client,ref.frame());
            byte[] compact=ChunkTransportEnvelopeCodec.encodeEnvelope(server,ref);
            equal(ref,ChunkTransportEnvelopeCodec.decodeEnvelope(client,compact));
            for(String reason:new String[]{"runtime_full_received","runtime_ref_reused_after_watch_boundary",
                    "runtime_ref_reused_from_persistent_cache","runtime_ref_reused_after_persistent_manifest","future_ack_reason"}) {
                var ack=envelope(frame(ChunkHotspotFrameOp.ACK,1,1,0,HASH,reason));
                equal(ack,ChunkTransportEnvelopeCodec.decodeEnvelope(server,ChunkTransportEnvelopeCodec.encodeEnvelope(client,ack)));
            }
            verifyStreaming(server, client, ref);
            benchmark(server, client, ref);
            check(legacy.length==331 && definition.length==85 && compact.length==18,"exact representative byte costs");
            var full=new ChunkTransportEnvelope(frame(ChunkHotspotFrameOp.PUBLISH_FULL,1,-50,100,OTHER,"native_full"),new byte[]{3,4,5});
            check(Arrays.equals(ChunkTransportEnvelopeCodec.encodeEnvelope(full),ChunkTransportEnvelopeCodec.encodeEnvelope(server,full)),"FULL wire stays byte-identical even after negotiation");
            equal(full,ChunkTransportEnvelopeCodec.decodeEnvelope(client,ChunkTransportEnvelopeCodec.encodeEnvelope(server,full)));
            acknowledge(server,client,full.frame());
            for(String reason:new String[]{"reuse_acknowledged_full_snapshot","reuse_persistent_client_cache_manifest",
                    "reuse_inflight_full_snapshot_before_ack","reuse_cached_full_snapshot_after_watch_boundary"}) {
                var f=new ChunkHotspotFrame(1,ChunkHotspotFrameOp.PUBLISH_REF,1,Long.MAX_VALUE,"PLAY",full.frame().packetClassName(),
                        ChunkHotspotKind.FULL_CHUNK,ChunkLaneKind.FULL,full.frame().coordinate(),0,Long.MAX_VALUE,Long.MAX_VALUE,
                        OTHER,OTHER,Long.MAX_VALUE,reason);
                equal(envelope(f),ChunkTransportEnvelopeCodec.decodeEnvelope(client,ChunkTransportEnvelopeCodec.encodeEnvelope(server,envelope(f))));
            }
            var newer=envelope(frame(ChunkHotspotFrameOp.PUBLISH_REF,2,1,0,OTHER,ref.frame().reason()));
            equal(newer,ChunkTransportEnvelopeCodec.decodeEnvelope(client,ChunkTransportEnvelopeCodec.encodeEnvelope(server,newer)));
            equal(ref,ChunkTransportEnvelopeCodec.decodeEnvelope(client,compact)); // old epoch retains its original identity
            var unknownReason=envelope(frame(ChunkHotspotFrameOp.PUBLISH_REF,1,1,0,HASH,"future_reason"));
            check(Arrays.equals(ChunkTransportEnvelopeCodec.encodeEnvelope(unknownReason),ChunkTransportEnvelopeCodec.encodeEnvelope(server,unknownReason)),"unknown reasons remain legacy");
            var unbound=new EmbeddedChannel();
            try {reject(()->ChunkTransportEnvelopeCodec.decodeEnvelope(unbound,compact));}finally{unbound.finishAndReleaseAll();}
            byte[] unknownId=compact.clone();unknownId[10]=15;unknownId[11]=(byte)255;
            reject(()->ChunkTransportEnvelopeCodec.decodeEnvelope(client,unknownId));
            byte[] truncated=Arrays.copyOf(compact,compact.length-1);
            reject(()->ChunkTransportEnvelopeCodec.decodeEnvelope(client,truncated));
            byte[] rebind=ChunkTransportEnvelopeCodec.encodeEnvelope(server,envelope(frame(ChunkHotspotFrameOp.PUBLISH_REF,3,2,0,HASH,ref.frame().reason())));
            rebind[10]=0;rebind[11]=0;
            reject(()->ChunkTransportEnvelopeCodec.decodeEnvelope(client,rebind));
            equal(ref,ChunkTransportEnvelopeCodec.decodeEnvelope(client,compact));
            // Both coordinate extremes and saturation: no slot is recycled or silently evicted.
            for(int i=0;i<4200;i++) {
                String uniqueHash="0".repeat(48)+java.util.HexFormat.of().toHexDigits((long)i);
                var f=envelope(frame(ChunkHotspotFrameOp.PUBLISH_REF,4,i==0?Integer.MIN_VALUE:i,i==0?Integer.MAX_VALUE:-i,uniqueHash,ref.frame().reason()));
                equal(f,ChunkTransportEnvelopeCodec.decodeEnvelope(client,ChunkTransportEnvelopeCodec.encodeEnvelope(server,f)));
            }
            equal(ref,ChunkTransportEnvelopeCodec.decodeEnvelope(client,compact));
            verifyBounds(server, client);
            check(server.attr(AttributeKey.valueOf("bandwidthoptimizer.compactChunkReferences.v1")).get()!=null,"dictionary exists before close");
            server.close();client.close();
            check(server.attr(AttributeKey.valueOf("bandwidthoptimizer.compactChunkReferences.v1")).get()==null,"dictionary released on close");
            check(client.attr(AttributeKey.valueOf("bandwidthoptimizer.compactChunkReferences.v1")).get()==null,"inbound dictionary released");
            ChunkTransportEnvelopeCodec.encodeEnvelope(server, envelope(frame(ChunkHotspotFrameOp.SERVER_CACHE_SCOPE,0,0,0,HASH,"closed")));
            check(server.attr(AttributeKey.valueOf("bandwidthoptimizer.compactChunkReferences.v1")).get()==null,"closed channel must not recreate dictionary");
            var reconnectServer = new EmbeddedChannel(); var reconnectClient = new EmbeddedChannel();
            try {
                check(Arrays.equals(legacy, ChunkTransportEnvelopeCodec.encodeEnvelope(reconnectServer,ref)),"reconnect requires fresh negotiation");
                negotiate(reconnectServer,reconnectClient);
                reject(() -> ChunkTransportEnvelopeCodec.decodeEnvelope(reconnectClient,compact));
                equal(ref,ChunkTransportEnvelopeCodec.decodeEnvelope(reconnectClient,ChunkTransportEnvelopeCodec.encodeEnvelope(reconnectServer,ref)));
            } finally { reconnectServer.finishAndReleaseAll(); reconnectClient.finishAndReleaseAll(); }
            System.out.println("Compact REF regression PASS legacy="+legacy.length+" first="+definition.length+" repeated="+compact.length);
        } finally {server.finishAndReleaseAll();client.finishAndReleaseAll();}
    }

    static void verifyBackendReplacement() {
        var first=new EmbeddedChannel();
        var next=new EmbeddedChannel(new io.netty.channel.ChannelInboundHandlerAdapter());
        var client=new EmbeddedChannel();
        try {
            negotiate(first,client);
            var a=envelope(frame(ChunkHotspotFrameOp.PUBLISH_REF,1,1,0,HASH,"reuse_persistent_client_cache_manifest"));
            equal(a,ChunkTransportEnvelopeCodec.decodeEnvelope(client,ChunkTransportEnvelopeCodec.encodeEnvelope(first,a)));
            var ackA=envelope(frame(ChunkHotspotFrameOp.ACK,1,1,0,HASH,"runtime_ref_reused_after_persistent_manifest"));
            byte[] delayedAck=ChunkTransportEnvelopeCodec.encodeEnvelope(client,ackA);
            var bloom=envelope(frame(ChunkHotspotFrameOp.CLIENT_CACHE_BLOOM,0,0,0,HASH,"bloom"));
            byte[] delayedBloom=ChunkTransportEnvelopeCodec.encodeEnvelope(client,bloom);
            acknowledge(first,client,a.frame());
            first.close(); // proxy replaces backend, not the client TCP connection
            reject(()->ChunkTransportEnvelopeCodec.decodeEnvelope(next,Arrays.copyOf(delayedAck,delayedAck.length-1)));
            reject(()->ChunkTransportEnvelopeCodec.decodeEnvelope(next,Arrays.copyOf(delayedAck,delayedAck.length+1)));
            check(ChunkTransportEnvelopeCodec.decodeEnvelope(next,delayedAck)==null,"old ACK before new negotiation is harmless");
            var consumed=com.PinkCats.bandwidthoptimizer.chunk.integration.transport.ChunkTransportDispatcher.tryDecodeInboundPacket(next.pipeline().firstContext(),delayedAck);
            check(consumed.consumeAsChunkControlFrame() && !consumed.shouldDecodeVanillaPacket(),"dispatcher consumes retired ACK without vanilla replay");
            ChunkTransportEnvelopeCodec.decodeEnvelope(next,delayedBloom);
            check(Arrays.equals(ChunkTransportEnvelopeCodec.encodeEnvelope(a),ChunkTransportEnvelopeCodec.encodeEnvelope(next,a)),"old unsolicited acceptance cannot activate replacement");
            negotiate(next,client);
            var b=envelope(frame(ChunkHotspotFrameOp.PUBLISH_REF,1,2,0,OTHER,a.frame().reason()));
            equal(b,ChunkTransportEnvelopeCodec.decodeEnvelope(client,ChunkTransportEnvelopeCodec.encodeEnvelope(next,b)));
            check(ChunkTransportEnvelopeCodec.decodeEnvelope(next,delayedAck)==null,"retired backend ACK is consumed");
            check(ChunkTransportEnvelopeCodec.encodeEnvelope(next,b)[9]==2,"old ACK cannot confirm reused ID with different identity");
            acknowledge(next,client,b.frame());
            equal(b,ChunkTransportEnvelopeCodec.decodeEnvelope(client,ChunkTransportEnvelopeCodec.encodeEnvelope(next,b)));
            negotiate(next,client); // repeated scope offer must not discard confirmed IDs
            byte[] repeated=ChunkTransportEnvelopeCodec.encodeEnvelope(next,b);
            check(repeated[9]==3,"same backend keeps confirmed ID across repeated scope");
            equal(b,ChunkTransportEnvelopeCodec.decodeEnvelope(client,repeated));
            // A delayed response to a different offer cannot negotiate a fresh sender.
            var pending=new EmbeddedChannel();
            try {
                var scope=envelope(frame(ChunkHotspotFrameOp.SERVER_CACHE_SCOPE,0,0,0,HASH,"scope"));
                ChunkTransportEnvelopeCodec.encodeEnvelope(pending,scope);
                ChunkTransportEnvelopeCodec.decodeEnvelope(pending,delayedBloom);
                check(Arrays.equals(ChunkTransportEnvelopeCodec.encodeEnvelope(a),ChunkTransportEnvelopeCodec.encodeEnvelope(pending,a)),"stale acceptance cannot match fresh offer");
            } finally {pending.finishAndReleaseAll();}
            // Old peers must stay on the legacy envelope rather than negotiate v2 by accident.
            var oldScope=envelope(frame(ChunkHotspotFrameOp.SERVER_CACHE_SCOPE,0,0,0,HASH,"scope|bo_compact_ref_1"));
            ChunkTransportEnvelopeCodec.decodeEnvelope(client,ChunkTransportEnvelopeCodec.encodeEnvelope(oldScope));
            check(Arrays.equals(ChunkTransportEnvelopeCodec.encodeEnvelope(bloom),ChunkTransportEnvelopeCodec.encodeEnvelope(client,bloom)),"old backend cannot enable v2 on retained client");
            for(int n=0;n<32;n++) {
                var replacement=new EmbeddedChannel();
                try {
                    negotiate(replacement,client);
                    var f=envelope(frame(ChunkHotspotFrameOp.PUBLISH_REF,1,n,3,HASH,a.frame().reason()));
                    equal(f,ChunkTransportEnvelopeCodec.decodeEnvelope(client,ChunkTransportEnvelopeCodec.encodeEnvelope(replacement,f)));
                    acknowledge(replacement,client,f.frame());
                    equal(f,ChunkTransportEnvelopeCodec.decodeEnvelope(client,ChunkTransportEnvelopeCodec.encodeEnvelope(replacement,f)));
                } finally {replacement.finishAndReleaseAll();}
            }
            try {
                Object state=client.attr(AttributeKey.valueOf("bandwidthoptimizer.compactChunkReferences.v1")).get();
                var inbound=state.getClass().getDeclaredField("inbound");inbound.setAccessible(true);
                check(Arrays.stream((Object[])inbound.get(state)).filter(java.util.Objects::nonNull).count()==1,"switches retain only current sender dictionary");
            } catch(ReflectiveOperationException failure) {throw new AssertionError(failure);}
            System.out.println("Backend replacement retained-client regression PASS");
        } finally {first.finishAndReleaseAll();next.finishAndReleaseAll();client.finishAndReleaseAll();}
    }
    static void verifyMixedPeers() {
        var newer = new EmbeddedChannel(); var older = new EmbeddedChannel();
        try {
            var scope = envelope(frame(ChunkHotspotFrameOp.SERVER_CACHE_SCOPE,0,0,0,HASH,"scope"));
            var bloom = envelope(frame(ChunkHotspotFrameOp.CLIENT_CACHE_BLOOM,0,0,0,HASH,"bloom"));
            var ref = envelope(frame(ChunkHotspotFrameOp.PUBLISH_REF,1,1,0,HASH,"reuse_persistent_client_cache_manifest"));
            // The no-Channel codec is the unchanged legacy wire implementation.
            var oldScope = ChunkTransportEnvelopeCodec.decodeEnvelope(ChunkTransportEnvelopeCodec.encodeEnvelope(newer,scope));
            check(oldScope.frame().operation()==scope.frame().operation(),"old client can read offered scope");
            equal(bloom,ChunkTransportEnvelopeCodec.decodeEnvelope(newer,ChunkTransportEnvelopeCodec.encodeEnvelope(bloom)));
            check(Arrays.equals(ChunkTransportEnvelopeCodec.encodeEnvelope(ref),ChunkTransportEnvelopeCodec.encodeEnvelope(newer,ref)),"new server/old client stays legacy");
            equal(scope,ChunkTransportEnvelopeCodec.decodeEnvelope(older,ChunkTransportEnvelopeCodec.encodeEnvelope(scope)));
            check(Arrays.equals(ChunkTransportEnvelopeCodec.encodeEnvelope(bloom),ChunkTransportEnvelopeCodec.encodeEnvelope(older,bloom)),"old server/new client cannot accept nonexistent offer");
            // A single altered capability field cannot activate a server that never offered it.
            var unsolicited = new EmbeddedChannel();
            try {
                var response = envelope(frame(ChunkHotspotFrameOp.CLIENT_CACHE_BLOOM,0,0,0,HASH,"bloom|bo_compact_ref_2=00000000-0000-0000-0000-000000000000"));
                ChunkTransportEnvelopeCodec.decodeEnvelope(unsolicited,ChunkTransportEnvelopeCodec.encodeEnvelope(response));
                check(Arrays.equals(ChunkTransportEnvelopeCodec.encodeEnvelope(ref),ChunkTransportEnvelopeCodec.encodeEnvelope(unsolicited,ref)),"unsolicited acceptance cannot activate encoder");
            } finally { unsolicited.finishAndReleaseAll(); }
        } finally { newer.finishAndReleaseAll(); older.finishAndReleaseAll(); }
    }

    static void verifyBounds(EmbeddedChannel sender, EmbeddedChannel receiver) {
        try {
            Object s=sender.attr(AttributeKey.valueOf("bandwidthoptimizer.compactChunkReferences.v1")).get();
            Object r=receiver.attr(AttributeKey.valueOf("bandwidthoptimizer.compactChunkReferences.v1")).get();
            var outbound=s.getClass().getDeclaredField("outbound"); outbound.setAccessible(true);
            var inbound=r.getClass().getDeclaredField("inbound"); inbound.setAccessible(true);
            var reverse=r.getClass().getDeclaredField("inboundIds"); reverse.setAccessible(true);
            check(((Map<?,?>)outbound.get(s)).size()==4096,"outbound metadata saturates at exact cap");
            check(((Object[])inbound.get(r)).length==4096 && ((Map<?,?>)reverse.get(r)).size()<=4096,"inbound metadata bounded");
            for (var field : s.getClass().getDeclaredFields()) check(field.getType()!=byte[].class,"no full payload ownership in state");
            long senderBytes=CompactReferenceMemoryAgent.retainedMetadataBytes(s);
            long receiverBytes=CompactReferenceMemoryAgent.retainedMetadataBytes(r);
            check(senderBytes<2*1024*1024 && receiverBytes<2*1024*1024,"metadata graph must remain below two MiB per saturated endpoint");
            System.out.println("compact-ref saturated metadata graph senderB="+senderBytes+" receiverB="+receiverBytes+" slots=4096");
        } catch (ReflectiveOperationException failure) { throw new AssertionError(failure); }
    }

    static void verifyStreaming(EmbeddedChannel server, EmbeddedChannel client, ChunkTransportEnvelope ref) {
        try (var sender = new ChannelTransportSession(); var receiver = new ChannelTransportSession()) {
            sender.setCrossFrameZstdEnabled(true); receiver.setCrossFrameZstdEnabled(true);
            for (int n=0;n<256;n++) {
                var source=envelope(frame(ChunkHotspotFrameOp.PUBLISH_REF,10+n/64,n%32,-n,HASH,ref.frame().reason()));
                byte[] wire=ChunkTransportEnvelopeCodec.encodeEnvelope(server,source);
                var encoded=sender.encodeBatchWithStreamingZstd(wire);
                receiver.acceptInboundStreamingFrame(encoded.epoch(),encoded.sequence());
                byte[] restored=receiver.decodeBatchWithStreamingZstd(encoded.packetResult().bytes()).bytes();
                receiver.completeInboundStreamingFrame(encoded.epoch(),encoded.sequence());
                check(Arrays.equals(wire,restored),"streaming preserves compact definition bytes and order");
                equal(source,ChunkTransportEnvelopeCodec.decodeEnvelope(client,restored));
                acknowledge(server,client,source.frame());
                // A transport codec restart must not clear confirmed chunk identities.
                if(n%64==63) { sender.restartOutboundStreamingEpoch(); receiver.resetInboundStreamingEpoch(); }
            }
            equal(ref,ChunkTransportEnvelopeCodec.decodeEnvelope(client,ChunkTransportEnvelopeCodec.encodeEnvelope(server,ref)));
        }
    }

    static void benchmark(EmbeddedChannel server,EmbeddedChannel client,ChunkTransportEnvelope ref) {
        var bean=(com.sun.management.ThreadMXBean)ManagementFactory.getThreadMXBean();
        for(int n=0;n<6000;n++) {
            ChunkTransportEnvelopeCodec.decodeEnvelope(ChunkTransportEnvelopeCodec.encodeEnvelope(ref));
            ChunkTransportEnvelopeCodec.decodeEnvelope(client,ChunkTransportEnvelopeCodec.encodeEnvelope(server,ref));
        }
        for(boolean compact:new boolean[]{false,true}) {
            long id=Thread.currentThread().getId(), a0=bean.getThreadAllocatedBytes(id), c0=bean.getCurrentThreadCpuTime(), t0=System.nanoTime();
            int iterations=0;
            do {
                if(compact) ChunkTransportEnvelopeCodec.decodeEnvelope(client,ChunkTransportEnvelopeCodec.encodeEnvelope(server,ref));
                else ChunkTransportEnvelopeCodec.decodeEnvelope(ChunkTransportEnvelopeCodec.encodeEnvelope(ref));
                iterations++;
            } while(iterations<1_000_000 && (iterations<30000 || System.nanoTime()-t0<600_000_000L));
            System.out.println("compact-ref encode+decode cost compact="+compact+" iterations="+iterations+" allocB/op="+(bean.getThreadAllocatedBytes(id)-a0)/iterations
                    +" cpuNs/op="+(bean.getCurrentThreadCpuTime()-c0)/iterations+" wallNs/op="+(System.nanoTime()-t0)/iterations);
        }
    }
    static void reject(Runnable r){try{r.run();throw new AssertionError("Expected rejection");}catch(IllegalArgumentException|IndexOutOfBoundsException expected){}}
    static void check(boolean value,String message){if(!value)throw new AssertionError(message);}
}
