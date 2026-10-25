package com.PinkCats.bandwidthoptimizer.networkprobe;

import com.google.gson.*;
import java.nio.file.*;

final class NetworkProbeFindingsRegression {
    private static int checks;
    private static void check(boolean value,String reason) { checks++; if(!value) throw new AssertionError(reason); }
    private static JsonObject report() {
        JsonObject r=new JsonObject(), analysis=new JsonObject();
        analysis.add("application_rtt_by_phase",new JsonObject()); r.add("analysis",analysis);
        r.add("windows",new JsonArray()); r.add("phases",new JsonObject());
        r.addProperty("rate_limit_bytes_s",1_000_000); return r;
    }
    private static void rtt(JsonObject r,String phase,int samples,long p95ms) {
        JsonObject q=new JsonObject(); q.addProperty("samples",samples); q.addProperty("p95_ns",p95ms*1_000_000);
        r.getAsJsonObject("analysis").getAsJsonObject("application_rtt_by_phase").add(phase,q);
    }
    private static void window(JsonObject r,long ms,long bytes,String phase) {
        JsonObject w=new JsonObject(); w.addProperty("elapsed_ns",ms*1_000_000);
        w.addProperty("test_received_bytes",bytes); w.addProperty("phase",phase); r.getAsJsonArray("windows").add(w);
    }
    private static boolean has(JsonArray findings,String code) {
        for(JsonElement f:findings) if(code.equals(f.getAsJsonObject().get("code").getAsString())) return true;
        return false;
    }
    static void run() {
        JsonObject r=report(); check(NetworkProbeFindings.detect(r).isEmpty(),"no evidence, no diagnosis");
        r.getAsJsonArray("windows").add(new JsonObject());
        check(NetworkProbeFindings.detect(r).isEmpty(),"partial sample does not prove a pause or break report");
        rtt(r,"BASELINE",10,20); rtt(r,"RECEIVE_UPLOAD",5,60);
        check(has(NetworkProbeFindings.detect(r),"loaded_application_latency_increase"),"loaded delay detected");
        rtt(r,"RECEIVE_UPLOAD",4,60);
        check(NetworkProbeFindings.detect(r).isEmpty(),"small RTT sample rejected");
        rtt(r,"RECEIVE_UPLOAD",5,40);
        check(NetworkProbeFindings.detect(r).isEmpty(),"exact20ms threshold rejected");
        rtt(r,"BASELINE",10,100); rtt(r,"RECEIVE_UPLOAD",5,140);
        check(NetworkProbeFindings.detect(r).isEmpty(),"small relative change rejected");
        r=report(); for(int i=0;i<=6;i++) window(r,i*100,100,"RECEIVE_UPLOAD");
        window(r,700,200,"RECEIVE_UPLOAD");
        check(has(NetworkProbeFindings.detect(r),"receive_pause_recovered"),"sampled pause recovered");
        r=report(); window(r,0,100,"RECEIVE_UPLOAD"); window(r,700,100,"RECEIVE_UPLOAD"); window(r,800,200,"RECEIVE_UPLOAD");
        check(NetworkProbeFindings.detect(r).isEmpty(),"sampling gap not inferred as network pause");
        r=report(); for(int i=0;i<6;i++) window(r,i*100,100,"RECEIVE_UPLOAD");
        window(r,600,200,"RECEIVE_DOWNLOAD");
        check(NetworkProbeFindings.detect(r).isEmpty(),"phase boundary cannot prove recovery");
        r=report(); for(int i=0;i<=6;i++) window(r,i*100,100,"RECEIVE_UPLOAD");
        check(NetworkProbeFindings.detect(r).isEmpty(),"unrecovered stall not labeled recovered");
        JsonObject measured=new JsonObject(); measured.addProperty("verified_bytes",250_000);
        measured.addProperty("receiver_phase_ns",1_000_000_000L); r.getAsJsonObject("phases").add("upload_received",measured);
        check(has(NetworkProbeFindings.detect(r),"receive_rate_below_configured_sender_rate"),"rate deficit detected");
        measured.addProperty("receiver_phase_ns",900_000_000L);
        check(NetworkProbeFindings.detect(r).isEmpty(),"short transfer cannot establish rate deficit");
        measured.addProperty("receiver_phase_ns",1_000_000_000L); measured.addProperty("verified_bytes",700_000);
        check(NetworkProbeFindings.detect(r).isEmpty(),"exact ratio threshold rejected");
        String root=System.getProperty("networkProbeEvidenceRoot");
        if(root!=null) replay(Path.of(root));
        System.out.println("NETWORK_PROBE_FINDINGS_PASS checks="+checks);
    }
    private static void replay(Path root) {
        try {
            for(String name:new String[]{"baseline","delay","jitter","loss-up","loss-down","burst-loss","corrupt","blackhole","rate-up","duplicate","reorder","reset","deadline"}) {
                JsonObject pair=JsonParser.parseString(Files.readString(root.resolve(name+"/result.json"))).getAsJsonObject();
                JsonArray server=NetworkProbeFindings.detect(pair.getAsJsonObject("server"));
                JsonArray client=NetworkProbeFindings.detect(pair.getAsJsonObject("client"));
                check(server.size()<=6 && client.size()<=6,"bounded findings "+name);
                for(JsonArray findings:new JsonArray[]{server,client}) for(JsonElement f:findings) {
                    String code=f.getAsJsonObject().get("code").getAsString();
                    check(code.equals("loaded_application_latency_increase") || code.equals("receive_pause_recovered")
                            || code.equals("receive_rate_below_configured_sender_rate"),"only evidenced effects "+name);
                }
                if(name.equals("baseline")) check(server.isEmpty() && client.isEmpty(),"baseline no findings");
                if(name.equals("rate-up")) {
                    check(has(server,"loaded_application_latency_increase"),"real rate limit raises loaded RTT");
                    check(has(server,"receive_rate_below_configured_sender_rate"),"real rate limit deficit");
                }
                if(name.equals("blackhole")) check(has(server,"receive_pause_recovered"),"real blackhole recovers");
                System.out.println("FINDINGS_REPLAY "+name+" server="+server+" client="+client);
            }
        } catch(java.io.IOException failure) { throw new AssertionError(failure); }
    }
}
