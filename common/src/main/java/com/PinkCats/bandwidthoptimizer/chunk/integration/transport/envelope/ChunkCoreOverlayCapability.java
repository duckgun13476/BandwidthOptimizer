package com.PinkCats.bandwidthoptimizer.chunk.integration.transport.envelope;

import com.PinkCats.bandwidthoptimizer.chunk.protocol.hotspot.ChunkHotspotFrame;
import com.PinkCats.bandwidthoptimizer.chunk.protocol.hotspot.ChunkHotspotFrameOp;
import com.PinkCats.bandwidthoptimizer.chunk.refresh.UpdatedChunkCacheStats;
import io.netty.channel.Channel;
import io.netty.util.AttributeKey;

/** Opaque scope offer followed by an explicit Bloom acceptance. */
public final class ChunkCoreOverlayCapability {
    private static final String MARKER="|bo_core_overlay_1";
    private static final AttributeKey<State> KEY=AttributeKey.valueOf("bandwidthoptimizer.coreOverlay.v1");
    private static final class State { volatile boolean peer, offered; }
    private ChunkCoreOverlayCapability() {}
    private static State state(Channel channel) {
        if(channel==null || !channel.isOpen())return null;
        State s=channel.attr(KEY).get();
        if(s==null){State fresh=new State(), old=channel.attr(KEY).setIfAbsent(fresh);s=old==null?fresh:old;
            if(old==null)channel.closeFuture().addListener(f->{channel.attr(KEY).set(null);com.PinkCats.bandwidthoptimizer.chunk.refresh.ChunkCoreClaims.clear(channel.id().asLongText());});}
        return s;
    }
    public static boolean supported(Channel channel) {
        State s=channel==null?null:channel.attr(KEY).get();
        return UpdatedChunkCacheStats.ENABLED && s!=null && s.peer;
    }
    public static boolean independentCompression(Channel channel) {
        var session=com.PinkCats.bandwidthoptimizer.channel.ChannelTransportStateManager.existingSession(channel);
        return session!=null && !session.isCrossFrameZstdEnabled();
    }
    public static ChunkHotspotFrame advertise(Channel channel,ChunkHotspotFrame frame) {
        if(!UpdatedChunkCacheStats.ENABLED)return frame;
        if(frame.operation()!=ChunkHotspotFrameOp.SERVER_CACHE_SCOPE && frame.operation()!=ChunkHotspotFrameOp.CLIENT_CACHE_BLOOM)return frame;
        State s=state(channel);if(s==null)return frame;
        if(frame.operation()==ChunkHotspotFrameOp.SERVER_CACHE_SCOPE) {
            // Actual envelope measurements showed a loss in warm streaming
            // contexts. Do not reset or disturb that context to manufacture a
            // saving; keep this optional path for independent compression.
            if(!independentCompression(channel))return frame;
            s.offered=true;
        }
        else if(!s.peer)return frame;
        return reason(frame,(frame.reason()==null?"":frame.reason())+MARKER);
    }
    public static ChunkTransportEnvelope accept(Channel channel,ChunkTransportEnvelope envelope) {
        ChunkHotspotFrame f=envelope.frame();String r=f.reason();
        if((f.operation()!=ChunkHotspotFrameOp.SERVER_CACHE_SCOPE && f.operation()!=ChunkHotspotFrameOp.CLIENT_CACHE_BLOOM)
                || r==null || !r.endsWith(MARKER))return envelope;
        if(UpdatedChunkCacheStats.ENABLED){State s=state(channel);if(s!=null && (f.operation()==ChunkHotspotFrameOp.SERVER_CACHE_SCOPE || s.offered))s.peer=true;}
        return new ChunkTransportEnvelope(reason(f,r.substring(0,r.length()-MARKER.length())),envelope.copyOriginalPacketBytes());
    }
    private static ChunkHotspotFrame reason(ChunkHotspotFrame f,String r) {
        return new ChunkHotspotFrame(f.protocolVersion(),f.operation(),f.epoch(),f.observedPacketCount(),f.protocolName(),f.packetClassName(),f.hotspotKind(),f.laneKind(),f.coordinate(),f.originalEncodedBytes(),f.fullSnapshotVersion(),f.laneVersion(),f.baseSnapshotHash(),f.payloadHash(),f.deltaBytesSinceFullSnapshot(),r);
    }
}
