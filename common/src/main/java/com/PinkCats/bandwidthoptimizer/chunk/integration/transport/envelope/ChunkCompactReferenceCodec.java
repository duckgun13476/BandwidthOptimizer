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
 * an ID within a sender session (including across transport epochs). A proxy
 * backend replacement negotiates a new session on the surviving client socket.
 */
public final class ChunkCompactReferenceCodec {
    private static final boolean ENABLED = Boolean.parseBoolean(System.getProperty("bandwidthoptimizer.compactChunkReferences", "true"));
    private static final String CAPABILITY = "|bo_compact_ref_2=";
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
        final java.util.UUID offerNonce = java.util.UUID.randomUUID();
        final String offerSession = offerNonce.toString();
        String acceptedSession;
        java.util.UUID acceptedNonce;
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
        String session;
        if(frame.operation()==ChunkHotspotFrameOp.CLIENT_CACHE_BLOOM) {
            State s=channel==null?null:channel.attr(STATE).get();
            if(s==null)return frame;
            synchronized(s) {
                if(!s.peer || s.acceptedSession==null)return frame;
                session=s.acceptedSession;
            }
        } else {
            State s = state(channel);
            if (s == null) return frame;
            synchronized (s) { s.offerSent = true; session=s.offerSession; }
        }
        return reason(frame, (frame.reason()==null?"":frame.reason()) + CAPABILITY + session);
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
        int capability = frame.reason()==null?-1:frame.reason().lastIndexOf(CAPABILITY);
        String session=capability<0?null:frame.reason().substring(capability+CAPABILITY.length());
        if ((frame.operation() == ChunkHotspotFrameOp.SERVER_CACHE_SCOPE || frame.operation() == ChunkHotspotFrameOp.CLIENT_CACHE_BLOOM)
                && validSession(session)) {
            if (ENABLED) {
                State s = state(channel);
                if (s != null) synchronized (s) {
                    if (frame.operation() == ChunkHotspotFrameOp.SERVER_CACHE_SCOPE) {
                        // A proxy can replace the backend without closing this socket.
                        // Only a new sender session invalidates its ordered dictionary.
                        if (!session.equals(s.acceptedSession)) {
                            s.inboundIds.clear(); java.util.Arrays.fill(s.inbound,null);
                            s.needsAckId.clear(); s.acceptedSession=session;
                            s.acceptedNonce=java.util.UUID.fromString(session);
                        }
                        s.peer=true;
                    } else if(s.offerSent && session.equals(s.offerSession)) s.peer=true;
                }
            }
            return new ChunkTransportEnvelope(reason(frame, frame.reason().substring(0, capability)), envelope.copyOriginalPacketBytes());
        }
        if(frame.operation()==ChunkHotspotFrameOp.SERVER_CACHE_SCOPE) {
            State s=channel==null?null:channel.attr(STATE).get();
            if(s!=null)synchronized(s){s.peer=false;}
        }
        return envelope;
    }
    private static boolean validSession(String value) {
        if(value==null || value.length()!=36)return false;
        try { return java.util.UUID.fromString(value).toString().equals(value); }
        catch(IllegalArgumentException invalid){return false;}
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
                // Echo the negotiated sender session: old ACKs must never
                // resolve their ID against a replacement backend's dictionary.
                if(ack){b.writeLong(s.acceptedNonce.getMostSignificantBits());b.writeLong(s.acceptedNonce.getLeastSignificantBits());}
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
        boolean bindingAck=bytes.length>9 && bytes[9]==BINDING_ACK;
        // A retired backend's ACK may arrive even before the new offer is
        // accepted. It may be consumed, but must not activate negotiation.
        if(s==null && bindingAck)s=state(channel);
        if(s==null || ((!ENABLED || !s.peer) && !bindingAck))throw new IllegalArgumentException("Unnegotiated compact reference");
        FriendlyByteBuf b=new FriendlyByteBuf(Unpooled.wrappedBuffer(bytes));
        try {
            b.skipBytes(9);int kind=b.readUnsignedByte();
            if(kind<DEFINE_REF || kind>BINDING_ACK)throw new IllegalArgumentException("Unknown compact reference kind");
            int id=kind==LITERAL_REF?-1:b.readUnsignedShort();
            if(id>=LIMIT)throw new IllegalArgumentException("Compact reference ID outside dictionary");
            synchronized(s) {
                Identity identity;ChunkTransportEnvelope result;
                {
                    long ackMost=kind==BINDING_ACK?b.readLong():0L,ackLeast=kind==BINDING_ACK?b.readLong():0L;
                    boolean staleAck=kind==BINDING_ACK && (s.offerNonce.getMostSignificantBits()!=ackMost || s.offerNonce.getLeastSignificantBits()!=ackLeast);
                    if(kind==BINDING_ACK && !staleAck && (!ENABLED || !s.peer))throw new IllegalArgumentException("Unnegotiated compact ACK");
                    identity=kind==BINDING_ACK?s.outboundIdentities[id]:kind==REF?s.inbound[id]:readIdentity(b);
                    if(identity==null && !staleAck)throw new IllegalArgumentException("Unknown compact reference ID");
                    long count=positive(b.readVarLong()),version=positive(b.readVarLong()),lane=positive(b.readVarLong()),delta=positive(b.readVarLong());
                    String[] reasons=kind==BINDING_ACK?ACK_REASONS:REASONS;
                    int reason=b.readUnsignedByte();if(reason>=reasons.length)throw new IllegalArgumentException("Unknown compact reference reason");
                    if(b.isReadable())throw new IllegalArgumentException("Trailing compact reference bytes");
                    // Fully validated, but belongs to a retired backend. This
                    // control has no authority in this session and is consumed.
                    if(staleAck)return null;
                    result=new ChunkTransportEnvelope(new ChunkHotspotFrame(1,kind==BINDING_ACK?ChunkHotspotFrameOp.ACK:ChunkHotspotFrameOp.PUBLISH_REF,identity.epoch(),count,"PLAY",FULL_CLASS,
                            ChunkHotspotKind.FULL_CHUNK,ChunkLaneKind.FULL,ChunkPacketCoordinate.ofChunk(identity.x(),identity.z()),0,
                            version,lane,identity.hash(),identity.hash(),delta,reasons[reason]),new byte[0]);
                }
                if(b.isReadable())throw new IllegalArgumentException("Trailing compact reference bytes");
                if(kind==BINDING_ACK && identity.equals(s.outboundIdentities[id]))s.acknowledged.set(id);
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
