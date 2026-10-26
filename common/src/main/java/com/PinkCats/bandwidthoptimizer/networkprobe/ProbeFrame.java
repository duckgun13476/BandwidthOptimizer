package com.PinkCats.bandwidthoptimizer.networkprobe;

import java.nio.ByteBuffer;
import java.util.UUID;

/** Independent, bounded PLAY payload. Never interpreted as a BO transport frame. */
public record ProbeFrame(int kind, UUID id, int sequence, long value, byte[] body) {
    public static final int OFFER=0, READY=1, PING=2, PONG=3, DATA=4, END=5,
            RECEIPT=6, CANCEL=7, COMPLETE=8, UPLOAD=9, DOWNLOAD=10, RESULT=11, REQUEST=12, STATUS=13;
    public static final int HEADER=34, BLOCK=8192, MAX_BYTES=HEADER+BLOCK;
    public ProbeFrame {
        if (kind<0 || kind>STATUS || id==null || sequence<0 || body==null || body.length>BLOCK)
            throw new IllegalArgumentException("Invalid network probe frame");
        body=body.clone();
    }
    @Override public byte[] body() { return body.clone(); }
    public byte[] encode() {
        return ByteBuffer.allocate(HEADER+body.length).put((byte)3).put((byte)kind)
                .putLong(id.getMostSignificantBits()).putLong(id.getLeastSignificantBits())
                .putInt(sequence).putLong(value).putInt(body.length).put(body).array();
    }
    public static ProbeFrame decode(byte[] bytes) {
        if (bytes==null || bytes.length<HEADER || bytes.length>MAX_BYTES)
            throw new IllegalArgumentException("Invalid network probe length");
        ByteBuffer in=ByteBuffer.wrap(bytes);
        if (in.get()!=3) throw new IllegalArgumentException("Unsupported network probe version");
        int kind=Byte.toUnsignedInt(in.get());
        UUID id=new UUID(in.getLong(),in.getLong());
        int sequence=in.getInt();
        long value=in.getLong();
        int size=in.getInt();
        if (size<0 || size>BLOCK || size!=in.remaining())
            throw new IllegalArgumentException("Invalid network probe body");
        byte[] body=new byte[size]; in.get(body);
        return new ProbeFrame(kind,id,sequence,value,body);
    }
    public static ProbeFrame control(int kind, UUID id) { return new ProbeFrame(kind,id,0,0,new byte[0]); }
}
