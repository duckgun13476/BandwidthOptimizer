package com.PinkCats.bandwidthoptimizer.networkprobe;

import com.google.gson.*;
import java.util.*;

final class NetworkProbeResultsRegression {
    private static int checks;
    private static void check(boolean ok,String reason) { checks++; if(!ok) throw new AssertionError(reason); }
    private static JsonObject report(UUID id,boolean server) {
        JsonObject r=new JsonObject(); r.addProperty("test_id",id.toString());
        r.addProperty("side",server?"server":"client"); r.addProperty("outcome","completed");
        r.add("findings",new JsonArray()); r.add("windows",new JsonArray()); return r;
    }
    static void run() {
        NetworkProbeResults.Store store=new NetworkProbeResults.Store();
        UUID id=UUID.randomUUID();
        var old=new NetworkProbeResults.Key("old-connection",id,false);
        var next=new NetworkProbeResults.Key("new-connection",id,false);
        JsonObject raw=report(id,false); store.record(old,raw);
        check(store.find(old)!=null && store.find(next)==null,"connection ownership does not leak across reconnect");
        raw.addProperty("outcome","mutated");
        check(store.find(old).json().get("outcome").getAsString().equals("completed"),"owns input snapshot");
        var view=store.find(old).json(); view.addProperty("outcome","mutated again");
        check(store.find(old).json().get("outcome").getAsString().equals("completed"),"owns output snapshot");
        check(!view.has("windows"),"no raw window retention");
        var sameConnectionNewTest=new NetworkProbeResults.Key("old-connection",UUID.randomUUID(),false);
        check(store.find(sameConnectionNewTest)==null,"backend replacement/new test cannot reuse old result");
        boolean rejected=false;
        try { store.record(next,report(UUID.randomUUID(),false)); } catch(IllegalArgumentException expected) { rejected=true; }
        check(rejected && store.find(next)==null,"mismatched result rejected without mutation");
        for(int i=0;i<10;i++) {
            UUID uuid=UUID.randomUUID(); store.record(new NetworkProbeResults.Key("connection",uuid,false),report(uuid,false));
        }
        check(store.history().size()==8 && store.find(old)==null,"fixed result retention");
        var last=store.history().get(7); store.record(last.key(),report(last.key().testId(),false));
        check(store.history().size()==8,"duplicate completion replaces instead of growing");
        raw=report(id,false); raw.addProperty("last_error_class","x".repeat(20000)); store.record(next,raw);
        check(store.find(next).json().get("result_status").getAsString().equals("summary_size_limit"),"oversized summary explicitly incomplete");
        UUID roleId=UUID.randomUUID();
        var client=new NetworkProbeResults.Key("integrated",roleId,false);
        var server=new NetworkProbeResults.Key("integrated",roleId,true);
        NetworkProbeResults.record(client,report(roleId,false)); NetworkProbeResults.record(server,report(roleId,true));
        check(NetworkProbeResults.find(client)!=null && NetworkProbeResults.find(server)!=null,"roles separate in integrated JVM");
        com.PinkCats.bandwidthoptimizer.debug.DiagnosticToolRegistry.disable(
                com.PinkCats.bandwidthoptimizer.debug.DiagnosticToolRegistry.Tool.NETWORK_PROBE);
        UUID suppressedId=UUID.randomUUID();
        var suppressed=new NetworkProbeResults.Key("diagnostics-disabled",suppressedId,false);
        boolean[] delivered={false};
        NetworkProbeService.publishResult(suppressed,report(suppressedId,false),r -> {
            delivered[0]=NetworkProbeResults.find(suppressed)!=null;
        });
        check(delivered[0],"functional result and callback available when diagnostic output suppressed");
        System.out.println("NETWORK_PROBE_RESULTS_PASS checks="+checks);
    }
}
