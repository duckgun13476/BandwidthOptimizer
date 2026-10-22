package com.PinkCats.bandwidthoptimizer.chunk.refresh;

/** Bounded NBT skipper without native NBT trees. Unknown or ambiguous packet layouts
 * are ineligible; the caller keeps the original FULL transport. */
public final class ChunkCoreWireLayout {
    private ChunkCoreWireLayout() {}

    public static ChunkCoreOverlayCodec.Core inspect(byte[] bytes) {
        if (bytes == null || bytes.length == 0 || bytes.length > ChunkCoreOverlayCodec.MAX_FULL_BYTES) return null;
        int[] result = null;
        for (int layout=0;layout<3;layout++) {
            try {
                Cursor c = new Cursor(bytes, layout==1);
                c.var(); c.skip(8);
                if(layout==2) {
                    // 26.x: ByteBufCodecs.map(Heightmap.Types.STREAM_CODEC,
                    // LONG_ARRAY), verified against the native codec.
                    int maps=c.var(), seen=0;
                    if(maps<0 || maps>6)continue;
                    for(int i=0;i<maps;i++) {
                        int type=c.var();if(type<0 || type>=6 || (seen&(1<<type))!=0)throw new IllegalArgumentException();
                        seen|=1<<type;c.longs();
                    }
                } else c.nbt();
                c.skip(c.var());
                int start = c.p, count = c.var();
                if (count < 0 || count > 4096) continue;
                for (int i = 0; i < count; i++) { c.skip(3); c.var(); c.nbt(); }
                int end = c.p;
                // Light masks and payloads must consume the complete suffix.
                // 1.19 also has a trustEdges boolean before these masks.
                if (!c.light(false) && !c.light(true)) continue;
                if (result != null && (result[0] != start || result[1] != end)) return null;
                result = new int[]{start, end};
            } catch (IllegalArgumentException ignored) { /* unsupported wire layout */ }
        }
        return result == null ? null : ChunkCoreOverlayCodec.inspect(bytes, result[0], result[1]);
    }

    private static final class Cursor {
        final byte[] b; final boolean named; int p, work;
        Cursor(byte[] b, boolean named) { this.b=b; this.named=named; this.work=b.length*2; }
        void skip(int n) { if(n<0 || n>b.length-p)throw new IllegalArgumentException(); p+=n; }
        int u8() { if(p>=b.length)throw new IllegalArgumentException(); return b[p++]&255; }
        int u16() { return (u8()<<8)|u8(); }
        int i32() { return (u16()<<16)|u16(); }
        int var() {
            int v=0;
            for(int i=0;i<5;i++){int x=u8();if(i==4 && (x&240)!=0)throw new IllegalArgumentException();v|=(x&127)<<(7*i);if((x&128)==0)return v;}
            throw new IllegalArgumentException();
        }
        void str() { skip(u16()); }
        void nbt() { int t=u8(); if(t==0)return; if(named)str(); tag(t,0); }
        void tag(int t,int depth) {
            if(depth>64 || --work<0)throw new IllegalArgumentException();
            switch(t) {
                case 1 -> skip(1); case 2 -> skip(2); case 3,5 -> skip(4); case 4,6 -> skip(8);
                case 7 -> array(1); case 8 -> str();
                case 9 -> { int child=u8(), n=i32(); if(n<0 || n>work || child<0 || child>12 || (child==0 && n!=0))throw new IllegalArgumentException(); for(int i=0;i<n;i++)tag(child,depth+1); }
                case 10 -> { for(;;){int child=u8();if(child==0)break;str();tag(child,depth+1);} }
                case 11 -> array(4); case 12 -> array(8);
                default -> throw new IllegalArgumentException();
            }
        }
        void array(int width) { int n=i32(); if(n<0 || n>(b.length-p)/width)throw new IllegalArgumentException();skip(n*width); }
        void longs() { int n=var(); if(n<0 || n>(b.length-p)/8)throw new IllegalArgumentException();skip(n*8); }
        boolean light(boolean trust) {
            int saved=p;
            try {
                if(trust && u8()>1)throw new IllegalArgumentException();
                for(int i=0;i<4;i++)longs();
                for(int k=0;k<2;k++) { int n=var(); if(n<0 || n>4096)throw new IllegalArgumentException();for(int i=0;i<n;i++){int size=var();if(size!=2048)throw new IllegalArgumentException();skip(size);} }
                return p==b.length;
            } catch(IllegalArgumentException ignored) { return false; }
            finally { p=saved; }
        }
    }
}
