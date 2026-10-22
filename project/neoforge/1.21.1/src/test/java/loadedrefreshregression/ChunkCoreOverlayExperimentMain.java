package loadedrefreshregression;

import com.google.gson.GsonBuilder;
import com.PinkCats.bandwidthoptimizer.chunk.refresh.ChunkCoreOverlayCodec;
import io.netty.buffer.Unpooled;
import net.minecraft.network.FriendlyByteBuf;
import java.nio.ByteBuffer;
import java.nio.file.*;
import java.security.MessageDigest;
import java.lang.management.ManagementFactory;
import java.util.*;
import java.util.function.Supplier;

/** Offline protocol experiment on actual native packet receipts. Not registered on the network. */
public final class ChunkCoreOverlayExperimentMain {
    private static final int LIMIT=1024*1024;
    private static java.lang.reflect.Method compressMethod,decompressMethod;
    private static byte[] compress(byte[] b,int level){try{return (byte[])compressMethod.invoke(null,b,level);}catch(Exception e){throw new IllegalStateException(e);}}
    private static byte[] decompress(byte[] b,int size){try{return (byte[])decompressMethod.invoke(null,b,size);}catch(Exception e){throw new IllegalStateException(e);}}
    private record Parts(int start,int end,byte[] coreHash,ChunkCoreOverlayCodec.Core core) {}
    private record CoreKey(byte[] hash) {
        @Override public int hashCode(){return Arrays.hashCode(hash);}
        @Override public boolean equals(Object value){return value instanceof CoreKey key && Arrays.equals(hash,key.hash);}
    }
    private static byte[] digest(byte[] b) {
        try { return MessageDigest.getInstance("SHA-256").digest(b); }
        catch(Exception e){throw new IllegalStateException(e);}
    }
    private static void skipNbt(FriendlyByteBuf in,java.io.DataInput input,net.minecraft.nbt.NbtAccounter quota) throws java.io.IOException {
        int type=in.readUnsignedByte();
        if(type>12)throw new IllegalArgumentException("NBT type");
        net.minecraft.nbt.TagTypes.getType(type).skip(input,quota);
    }
    private static Parts split(byte[] b) {
        if(b.length==0||b.length>LIMIT)throw new IllegalArgumentException("body bound");
        var in=new FriendlyByteBuf(Unpooled.wrappedBuffer(b).asReadOnly());
        try {
            var input=new io.netty.buffer.ByteBufInputStream(in);
            var quota=net.minecraft.nbt.NbtAccounter.create(16L*1024*1024);
            in.readVarInt();in.readInt();in.readInt();skipNbt(in,input,quota);
            int n=in.readVarInt();if(n<0||n>in.readableBytes())throw new IllegalArgumentException("sections");
            in.skipBytes(n);int start=in.readerIndex(),count=in.readVarInt();
            if(count<0||count>4096)throw new IllegalArgumentException("BE count");
            for(int i=0;i<count;i++){in.readByte();in.readShort();in.readVarInt();skipNbt(in,input,quota);}
            int end=in.readerIndex();
            var core=ChunkCoreOverlayCodec.inspect(b,start,end);
            return new Parts(start,end,core.hash(),core);
        } catch(java.io.IOException e){throw new IllegalStateException(e);}
        finally{in.release();}
    }
    // Server only has the original native outgoing FULL + advertised core key.
    private static byte[] encode(byte[] target,byte[] advertisedCore) {
        Parts p=split(target);
        return ChunkCoreOverlayCodec.encode(target,p.core,advertisedCore);
    }
    // Client index stores offsets/key beside an existing owned cache body.
    private static byte[] restore(byte[] frame,byte[] base,Parts cached) {
        return ChunkCoreOverlayCodec.restore(frame,base,cached.core);
    }
    private static void check(boolean v,String text){if(!v)throw new AssertionError(text);}
    private static void rejected(Runnable action){try{action.run();}catch(IllegalArgumentException|IndexOutOfBoundsException expected){return;}throw new AssertionError("accepted invalid input");}
    private static Map<String,Object> cost(Supplier<byte[]> op) {
        var bean=(com.sun.management.ThreadMXBean)ManagementFactory.getThreadMXBean();
        long warm=System.nanoTime();int warmed=0;
        do{op.get();warmed++;}while(warmed<100000 && System.nanoTime()-warm<600000000L);
        long id=Thread.currentThread().getId(),alloc=bean.getThreadAllocatedBytes(id),cpu=bean.getCurrentThreadCpuTime(),t=System.nanoTime(),count=0,sum=0;
        do{sum+=op.get().length;count++;}while(count<100000 && System.nanoTime()-t<600000000L);
        return Map.of("warmIterations",warmed,"iterations",count,"allocatedBytesPerOp",(bean.getThreadAllocatedBytes(id)-alloc)/(double)count,
                "cpuNsPerOp",(bean.getCurrentThreadCpuTime()-cpu)/(double)count,"wallNsPerOp",(System.nanoTime()-t)/(double)count,"bytesPerOp",sum/(double)count);
    }
    private static Map<String,Object> carrier(byte[] initial,byte[] target,byte[] overlay,byte[] base,Parts cached,boolean useOverlay,int gap,boolean reset) {
        try(var tx=new com.PinkCats.bandwidthoptimizer.channel.ChannelTransportSession();
            var rx=new com.PinkCats.bandwidthoptimizer.channel.ChannelTransportSession()) {
            tx.setCrossFrameZstdEnabled(true);rx.setCrossFrameZstdEnabled(true);
            var packets=new ArrayList<byte[]>();packets.add(initial);
            if(gap>0){byte[] filler=new byte[gap];new Random(27012026).nextBytes(filler);packets.add(filler);}
            packets.add(useOverlay?overlay:target);
            long total=0;int last=0;
            for(int i=0;i<packets.size();i++) {
                if(reset && i==packets.size()-1){tx.restartOutboundStreamingEpoch();rx.resetInboundStreamingEpoch();}
                byte[] source=packets.get(i);var encoded=tx.encodeBatchWithStreamingZstd(source);
                rx.acceptInboundStreamingFrame(encoded.epoch(),encoded.sequence());
                byte[] decoded=rx.decodeBatchWithStreamingZstd(encoded.packetResult().bytes()).bytes();
                rx.completeInboundStreamingFrame(encoded.epoch(),encoded.sequence());
                check(Arrays.equals(source,decoded),"production carrier roundtrip");
                if(i==packets.size()-1 && useOverlay)check(Arrays.equals(target,restore(decoded,base,cached)),"carrier overlay exact FULL");
                last=encoded.packetResult().bytes().length;total+=last;
            }
            return Map.of("totalCarrierBytes",total,"returnCarrierBytes",last,"fillerBytes",gap,"resetBeforeReturn",reset);
        }
    }
    public static void main(String[] args)throws Exception{
        codecBoundsRegression();
        Path root=Path.of(args[0]).toAbsolutePath(),output=Path.of(args[1]).toAbsolutePath();
        Path jar=root.resolve(".tmp/refresh-v8/factory-on/client/bandwidthoptimizer-native/drive/embedded-libs/zstd-jni-1.5.7-7.jar");
        check(HexFormat.of().formatHex(digest(Files.readAllBytes(jar))).equalsIgnoreCase("D4725BCE2C3E4F3DF54CBEA8BF5966E240872BB7BA7658572EDEDAEF28A01D83"),"trusted existing zstd jar");
        var loader=new java.net.URLClassLoader(new java.net.URL[]{jar.toUri().toURL()},ClassLoader.getPlatformClassLoader());
        var zstd=Class.forName("com.github.luben.zstd.Zstd",true,loader);
        compressMethod=zstd.getMethod("compress",byte[].class,int.class);decompressMethod=zstd.getMethod("decompress",byte[].class,int.class);
        var reports=new ArrayList<Map<String,Object>>();
        for(String run:List.of("refresh-v7","refresh-v8")){
            Path dir=root.resolve(".tmp/"+run+"/factory-on");
            byte[] base=Files.readAllBytes(dir.resolve("captured-candidate.bin")),target=Files.readAllBytes(dir.resolve("native-full-1.bin")),initial=Files.readAllBytes(dir.resolve("native-full-0.bin"));
            byte[] before=digest(base);Parts cached=split(base);var wireCore=com.PinkCats.bandwidthoptimizer.chunk.refresh.ChunkCoreWireLayout.inspect(base);
            check(wireCore!=null && Arrays.equals(wireCore.hash(),cached.coreHash),"bounded wire parser agrees with native NBT reader");
            var targetCore=com.PinkCats.bandwidthoptimizer.chunk.refresh.ChunkCoreWireLayout.inspect(target);
            check(targetCore!=null && Arrays.equals(targetCore.hash(),split(target).coreHash),"target native boundaries");
            byte[] frame=encode(target,cached.coreHash);
            check(frame!=null,"actual core match");check(Arrays.equals(target,restore(frame,base,cached)),"byte exact native FULL");
            check(encode(target,split(initial).coreHash)==null,"stale terrain must use FULL");
            byte[] changed=base.clone();changed[4]^=1;
            rejected(()->restore(frame,changed,cached)); // whole target hash still protects a stale/corrupt index body
            byte[] wrongKey=frame.clone();wrongKey[5]^=1;rejected(()->restore(wrongKey,base,cached));
            byte[] wrongTarget=frame.clone();wrongTarget[37]^=1;rejected(()->restore(wrongTarget,base,cached));
            rejected(()->restore(Arrays.copyOf(frame,frame.length-1),base,cached));
            check(Arrays.equals(before,digest(base)),"original bytes unchanged");
            var compression=new ArrayList<Map<String,Object>>();
            for(int level:List.of(1,3,9)) {
                byte[] fullCompressed=compress(target,level),overlayCompressed=compress(frame,level);
                check(Arrays.equals(frame,decompress(overlayCompressed,frame.length)),"zstd roundtrip");
                compression.add(Map.of("level",level,"nativeFullCompressedBytes",fullCompressed.length,"overlayCompressedBytes",overlayCompressed.length,
                        "savedCompressedBytes",fullCompressed.length-overlayCompressed.length));
            }
            var row=new LinkedHashMap<String,Object>();row.put("run",run);row.put("fullBytes",target.length);row.put("overlayBytes",frame.length);
            row.put("coreBytesReused",cached.start+base.length-cached.end);row.put("authoritativeBEBytes",split(target).end-split(target).start);
            row.put("compression",compression);row.put("serverParseHashEncode",cost(()->encode(target,cached.coreHash)));
            row.put("clientRestoreHash",cost(()->restore(frame,base,cached)));
            row.put("fullCompressLevel3",cost(()->compress(target,3)));row.put("overlayCompressLevel3",cost(()->compress(frame,3)));
            row.put("clientIndexEntry",Map.of("additionalCoreKeyBytes",32,"offsetInts",2,"ownsAdditionalPacketBody",false));
            var carriers=new ArrayList<Map<String,Object>>();
            for(int gap:List.of(0,65536,1048576))carriers.add(Map.of("baseline",carrier(initial,target,frame,base,cached,false,gap,false),"overlay",carrier(initial,target,frame,base,cached,true,gap,false)));
            carriers.add(Map.of("baseline",carrier(initial,target,frame,base,cached,false,0,true),"overlay",carrier(initial,target,frame,base,cached,true,0,true)));
            row.put("productionCarrierMatchedPairs",carriers);
            row.put("correctness","PASS exact native bytes, stale core FULL, wrong key/body/hash/truncation rejected, immutable baseline");reports.add(row);
        }
        var metadata=new LinkedHashMap<CoreKey,Parts>();
        for(int i=0;i<100000;i++) {
            byte[] input=ByteBuffer.allocate(30).putInt(i).array();
            var core=ChunkCoreOverlayCodec.inspect(input,10,20);
            byte[] hash=core.hash();
            metadata.put(new CoreKey(hash),new Parts(10,20,hash,core));
            if(metadata.size()>4096)metadata.remove(metadata.keySet().iterator().next());
        }
        var measure=Class.forName("compactrefregression.CompactReferenceMemoryAgent").getDeclaredMethod("retainedMetadataBytes",Object.class);measure.setAccessible(true);
        long saturated=(long)measure.invoke(null,metadata);check(saturated<2*1024*1024,"bounded metadata");
        metadata.clear();long cleared=(long)measure.invoke(null,metadata);
        Files.writeString(output,new GsonBuilder().setPrettyPrinting().create().toJson(Map.of("scope","offline actual native receipts; independent zstd + matched existing streaming codec; not native network/NIC acceptance","reports",reports,
                "clientIndexExperiment",Map.of("capacity",4096,"saturatedMetadataGraphBytes",saturated,"clearedCapacityBytes",cleared,"ownsPacketBodies",false))));
        System.out.println("Core overlay experiment PASS "+output);
        System.out.println(new GsonBuilder().create().toJson(reports));
    }
    private static void codecBoundsRegression() {
        byte[] base={1,2,3,4,5},target={1,8,9,7,5};
        var core=ChunkCoreOverlayCodec.inspect(base,1,4);
        byte[] frame=ChunkCoreOverlayCodec.encode(target,ChunkCoreOverlayCodec.inspect(target,1,4),core.hash());
        check(Arrays.equals(target,ChunkCoreOverlayCodec.restore(frame,base,core)),"shared codec exact bytes");
        byte[] exposed=core.hash();exposed[0]^=1;
        check(Arrays.equals(target,ChunkCoreOverlayCodec.restore(frame,base,core)),"core identity immutable");
        byte[] stale=base.clone();stale[0]^=1;
        rejected(()->ChunkCoreOverlayCodec.restore(frame,stale,core));
        rejected(()->ChunkCoreOverlayCodec.inspect(base,0,4));
        rejected(()->ChunkCoreOverlayCodec.inspect(base,3,2));
        rejected(()->ChunkCoreOverlayCodec.inspect(base,1,6));
        rejected(()->ChunkCoreOverlayCodec.inspect(new byte[LIMIT+1],1,2));
        for(int size=0;size<frame.length;size++) {
            byte[] shortFrame=Arrays.copyOf(frame,size);
            rejected(()->ChunkCoreOverlayCodec.restore(shortFrame,base,core));
        }
        byte[] extra=Arrays.copyOf(frame,frame.length+1);
        rejected(()->ChunkCoreOverlayCodec.restore(extra,base,core));
        byte[] badSize=frame.clone();badSize[69]=0;
        rejected(()->ChunkCoreOverlayCodec.restore(badSize,base,core));
        byte[] badVersion=frame.clone();badVersion[4]=2;
        rejected(()->ChunkCoreOverlayCodec.restore(badVersion,base,core));
        byte[] badMagic=frame.clone();badMagic[0]^=1;
        rejected(()->ChunkCoreOverlayCodec.restore(badMagic,base,core));
        check(ChunkCoreOverlayCodec.encode(target,ChunkCoreOverlayCodec.inspect(target,1,4),exposed)==null,"stale advertisement FULL fallback");
        check(Arrays.equals(base,new byte[]{1,2,3,4,5}),"baseline ownership unchanged");
    }
}
