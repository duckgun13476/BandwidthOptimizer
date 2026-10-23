package loadedrefreshregression;

import com.PinkCats.bandwidthoptimizer.chunk.classify.*;
import com.PinkCats.bandwidthoptimizer.chunk.classify.packet.ChunkPacketCoordinate;
import com.PinkCats.bandwidthoptimizer.chunk.integration.transport.envelope.*;
import com.PinkCats.bandwidthoptimizer.chunk.protocol.hotspot.*;
import com.PinkCats.bandwidthoptimizer.chunk.persistent.*;
import com.PinkCats.bandwidthoptimizer.chunk.patch.*;
import com.PinkCats.bandwidthoptimizer.chunk.refresh.*;
import io.netty.channel.embedded.EmbeddedChannel;
import java.nio.file.*;
import java.util.*;

public final class ChunkCoreProtocolRegressionMain {
    static void check(boolean v,String label) { if(!v)throw new AssertionError(label); }
    static ChunkHotspotFrame control(ChunkHotspotFrameOp op) {
        return new ChunkHotspotFrame(1,op,1,0,"PLAY","",ChunkHotspotKind.FULL_CHUNK,ChunkLaneKind.FULL,
                ChunkPacketCoordinate.unknown(),0,1,0,"","",0,"scope");
    }
    public static void main(String[] args) throws Exception {
        if(!UpdatedChunkCacheStats.ENABLED) {
            var channel=new EmbeddedChannel();var frame=control(ChunkHotspotFrameOp.SERVER_CACHE_SCOPE);
            check(ChunkCoreOverlayCapability.advertise(channel,frame)==frame,"disabled path unchanged");
            var plain=new ChunkTransportEnvelope(frame,new byte[0]);
            check(Arrays.equals(ChunkTransportEnvelopeCodec.encodeEnvelope(plain),ChunkTransportEnvelopeCodec.encodeEnvelope(channel,plain))==false,
                    "existing compact offer still independent of core feature");
            check(!ChunkCoreOverlayCapability.supported(channel),"disabled capability rejected");
            channel.finishAndReleaseAll();System.out.println("Core protocol disabled regression PASS");return;
        }

        var server=new EmbeddedChannel();var client=new EmbeddedChannel();var stranger=new EmbeddedChannel();
        var bloom=new ChunkTransportEnvelope(control(ChunkHotspotFrameOp.CLIENT_CACHE_BLOOM),new byte[0]);
        ChunkTransportEnvelopeCodec.decodeEnvelope(stranger,ChunkTransportEnvelopeCodec.encodeEnvelope(bloom));
        check(!ChunkCoreOverlayCapability.supported(stranger),"unsolicited Bloom rejected");
        var offer=new ChunkTransportEnvelope(control(ChunkHotspotFrameOp.SERVER_CACHE_SCOPE),new byte[0]);
        var stream=com.PinkCats.bandwidthoptimizer.channel.ChannelTransportStateManager.getOrCreateSession(server);
        stream.setCrossFrameZstdEnabled(true);
        check(ChunkCoreOverlayCapability.advertise(server,offer.frame())==offer.frame(),"warm streaming does not offer redundant core optimization");
        stream.setCrossFrameZstdEnabled(false);

        byte[] wire=ChunkTransportEnvelopeCodec.encodeEnvelope(server,offer);
        check(!ChunkCoreOverlayCapability.supported(server),"offer alone insufficient");
        var received=ChunkTransportEnvelopeCodec.decodeEnvelope(client,wire);
        check(received.frame().reason().equals("scope"),"both capability markers stripped");
        check(ChunkCoreOverlayCapability.supported(client),"client receives offer");
        received=ChunkTransportEnvelopeCodec.decodeEnvelope(server,ChunkTransportEnvelopeCodec.encodeEnvelope(client,bloom));
        check(received.frame().reason().equals("scope") && ChunkCoreOverlayCapability.supported(server),"ordered acceptance");
        String hash="a".repeat(64),coreHash="b".repeat(64);
        var entry=new ChunkPersistentClientCache.ManifestEntry(ChunkPacketCoordinate.ofChunk(12,28),hash,"PLAY","full",1,80000,1,coreHash);
        var legacy=ChunkPersistentClientCacheManifestBatchCodec.decode(ChunkPersistentClientCacheManifestBatchCodec.encode(List.of(entry),"test"),"test").get(0);
        check(legacy.baseSnapshotHash().equals(hash),"legacy manifest byte semantics");
        var modern=ChunkPersistentClientCacheManifestBatchCodec.decode(ChunkPersistentClientCacheManifestBatchCodec.encode(List.of(entry),"test",true),"test").get(0);
        check(modern.baseSnapshotHash().equals(coreHash) && modern.payloadHash().equals(hash),"core identity and baseline distinct");
        Path root=args.length==0?null:Path.of(args[0]);
        for(String run:root==null?List.<String>of():List.of("refresh-v7","refresh-v8")) {
            Path dir=root.resolve(".tmp/"+run+"/factory-on");
            byte[] base=Files.readAllBytes(dir.resolve("captured-candidate.bin")),target=Files.readAllBytes(dir.resolve("native-full-1.bin"));
            var a=ChunkCoreWireLayout.inspect(base);var b=ChunkCoreWireLayout.inspect(target);
            check(a!=null && b!=null,"actual native layout");
            var patch=new ChunkPatch(ChunkPatchMode.CORE_OVERLAY,"full_chunk",sha(base),target.length,ChunkCoreOverlayCodec.encode(target,b,a.hash()));
            check(Arrays.equals(target,ChunkPatchApplier.applyPatch(ChunkPatch.decode(patch.encode()),base,sha(target))),"native exact PATCH roundtrip");
            byte[] initial=Files.readAllBytes(dir.resolve("native-full-0.bin"));
            byte[] fullWire=ChunkTransportEnvelopeCodec.encodeEnvelope(new ChunkTransportEnvelope(dataFrame(ChunkHotspotFrameOp.PUBLISH_FULL,sha(target),sha(target),target.length),target));
            byte[] patchWire=ChunkTransportEnvelopeCodec.encodeEnvelope(new ChunkTransportEnvelope(dataFrame(ChunkHotspotFrameOp.PUBLISH_PATCH,sha(base),sha(target),target.length),patch.encode()));
            byte[] initialWire=ChunkTransportEnvelopeCodec.encodeEnvelope(new ChunkTransportEnvelope(dataFrame(ChunkHotspotFrameOp.PUBLISH_FULL,sha(initial),sha(initial),initial.length),initial));
            var wireCosts=new ArrayList<Map<String,Object>>();
            for(boolean reset:new boolean[]{false,true}) {
                int normal=carrier(initialWire,fullWire,reset),optimized=carrier(initialWire,patchWire,reset);
                wireCosts.add(Map.of("resetBeforeReturn",reset,"fullCarrierBytes",normal,"patchCarrierBytes",optimized,"downlinkSavedBytes",normal-optimized,"additionalManifestRawBytes",34));
            }
            int independentFull=independentCarrier(initialWire,fullWire),independentPatch=independentCarrier(initialWire,patchWire);
            check(independentFull-independentPatch>34,"independent compression saves more than sparse advertisement on native fixture");
            wireCosts.add(Map.of("independentCompression",true,"fullCarrierBytes",independentFull,"patchCarrierBytes",independentPatch,"downlinkSavedBytes",independentFull-independentPatch,"additionalManifestRawBytes",34));
            System.out.println("Core production-envelope matched carrier "+run+" "+wireCosts);
            byte[] stale=base.clone();stale[2]^=1;
            try { ChunkPatchApplier.applyPatch(patch,stale,sha(target));throw new AssertionError("stale accepted"); }catch(IllegalStateException expected) {}
        }
        ChunkCoreClaims.put(server.id().asLongText(),1,12,28,hash,coreHash);
        server.finishAndReleaseAll();client.finishAndReleaseAll();stranger.finishAndReleaseAll();
        check(ChunkCoreClaims.size()==0 && !ChunkCoreOverlayCapability.supported(server),"close releases metadata and capability");
        byte[] v1=ChunkPersistentClientCacheManifestBatchCodec.encode(List.of(entry),"test");
        byte[] v2=ChunkPersistentClientCacheManifestBatchCodec.encode(List.of(entry),"test",true);
        check(v2.length-v1.length==34,"sparse binary claim overhead exactly34 bytes");
        var noClaim=new ChunkPersistentClientCache.ManifestEntry(entry.coordinate(),hash,"PLAY","full",1,80000,1);
        check(ChunkPersistentClientCacheManifestBatchCodec.encode(List.of(noClaim),"test",true).length
                -ChunkPersistentClientCacheManifestBatchCodec.encode(List.of(noClaim),"test").length==1,"no candidate only batch count overhead");
        for(int i=0;i<100000;i++) {
            String peer="peer"+(i%16);String unique=String.format("%064x",i);
            ChunkCoreClaims.put(peer,1,i,0,unique,sha(unique.getBytes(java.nio.charset.StandardCharsets.UTF_8)));
        }
        check(ChunkCoreClaims.size()==8192,"global index remains bounded under churn");
        var roots=new ArrayList<Object>();
        for(String field:List.of("CLAIMS","PEERS")) { var f=ChunkCoreClaims.class.getDeclaredField(field);f.setAccessible(true);roots.add(f.get(null)); }
        var measure=Class.forName("compactrefregression.CompactReferenceMemoryAgent").getDeclaredMethod("retainedMetadataBytes",Object.class);measure.setAccessible(true);
        long metadata=(long)measure.invoke(null,(Object)roots.toArray());
        check(metadata<8*1024*1024,"global metadata graph under8MiB and contains no packet bodies");
        for(int i=0;i<16;i++)ChunkCoreClaims.clear("peer"+i);
        check(ChunkCoreClaims.size()==0,"all closed peers release global entries");
        long cleared=(long)measure.invoke(null,(Object)roots.toArray());
        for(int i=0;i<2000;i++)ChunkCoreClaims.put("channel"+i,1,0,0,hash,coreHash);
        check(ChunkCoreClaims.size()==1024,"peer fanout bound");
        long fanout=(long)measure.invoke(null,(Object)roots.toArray());
        check(fanout<8*1024*1024,"peer fanout metadata budget");
        for(int i=0;i<2000;i++)ChunkCoreClaims.clear("channel"+i);
        check(ChunkCoreClaims.size()==0,"fanout cleanup");
        System.out.println("Core peer fanout metadataGraphBytes="+fanout);

        System.out.println("Core protocol regression PASS nativeReceiptPairs="+(root==null?0:2)+" metadataGraphBytes="+metadata+" clearedCapacityBytes="+cleared);

    }
    static ChunkHotspotFrame dataFrame(ChunkHotspotFrameOp op,String base,String target,int size) {
        return new ChunkHotspotFrame(1,op,1,2,"PLAY","net.minecraft.network.protocol.game.ClientboundLevelChunkWithLightPacket",ChunkHotspotKind.FULL_CHUNK,ChunkLaneKind.FULL,
                ChunkPacketCoordinate.ofChunk(12,28),size,2,0,base,target,0,op==ChunkHotspotFrameOp.PUBLISH_PATCH?ChunkLocalCacheReuseStats.PERSISTENT_MANIFEST_PATCH_REASON:"publish_full");
    }
    static int independentCarrier(byte[] initial,byte[] target) {
        try(var tx=new com.PinkCats.bandwidthoptimizer.channel.ChannelTransportSession();var rx=new com.PinkCats.bandwidthoptimizer.channel.ChannelTransportSession()) {
            int size=0;
            for(byte[] bytes:List.of(initial,target)) {
                byte[] encoded=tx.encodeSinglePacket(bytes);
                check(Arrays.equals(bytes,rx.decodeSinglePacket(encoded)),"matched independent envelope carrier exact");size=encoded.length;
            }
            return size;
        }
    }
    static int carrier(byte[] initial,byte[] target,boolean reset) {
        try(var tx=new com.PinkCats.bandwidthoptimizer.channel.ChannelTransportSession();var rx=new com.PinkCats.bandwidthoptimizer.channel.ChannelTransportSession()) {
            tx.setCrossFrameZstdEnabled(true);rx.setCrossFrameZstdEnabled(true);
            int size=0;
            for(byte[] bytes:List.of(initial,target)) {
                if(reset && bytes==target){tx.restartOutboundStreamingEpoch();rx.resetInboundStreamingEpoch();}
                var encoded=tx.encodeBatchWithStreamingZstd(bytes);
                rx.acceptInboundStreamingFrame(encoded.epoch(),encoded.sequence());
                byte[] decoded=rx.decodeBatchWithStreamingZstd(encoded.packetResult().bytes()).bytes();
                rx.completeInboundStreamingFrame(encoded.epoch(),encoded.sequence());
                check(Arrays.equals(bytes,decoded),"matched production envelope carrier exact");size=encoded.packetResult().bytes().length;
            }
            return size;
        }
    }
    static String sha(byte[] b) throws Exception { return HexFormat.of().formatHex(java.security.MessageDigest.getInstance("SHA-256").digest(b)); }
}
