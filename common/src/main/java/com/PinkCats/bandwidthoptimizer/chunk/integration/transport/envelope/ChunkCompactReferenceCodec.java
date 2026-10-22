package com.PinkCats.bandwidthoptimizer.chunk.integration.transport.envelope;

import com.PinkCats.bandwidthoptimizer.chunk.classify.ChunkHotspotKind;
import com.PinkCats.bandwidthoptimizer.chunk.classify.ChunkLaneKind;
import com.PinkCats.bandwidthoptimizer.chunk.classify.packet.ChunkPacketCoordinate;
import com.PinkCats.bandwidthoptimizer.chunk.protocol.hotspot.ChunkHotspotFrame;
import com.PinkCats.bandwidthoptimizer.chunk.protocol.hotspot.ChunkHotspotFrameOp;
import io.netty.buffer.ByteBufUtil;
import io.netty.buffer.Unpooled;
import io.netty.channel.Channel;
import io.netty.util.AttributeKey;
import net.minecraft.network.FriendlyByteBuf;

import java.util.HashMap;
import java.util.Map;
import java.util.BitSet;
import java.nio.charset.StandardCharsets;

/**
 * Negotiated wire metadata dictionary. IDs are connection-local names, not hash
 * truncations. Never retains packet bodies or world objects, and never recycles
 * an ID while the connection is alive (including across transport epochs).
 */
public final class ChunkCompactReferenceCodec {
    private static final boolean ENABLED = Boolean.parseBoolean(System.getProperty("bandwidthoptimizer.compactChunkReferences", "true"));
    private static final String CAPABILITY = "|bo_compact_ref_1";
    private static final String ACK_ID = "|bo_ref_id=";
    private static final String FULL_CLASS = "net.minecraft.network.protocol.game.ClientboundLevelChunkWithLightPacket";
    private static final byte[] MAGIC = {'B','O','C','H','K','E','N','V'};
    private static final AttributeKey<State> STATE = AttributeKey.valueOf("bandwidthoptimizer.compactChunkReferences.v1");
    static final int LIMIT = 4096;
    private static final int DEFINE_REF = 2, REF = 3, LITERAL_REF = 4, BINDING_ACK = 5;
    private static final String[] REASONS = {
            "reuse_acknowledged_full_snapshot",
            "reuse_persistent_client_cache_manifest",
            "reuse_inflight_full_snapshot_before_ack",
            "reuse_cached_full_snapshot_after_watch_boundary"
    };
    private static final String[] ACK_REASONS = {
            "runtime_full_received", "runtime_ref_reused_after_watch_boundary",
            "runtime_ref_reused_from_persistent_cache", "runtime_ref_reused_after_persistent_manifest"
    };
    private ChunkCompactReferenceCodec() {}

    private record Identity(long epoch, int x, int z, String hash) {}
    private static final class State {
        volatile boolean peer;
        boolean offerSent;
        final Map<Identity, Integer> outbound = new HashMap<>();
        final Identity[] outboundIdentities = new Identity[LIMIT];
        final BitSet acknowledged = new BitSet(LIMIT);
        final Map<Identity, Integer> inboundIds = new HashMap<>();
        final BitSet needsAckId = new BitSet(LIMIT);
        final Identity[] inbound = new Identity[LIMIT];
    }
    private static State state(Channel channel) {
        if (channel == null || !channel.isOpen()) return null;
        State value = channel.attr(STATE).get();
        if (value == null) {
            State fresh = new State();
            State previous = channel.attr(STATE).setIfAbsent(fresh);
            value = previous == null ? fresh : previous;
            if (previous == null) channel.closeFuture().addListener(f -> channel.attr(STATE).set(null));
        }
        return value;
    }

