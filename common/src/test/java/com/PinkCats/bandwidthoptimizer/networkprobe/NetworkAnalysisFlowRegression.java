package com.PinkCats.bandwidthoptimizer.networkprobe;

import com.PinkCats.bandwidthoptimizer.networkprobe.ui.NetworkAnalysisFlow;

import java.util.UUID;

final class NetworkAnalysisFlowRegression {
    private static int checks;
    private static void check(boolean value,String message) { checks++; if(!value) throw new AssertionError(message); }
    private static final class Peer implements NetworkAnalysisFlow.Endpoint {
        String channel="one"; long generation=1; boolean ready,supported=true; int requests,cancels;
        NetworkProbeRequest ticket;
        public String channelId(){return channel;} public long generation(){return generation;}
        public boolean ready(){return ready;} public boolean supported(){return supported;}
        public NetworkProbeRequest request(){requests++;ticket=new NetworkProbeRequest(UUID.randomUUID());ticket.status("waiting_for_authorization");return ticket;}
        public void cancel(UUID id){if(ticket!=null && ticket.id().equals(id)) cancels++;}
        public NetworkProbeProgress progress(){return null;}
    }
    static void run() {
        NetworkAnalysisFlow flow=new NetworkAnalysisFlow(); Peer peer=new Peer();
        flow.tick(peer,0); check(peer.requests==0 && flow.state().equals("idle"),"off has no effect");
        flow.armed(true); flow.tick(null,0); check(flow.armed(),"selection survives server list");
        flow.tick(peer,0); check(!flow.armed() && flow.state().equals("waiting_world"),"selection consumed by one connection");
        peer.ready=true; flow.tick(peer,1); flow.tick(peer,4_000_000_000L);
        check(peer.requests==0,"five seconds of readiness required");
        peer.ready=false; flow.tick(peer,4_000_000_001L); peer.ready=true; flow.tick(peer,5_000_000_000L);
        flow.tick(peer,10_000_000_000L); check(peer.requests==1,"readiness restarts then admits once");
        peer.ticket.status("server_busy_or_cooldown"); flow.tick(peer,11_000_000_000L); flow.tick(peer,100_000_000_000L);
        check(peer.requests==1 && flow.state().equals("server_busy_or_cooldown"),"busy never automatically retries");
        flow.retry(peer,101_000_000_000L); flow.tick(peer,101_000_000_000L); flow.tick(peer,106_000_000_000L);
        check(peer.requests==2,"explicit retry allowed");
        flow.cancel(peer); flow.tick(peer,107_000_000_000L);
        check(peer.cancels==1 && flow.state().equals("cancelled"),"cancel stays cancelled");
        flow.retry(peer,108_000_000_000L); flow.tick(peer,108_000_000_000L); flow.tick(peer,113_000_000_000L);
        peer.generation++; flow.tick(peer,114_000_000_000L);
        check(flow.state().equals("connection_changed") && peer.cancels==2,"same socket backend replacement terminates selection");
        flow.tick(peer,120_000_000_000L); check(peer.requests==3,"backend replacement does not retest");
        flow.armed(true); flow.tick(peer,121_000_000_000L); peer.supported=false; flow.tick(peer,126_000_000_000L);
        check(flow.state().equals("unsupported") && peer.requests==3,"missing capability generates no traffic");
        flow.retry(peer,127_000_000_000L); peer.ready=false; flow.tick(peer,187_000_000_000L);
        check(flow.state().equals("world_not_ready"),"world wait bounded");
        peer.supported=true; peer.ready=true; flow.retry(peer,188_000_000_000L); flow.tick(peer,188_000_000_000L);
        flow.tick(null,189_000_000_000L); check(flow.state().equals("connection_changed"),"disconnect while stabilizing terminates");
        peer.channel="two"; flow.tick(peer,200_000_000_000L); check(peer.requests==3,"reconnect requires new selection");
        flow.retry(peer,201_000_000_000L);flow.tick(peer,201_000_000_000L);flow.tick(peer,206_000_000_000L);
        var report=new com.google.gson.JsonObject();report.addProperty("test_id",peer.ticket.id().toString());report.addProperty("side","client");report.addProperty("outcome","completed");
        NetworkProbeResults.record(new NetworkProbeResults.Key(peer.channel,peer.ticket.id(),false),report);
        peer.ticket.status("completed");flow.tick(peer,207_000_000_000L);
        check(flow.result()!=null && flow.result().key().testId().equals(peer.ticket.id()),"completion result retained by ticket identity");
        var frozen=flow.result();peer.channel="three";peer.generation++;flow.tick(peer,208_000_000_000L);
        check(flow.result()==frozen && !flow.active(),"completed result stays with original connection after replacement");
        flow.retry(peer,209_000_000_000L);flow.tick(peer,209_000_000_000L);flow.tick(peer,214_000_000_000L);
        flow.cancel(peer);check(flow.result()==null,"cancel may precede result publication");
        report.addProperty("test_id",peer.ticket.id().toString());report.addProperty("outcome","operator_cancelled");
        NetworkProbeResults.record(new NetworkProbeResults.Key(peer.channel,peer.ticket.id(),false),report);
        flow.tick(null,215_000_000_000L);
        check(flow.result()!=null && flow.state().equals("cancelled") && !flow.active(),"late partial result collected without an endpoint or reactivation");
        flow=new NetworkAnalysisFlow();peer=new Peer();
        flow.start(null,0);check(flow.armed() && flow.active() && flow.state().equals("waiting_multiplayer"),"start at menu waits for multiplayer");
        flow.tick(null,10_000_000_000L);check(flow.armed() && peer.requests==0,"menu waiting does not send");
        flow.tick(peer,11_000_000_000L);peer.ready=true;flow.tick(peer,12_000_000_000L);flow.tick(peer,17_000_000_000L);
        check(peer.requests==1 && !flow.armed(),"join automatically consumes queued sample once");
        flow.cancel(peer);flow.start(peer,18_000_000_000L);
        check(peer.requests==2,"explicit in-world resample starts immediately");
        flow.start(peer,19_000_000_000L);check(peer.requests==2,"double click cannot start duplicate probe");
        flow.cancel(peer);flow.start(null,20_000_000_000L);flow.cancel(null);flow.tick(peer,21_000_000_000L);
        check(!flow.armed() && peer.requests==2,"cancelled menu request cannot start on join");
        System.out.println("NETWORK_ANALYSIS_FLOW_PASS checks="+checks);
    }
}
