package com.PinkCats.bandwidthoptimizer.networkprobe.ui;

import com.PinkCats.bandwidthoptimizer.networkprobe.NetworkProbeProgress;
import com.PinkCats.bandwidthoptimizer.networkprobe.NetworkProbeRequest;
import com.PinkCats.bandwidthoptimizer.networkprobe.NetworkProbeResults;

import java.util.Objects;
import java.util.UUID;

/** Client-thread product state. One attempt per selection, no connection references or automatic retries. */
public final class NetworkAnalysisFlow {
    public interface Endpoint {
        String channelId();
        long generation();
        boolean ready();
        boolean supported();
        NetworkProbeRequest request();
        void cancel(UUID testId);
        NetworkProbeProgress progress();
    }
    static final long STABLE_NS=5_000_000_000L, WORLD_TIMEOUT_NS=60_000_000_000L;
    private boolean armed;
    private boolean immediate;
    private String channelId,state="idle";
    private long generation,attachedAt,readyAt=-1;
    private NetworkProbeRequest ticket;
    private NetworkProbeProgress progress;
    private NetworkProbeResults.Result result;
    public boolean armed() { return armed; }
    public void armed(boolean selected) { armed=selected; }
    public String state() { return state; }
    public NetworkProbeRequest ticket() { return ticket; }
    public NetworkProbeProgress progress() { return progress; }
    public NetworkProbeResults.Result result() { return result; }
    public boolean active() { return state.equals("waiting_multiplayer") || state.equals("waiting_world") || state.equals("stabilizing") || state.equals("waiting_for_authorization") || state.equals("running") || state.equals("queued"); }
    public void tick(Endpoint endpoint,long now) {
        if(ticket!=null && channelId!=null) {
            NetworkProbeResults.Result saved=NetworkProbeResults.find(new NetworkProbeResults.Key(channelId,ticket.id(),false));
            if(saved!=null) result=saved;
        }
        if(channelId!=null && active() && (endpoint==null || !channelId.equals(endpoint.channelId()) || generation!=endpoint.generation())) {
            if(endpoint!=null && ticket!=null && channelId.equals(endpoint.channelId())) endpoint.cancel(ticket.id());
            state="connection_changed"; progress=null; return;
        }
        if(armed && endpoint!=null && (!active() || state.equals("waiting_multiplayer"))) {
            armed=false; attach(endpoint,now);
        }
        if(endpoint==null || channelId==null || !channelId.equals(endpoint.channelId()) || generation!=endpoint.generation()) return;
        if(ticket!=null) {
            NetworkProbeProgress sampled=endpoint.progress();
            if(sampled!=null && sampled.id().equals(ticket.id())) progress=sampled;
            if(active()) state=ticket.status();
            // Completion publishes the result before the volatile ticket status. Re-read after
            // observing that status so a render tick racing completion cannot lose the result.
            if(!active()) {
                NetworkProbeResults.Result saved=NetworkProbeResults.find(new NetworkProbeResults.Key(channelId,ticket.id(),false));
                if(saved!=null) result=saved;
            }
            return;
        }
        if(!active()) return;
        if(now-attachedAt>=WORLD_TIMEOUT_NS) { state="world_not_ready"; return; }
        if(!endpoint.ready()) { readyAt=-1; state="waiting_world"; return; }
        if(readyAt<0) readyAt=now;
        state="stabilizing";
        if(!immediate && now-readyAt<STABLE_NS) return;
        if(!endpoint.supported()) { state="unsupported"; return; }
        ticket=Objects.requireNonNull(endpoint.request()); state=ticket.status();
    }
    private void attach(Endpoint endpoint,long now) {
        channelId=endpoint.channelId(); generation=endpoint.generation(); attachedAt=now; readyAt=-1;
        immediate=false;
        ticket=null; progress=null; result=null; state="waiting_world";
    }
    public void retry(Endpoint endpoint,long now) {
        if(endpoint==null || active()) return;
        attach(endpoint,now);
    }
    /** Explicit user start: ready multiplayer tests immediately; menus arm one future join. */
    public void start(Endpoint endpoint,long now) {
        if(active()) return;
        if(endpoint==null) {
            armed=true;channelId=null;ticket=null;progress=null;result=null;state="waiting_multiplayer";
        } else {
            armed=false;attach(endpoint,now);
            immediate=endpoint.ready();
            tick(endpoint,now);
        }
    }
    public void cancel(Endpoint endpoint) {
        armed=false;
        if(active()) {
            if(endpoint!=null && ticket!=null && channelId.equals(endpoint.channelId()) && generation==endpoint.generation()) endpoint.cancel(ticket.id());
            state="cancelled";
        }
    }
}