    /** Only existing controls with opaque reasons carry capability offers; old peers ignore them. */
    public static ChunkHotspotFrame advertise(Channel channel, ChunkHotspotFrame frame) {
        if(ENABLED && channel!=null && frame.operation()==ChunkHotspotFrameOp.ACK) {
            State s=channel.attr(STATE).get();
            if(s!=null) synchronized(s) {
                Identity i=identity(frame);Integer id=i==null?null:s.inboundIds.get(i);
                if(id!=null && s.needsAckId.get(id)) {
                    s.needsAckId.clear(id);
                    return reason(frame,frame.reason()+ACK_ID+Integer.toHexString(id));
                }
            }
        }
        if (!ENABLED || channel == null || !channel.isOpen() || (frame.operation() != ChunkHotspotFrameOp.SERVER_CACHE_SCOPE
                && frame.operation() != ChunkHotspotFrameOp.CLIENT_CACHE_BLOOM)) return frame;
        // A Bloom response is an acceptance, not an unsolicited offer: the
        // server must know that its own offer was already received in order.
        if(frame.operation()==ChunkHotspotFrameOp.CLIENT_CACHE_BLOOM) {
            State s=channel==null?null:channel.attr(STATE).get();
            if(s==null || !s.peer)return frame;
        } else {
            State s = state(channel);
            if (s == null) return frame;
            synchronized (s) { s.offerSent = true; }
        }
        return reason(frame, (frame.reason()==null?"":frame.reason()) + CAPABILITY);
    }
    public static ChunkTransportEnvelope acceptCapability(Channel channel, ChunkTransportEnvelope envelope) {
        var frame = envelope.frame();
        if(frame.operation()==ChunkHotspotFrameOp.ACK && frame.reason()!=null) {
            int marker=frame.reason().lastIndexOf(ACK_ID);
            if(marker>=0) {
                String suffix=frame.reason().substring(marker+ACK_ID.length());
                if(suffix.length()>0 && suffix.length()<=3) {
                    try {
                        int id=Integer.parseInt(suffix,16);
                        State s=channel==null?null:channel.attr(STATE).get();
                        Identity i=identity(frame);
                        if(s!=null && i!=null) synchronized(s) {
                            if(Integer.valueOf(id).equals(s.outbound.get(i)))s.acknowledged.set(id);
                        }
                        return new ChunkTransportEnvelope(reason(frame,frame.reason().substring(0,marker)),envelope.copyOriginalPacketBytes());
                    } catch(NumberFormatException ignored) { /* ordinary ACK validation remains authoritative */ }
                }
            }
        }
        if ((frame.operation() == ChunkHotspotFrameOp.SERVER_CACHE_SCOPE || frame.operation() == ChunkHotspotFrameOp.CLIENT_CACHE_BLOOM)
                && frame.reason() != null && frame.reason().endsWith(CAPABILITY)) {
            if (ENABLED) {
                State s = state(channel);
                if (s != null) synchronized (s) {
                    // Bloom is a response to our offer, never an unsolicited offer.
                    if (frame.operation() == ChunkHotspotFrameOp.SERVER_CACHE_SCOPE || s.offerSent) s.peer = true;
                }
            }
            return new ChunkTransportEnvelope(reason(frame, frame.reason().substring(0, frame.reason().length()-CAPABILITY.length())), envelope.copyOriginalPacketBytes());
        }
        return envelope;
    }
    private static ChunkHotspotFrame reason(ChunkHotspotFrame f, String reason) {
        return new ChunkHotspotFrame(f.protocolVersion(),f.operation(),f.epoch(),f.observedPacketCount(),f.protocolName(),f.packetClassName(),
                f.hotspotKind(),f.laneKind(),f.coordinate(),f.originalEncodedBytes(),f.fullSnapshotVersion(),f.laneVersion(),
                f.baseSnapshotHash(),f.payloadHash(),f.deltaBytesSinceFullSnapshot(),reason);
    }
    private static Identity identity(ChunkHotspotFrame f) {
        if (f.protocolVersion()!=1 || !"PLAY".equals(f.protocolName()) || !FULL_CLASS.equals(f.packetClassName())
                || f.hotspotKind()!=ChunkHotspotKind.FULL_CHUNK || f.laneKind()!=ChunkLaneKind.FULL
                || f.coordinate()==null || !f.coordinate().present() || f.epoch()<0
                || !validHash(f.payloadHash())
                || !f.payloadHash().equals(f.baseSnapshotHash())) return null;
        return new Identity(f.epoch(),f.coordinate().chunkX(),f.coordinate().chunkZ(),f.payloadHash());
    }
    private static boolean validHash(String hash) {
        if(hash==null || hash.length()!=64)return false;
        for(int i=0;i<64;i++){char c=hash.charAt(i);if(!((c>='0'&&c<='9')||(c>='a'&&c<='f')))return false;}
        return true;
    }

