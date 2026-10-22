package loadedrefreshregression;

import com.google.gson.GsonBuilder;
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
    private static final int LIMIT=1024*1024, MAGIC=0x424f4245;
    private static java.lang.reflect.Method compressMethod,decompressMethod;
    private static byte[] compress(byte[] b,int level){try{return (byte[])compressMethod.invoke(null,b,level);}catch(Exception e){throw new IllegalStateException(e);}}
    private static byte[] decompress(byte[] b,int size){try{return (byte[])decompressMethod.invoke(null,b,size);}catch(Exception e){throw new IllegalStateException(e);}}
    private record Parts(int start,int end,byte[] coreHash) {}
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
            var md=MessageDigest.getInstance("SHA-256");
            md.update(ByteBuffer.allocate(8).putInt(start).putInt(b.length-end).array());
            md.update(b,0,start);md.update(b,end,b.length-end);
            return new Parts(start,end,md.digest());
        } catch(java.security.NoSuchAlgorithmException|java.io.IOException e){throw new IllegalStateException(e);}
        finally{in.release();}
    }
    // Server only has the original native outgoing FULL + advertised core key.
    private static byte[] encode(byte[] target,byte[] advertisedCore) {
        Parts p=split(target);
        if(!Arrays.equals(p.coreHash,advertisedCore))return null;
        int beBytes=p.end-p.start;
        int size=69+varSize(target.length)+varSize(beBytes)+beBytes;
        byte[] result=new byte[size];
        var out=new FriendlyByteBuf(Unpooled.wrappedBuffer(result));out.clear();
        try{
            out.writeInt(MAGIC);out.writeByte(1);out.writeBytes(p.coreHash);out.writeBytes(digest(target));
            out.writeVarInt(target.length);out.writeVarInt(p.end-p.start);out.writeBytes(target,p.start,p.end-p.start);
            check(out.writerIndex()==size,"exact frame allocation");return result;
        }finally{out.release();}
    }
    private static int varSize(int n){int count=1;while((n >>>= 7)!=0)count++;return count;}
    // Client index stores offsets/key beside an existing owned cache body.
    private static byte[] restore(byte[] frame,byte[] base,Parts cached) {
        if(frame==null||frame.length>LIMIT+128)throw new IllegalArgumentException("frame bound");
        var in=new FriendlyByteBuf(Unpooled.wrappedBuffer(frame).asReadOnly());
        try{
            if(in.readInt()!=MAGIC||in.readUnsignedByte()!=1)throw new IllegalArgumentException("format");
            byte[] core=new byte[32],targetHash=new byte[32];in.readBytes(core);in.readBytes(targetHash);
            if(!Arrays.equals(core,cached.coreHash))throw new IllegalArgumentException("core mismatch");
            int len=in.readVarInt(),n=in.readVarInt();
            if(len<1||len>LIMIT||n<1||n!=in.readableBytes()||len!=(long)cached.start+n+base.length-cached.end)
                throw new IllegalArgumentException("length");
            byte[] result=new byte[len];System.arraycopy(base,0,result,0,cached.start);
            in.readBytes(result,cached.start,n);System.arraycopy(base,cached.end,result,cached.start+n,base.length-cached.end);
            if(!Arrays.equals(digest(result),targetHash))throw new IllegalArgumentException("target hash");
            return result;
        }finally{in.release();}
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
            byte[] before=digest(base);Parts cached=split(base);byte[] frame=encode(target,cached.coreHash);
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
            byte[] hash=digest(ByteBuffer.allocate(4).putInt(i).array());
            metadata.put(new CoreKey(hash),new Parts(10,20,hash));
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
}
