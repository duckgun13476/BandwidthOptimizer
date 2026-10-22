package com.PinkCats.bandwidthoptimizer.chunk.refresh;

import java.io.ByteArrayOutputStream;
import java.io.DataOutputStream;
import java.util.Arrays;

/** Native-independent bounds and metadata ownership regression. */
public final class ChunkCoreMetadataRegressionMain {
    private static void check(boolean value,String label) { if(!value)throw new AssertionError(label); }
    private static byte[] full(boolean named, boolean trust, int value) throws Exception {
        var bytes=new ByteArrayOutputStream();var out=new DataOutputStream(bytes);
        out.writeByte(39);out.writeInt(12);out.writeInt(28);
        out.writeByte(10);if(named)out.writeShort(0);out.writeByte(0);
        out.writeByte(4);out.writeInt(1234);
        out.writeByte(1);out.writeByte(0);out.writeShort(70);out.writeByte(1);
        out.writeByte(10);if(named)out.writeShort(0);
        out.writeByte(3);out.writeUTF("value");out.writeInt(value);out.writeByte(0);
        if(trust)out.writeBoolean(true);
        for(int i=0;i<6;i++)out.writeByte(0);
        return bytes.toByteArray();
    }
    public static void main(String[] args) throws Exception {
        for(boolean named:new boolean[]{false,true})for(boolean trust:new boolean[]{false,true}) {
            byte[] base=full(named,trust,1),target=full(named,trust,2);
            var a=ChunkCoreWireLayout.inspect(base);var b=ChunkCoreWireLayout.inspect(target);
            check(a!=null && b!=null,"supported named/unnamed and legacy light layout");
            byte[] frame=ChunkCoreOverlayCodec.encode(target,b,a.hash());
            check(frame!=null && Arrays.equals(target,ChunkCoreOverlayCodec.restore(frame,base,a)),"exact wire restoration");
            for(int n=0;n<base.length;n++)check(ChunkCoreWireLayout.inspect(Arrays.copyOf(base,n))==null,"truncation rejected");
            check(ChunkCoreWireLayout.inspect(Arrays.copyOf(base,base.length+2))==null,"extra suffix rejected");
            base[1]^=1;check(ChunkCoreOverlayCodec.encode(base,ChunkCoreWireLayout.inspect(base),b.hash())==null,"coordinate identity");
        }
        byte[] legacy=full(false,false,1);
        var modernBytes=new ByteArrayOutputStream();var modern=new DataOutputStream(modernBytes);
        modern.write(legacy,0,9);modern.writeByte(1);modern.writeByte(4);modern.writeByte(1);modern.writeLong(42);
        modern.write(legacy,11,legacy.length-11);
        byte[] mapFull=modernBytes.toByteArray();
        check(ChunkCoreWireLayout.inspect(mapFull)!=null,"26.x map heightmaps");
        for(int n=0;n<mapFull.length;n++)check(ChunkCoreWireLayout.inspect(Arrays.copyOf(mapFull,n))==null,"map truncation rejected");
        check(ChunkCoreWireLayout.inspect(new byte[ChunkCoreOverlayCodec.MAX_FULL_BYTES+1])==null,"packet bound");
        String hash="a".repeat(64),core="b".repeat(64);
        for(int i=0;i<2000;i++)ChunkCoreClaims.put("peer",1,i,0,hash,core);
        check(ChunkCoreClaims.size()==1024,"per peer bound");
        check(ChunkCoreClaims.get("peer",1,0,0,hash)==null,"oldest evicted");
        check(ChunkCoreClaims.get("peer",2,1999,0,hash)==null,"scope isolated");
        check(ChunkCoreClaims.get("peer",1,1999,0,hash)!=null,"current baseline found");
        check(ChunkCoreClaims.get("peer",1,1999,0,core)==null,"changed baseline invalidated");
        for(int p=0;p<12;p++)for(int i=0;i<1000;i++)ChunkCoreClaims.put("p"+p,1,i,0,hash,core);
        check(ChunkCoreClaims.size()==8192,"global bound");
        ChunkCoreClaims.clear("peer");for(int p=0;p<12;p++)ChunkCoreClaims.clear("p"+p);
        check(ChunkCoreClaims.size()==0,"channel close cleanup");
        System.out.println("Core metadata regression PASS");
    }
}