    public static byte[] tryEncode(Channel channel, ChunkTransportEnvelope envelope) {
        if (!ENABLED || channel==null || !channel.isOpen()) return null;
        State s = channel.attr(STATE).get();
        if (s==null || !s.peer) return null;
        ChunkHotspotFrame f=envelope.frame();
        // FULL must remain byte-identical. A binding header shifts the mapping
        // input and can cost more compressed bytes than subsequent REF savings.
        // Allocate metadata lazily only when a real reuse is selected.
        boolean ack = f.operation()==ChunkHotspotFrameOp.ACK;
        if (f.operation()!=ChunkHotspotFrameOp.PUBLISH_REF && !ack) return null;
        Identity identity=identity(f);
        int reason=reasonCode(f.reason(),ack?ACK_REASONS:REASONS);
        if (identity==null || reason<0 || f.originalEncodedBytes()!=0 || envelope.copyOriginalPacketBytes().length!=0
                || f.observedPacketCount()<0 || f.fullSnapshotVersion()<0 || f.laneVersion()<0 || f.deltaBytesSinceFullSnapshot()<0) return null;
        synchronized(s) {
            Integer id=ack?s.inboundIds.get(identity):s.outbound.get(identity);
            if (ack && id == null) return null; // no binding ACK for an unseen definition
            boolean fresh=!ack && id==null && s.outbound.size()<LIMIT;
            boolean define=!ack && (fresh || (id!=null && !s.acknowledged.get(id)));
            int nextId=fresh?s.outbound.size():id==null?-1:id;
            FriendlyByteBuf b=new FriendlyByteBuf(Unpooled.buffer());
            try {
                b.writeBytes(MAGIC); b.writeVarInt(0);
                b.writeByte(ack?BINDING_ACK:define?DEFINE_REF:id==null?LITERAL_REF:REF);
                if(nextId>=0)b.writeShort(nextId);
                if(!ack && (define || id==null))writeIdentity(b,identity);
                b.writeVarLong(f.observedPacketCount());b.writeVarLong(f.fullSnapshotVersion());
                b.writeVarLong(f.laneVersion());b.writeVarLong(f.deltaBytesSinceFullSnapshot());b.writeByte(reason);
                byte[] bytes=ByteBufUtil.getBytes(b);
                // IDs are never recycled, including across epochs, until this connection closes.
                if(fresh) { s.outbound.put(identity,nextId); s.outboundIdentities[nextId]=identity; }
                if(ack)s.needsAckId.clear(nextId);
                return bytes;
            } finally { b.release(); }
        }
    }
    private static int reasonCode(String reason,String[] reasons) {
        for(int i=0;i<reasons.length;i++)if(reasons[i].equals(reason))return i;
        return -1;
    }
    private static void writeIdentity(FriendlyByteBuf b, Identity i) {
        b.writeVarLong(i.epoch());b.writeVarInt((i.x()<<1)^(i.x()>>31));b.writeVarInt((i.z()<<1)^(i.z()>>31));
        // FULL/manifest already sent the hex representation through the same
        // stream. Reuse that history instead of introducing 32 unrelated binary
        // hash bytes on the first REF (smaller before Zstd can be larger after it).
        b.writeBytes(i.hash().getBytes(StandardCharsets.US_ASCII));
    }
    private static Identity readIdentity(FriendlyByteBuf b) {
        long epoch=positive(b.readVarLong());int x=b.readVarInt(),z=b.readVarInt();
        String hash=b.readCharSequence(64,StandardCharsets.US_ASCII).toString();
        if (!validHash(hash)) throw new IllegalArgumentException("Invalid compact reference hash");
        return new Identity(epoch,(x>>>1)^-(x&1),(z>>>1)^-(z&1),hash);
    }
    private static long positive(long value) {
        if(value<0)throw new IllegalArgumentException("Negative compact reference counter");return value;
    }
    public static boolean isCompact(byte[] bytes) {
        return ChunkTransportEnvelopeCodec.looksLikeEnvelope(bytes) && bytes.length>8 && bytes[8]==0;
    }
    public static ChunkTransportEnvelope decode(Channel channel, byte[] bytes) {
        if(!isCompact(bytes))throw new IllegalArgumentException("Invalid compact reference magic");
        State s=channel==null?null:channel.attr(STATE).get();
        if(!ENABLED || s==null || !s.peer)throw new IllegalArgumentException("Unnegotiated compact reference");
        FriendlyByteBuf b=new FriendlyByteBuf(Unpooled.wrappedBuffer(bytes));
        try {
            b.skipBytes(9);int kind=b.readUnsignedByte();
            if(kind<DEFINE_REF || kind>BINDING_ACK)throw new IllegalArgumentException("Unknown compact reference kind");
            int id=kind==LITERAL_REF?-1:b.readUnsignedShort();
            if(id>=LIMIT)throw new IllegalArgumentException("Compact reference ID outside dictionary");
            synchronized(s) {
                Identity identity;ChunkTransportEnvelope result;
                {
                    identity=kind==BINDING_ACK?s.outboundIdentities[id]:kind==REF?s.inbound[id]:readIdentity(b);
                    if(identity==null)throw new IllegalArgumentException("Unknown compact reference ID");
                    long count=positive(b.readVarLong()),version=positive(b.readVarLong()),lane=positive(b.readVarLong()),delta=positive(b.readVarLong());
                    String[] reasons=kind==BINDING_ACK?ACK_REASONS:REASONS;
                    int reason=b.readUnsignedByte();if(reason>=reasons.length)throw new IllegalArgumentException("Unknown compact reference reason");
                    result=new ChunkTransportEnvelope(new ChunkHotspotFrame(1,kind==BINDING_ACK?ChunkHotspotFrameOp.ACK:ChunkHotspotFrameOp.PUBLISH_REF,identity.epoch(),count,"PLAY",FULL_CLASS,
                            ChunkHotspotKind.FULL_CHUNK,ChunkLaneKind.FULL,ChunkPacketCoordinate.ofChunk(identity.x(),identity.z()),0,
                            version,lane,identity.hash(),identity.hash(),delta,reasons[reason]),new byte[0]);
                }
                if(b.isReadable())throw new IllegalArgumentException("Trailing compact reference bytes");
                if(kind==BINDING_ACK)s.acknowledged.set(id);
                if(kind==DEFINE_REF) {
                    Identity previous=s.inbound[id];
                    if(previous!=null && !previous.equals(identity))throw new IllegalArgumentException("Compact reference ID cannot be rebound");
                    Integer previousId = s.inboundIds.get(identity);
                    if (previousId != null && previousId != id) throw new IllegalArgumentException("Compact reference identity already bound");
                    if(previous==null){s.inbound[id]=identity;s.inboundIds.put(identity,id);}
                    s.needsAckId.set(id);
                }
                return result;
            }
        } finally {b.release();}
    }
}
