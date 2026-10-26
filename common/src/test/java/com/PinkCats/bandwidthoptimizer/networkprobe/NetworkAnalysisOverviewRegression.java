package com.PinkCats.bandwidthoptimizer.networkprobe;

import com.google.gson.*;
import java.util.*;

final class NetworkAnalysisOverviewRegression {
    private static int checks;
    private static void check(boolean ok,String message) { checks++; if(!ok) throw new AssertionError(message); }
    static void run() {
        JsonObject report=JsonParser.parseString("{\"outcome\":\"completed\",\"analysis\":{\"local_tcp_retransmitted_bytes_delta\":0},\"peer_summary\":{\"metrics\":{\"tcp_retransmitted_segments\":0}}}").getAsJsonObject();
        check(NetworkAnalysisOverview.keys(report).equals(List.of("bo.network.summary.no_issues")),"healthy overview is one plain conclusion");
        report.getAsJsonObject("peer_summary").getAsJsonObject("metrics").addProperty("tcp_retransmitted_segments",37);
        check(NetworkAnalysisOverview.keys(report).equals(List.of("bo.network.summary.retransmission")),"Linux sender retransmissions remain visible despite client zero");
        report.getAsJsonObject("analysis").addProperty("local_tcp_retransmitted_bytes_delta",3000);
        check(NetworkAnalysisOverview.keys(report).size()==1,"both endpoints deduplicate retransmission issue without loss percentage");
        JsonArray findings=new JsonArray();
        findings.add(finding("loaded_application_latency_increase","SEND_DOWNLOAD"));
        report.getAsJsonObject("peer_summary").add("findings",findings.deepCopy());
        findings.add(finding("receive_rate_below_configured_sender_rate","download_received"));
        report.add("findings",findings);
        check(NetworkAnalysisOverview.keys(report).equals(List.of("bo.network.summary.retransmission","bo.network.summary.download_latency","bo.network.summary.download_rate")),"manual-test symptoms have directions and no duplicate endpoint rows");
        for(String[] sample:new String[][]{{"loaded_application_latency_increase","RECEIVE_UPLOAD","upload_latency"},{"receive_pause_recovered","RECEIVE_UPLOAD","upload_pause"},{"receive_pause_recovered","RECEIVE_DOWNLOAD","download_pause"},{"receive_rate_below_configured_sender_rate","upload_received","upload_rate"}}) {
            findings.add(finding(sample[0],sample[1]));
            check(NetworkAnalysisOverview.keys(report).contains("bo.network.summary."+sample[2]),"direction "+sample[2]);
        }
        report.remove("peer_summary");
        check(NetworkAnalysisOverview.keys(report).contains("bo.network.peer_missing"),"missing server evidence not healthy");
        report.addProperty("outcome","cancelled");
        check(NetworkAnalysisOverview.keys(report).contains("bo.network.incomplete"),"partial report is explicit");
        report=new JsonObject();
        check(!NetworkAnalysisOverview.keys(report).contains("bo.network.summary.no_issues"),"empty report not healthy");
        if(System.getProperty("networkProbeOverviewReport")!=null) {
            try {
                String raw=java.nio.file.Files.readAllLines(java.nio.file.Path.of(System.getProperty("networkProbeOverviewReport"))).stream().filter(s->s.contains("\"peer_summary\"")).reduce((a,b)->b).orElseThrow();
                JsonObject actual=JsonParser.parseString(raw.substring(raw.indexOf('{'))).getAsJsonObject();
                List<String> actualKeys=NetworkAnalysisOverview.keys(actual);
                check(actualKeys.contains("bo.network.summary.retransmission"),"actual manual report retransmissions");
                check(actualKeys.contains("bo.network.summary.download_latency"),"actual manual report download latency");
                check(!actualKeys.contains("bo.network.summary.no_issues"),"actual abnormal report not healthy");
                System.out.println("NETWORK_OVERVIEW_ACTUAL_REPORT "+actualKeys);
            } catch(java.io.IOException ex) { throw new AssertionError(ex); }
        }
        System.out.println("NETWORK_OVERVIEW_PASS checks="+checks);
    }
    private static JsonObject finding(String code,String phase) {
        JsonObject finding=new JsonObject(),evidence=new JsonObject();
        finding.addProperty("code",code); evidence.addProperty("phase",phase); finding.add("evidence",evidence);
        return finding;
    }
}
