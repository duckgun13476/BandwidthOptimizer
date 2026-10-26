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
        JsonObject numeric=JsonParser.parseString("{\"outcome\":\"completed\",\"analysis\":{\"local_tcp_retransmitted_bytes_delta\":1250,\"local_tcp_sent_bytes_delta\":50000},\"peer_summary\":{\"metrics\":{\"tcp_retransmitted_segments\":5,\"tcp_sent_segments\":100}},\"findings\":[]}").getAsJsonObject();
        expectRow(numeric,"bo.network.summary.client_retransmission",List.of("1250.00","bo.network.unit.bytes","2.50%"),"Windows byte retransmission rate uses sent-byte denominator");
        expectRow(numeric,"bo.network.summary.server_retransmission",List.of("5.00","bo.network.unit.segments","5.00%"),"Linux segment retransmission rate uses sent-segment denominator");
        numeric.getAsJsonObject("analysis").remove("local_tcp_sent_bytes_delta");
        numeric.getAsJsonObject("peer_summary").getAsJsonObject("metrics").remove("tcp_sent_segments");
        expectRow(numeric,"bo.network.summary.client_retransmission",List.of("1250.00","bo.network.unit.bytes","bo.network.rate_unavailable"),"missing Windows denominator does not fabricate a rate");
        expectRow(numeric,"bo.network.summary.server_retransmission",List.of("5.00","bo.network.unit.segments","bo.network.rate_unavailable"),"missing Linux denominator does not fabricate a rate");

        JsonObject measured=JsonParser.parseString("{\"outcome\":\"completed\",\"analysis\":{},\"peer_summary\":{\"metrics\":{},\"findings\":[]},\"findings\":[]}").getAsJsonObject();
        measured.getAsJsonArray("findings").add(findingWithEvidence("loaded_application_latency_increase","RECEIVE_DOWNLOAD",40_000_000L,250_000_000L,0,0));
        measured.getAsJsonArray("findings").add(findingWithEvidence("receive_rate_below_configured_sender_rate","download_received",0,0,510_000,1_000_000));
        expectRow(measured,"bo.network.summary.download_latency",List.of("40.00","250.00","210.00"),"latency row reports actual, baseline, and increase");
        check(NetworkAnalysisOverview.rows(measured).stream().noneMatch(row->row.key().equals("bo.network.summary.download_rate")),"510 KB/s above the green threshold is omitted from overview rows");
        measured.getAsJsonArray("findings").get(1).getAsJsonObject().getAsJsonObject("evidence").addProperty("receiver_goodput_bytes_s",150_000);
        expectRow(measured,"bo.network.summary.download_rate",List.of("150.00","1000.00"),"low rate row reports measured receive rate and configured target");
        JsonObject ordinaryLatency=JsonParser.parseString("{\"outcome\":\"completed\",\"analysis\":{},\"peer_summary\":{\"metrics\":{},\"findings\":[]},\"findings\":[]}").getAsJsonObject();
        ordinaryLatency.getAsJsonArray("findings").add(findingWithEvidence("loaded_application_latency_increase","RECEIVE_DOWNLOAD",40_000_000L,60_000_000L,0,0));
        check(NetworkAnalysisOverview.rows(ordinaryLatency).stream().noneMatch(row->row.key().equals("bo.network.summary.download_latency")),"normal 60 ms loaded latency stays out of overview regardless of baseline increase");
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
    private static JsonObject findingWithEvidence(String code,String phase,long baseline,long loaded,long received,long configured) {
        JsonObject finding=finding(code,phase),evidence=finding.getAsJsonObject("evidence");
        evidence.addProperty("baseline_p95_ns",baseline); evidence.addProperty("loaded_p95_ns",loaded);
        evidence.addProperty("receiver_goodput_bytes_s",received); evidence.addProperty("configured_sender_rate_bytes_s",configured);
        return finding;
    }
    private static void expectRow(JsonObject report,String key,List<?> expected,String message) {
        check(NetworkAnalysisOverview.rows(report).stream().anyMatch(row->row.key().equals(key)&&row.args().equals(expected)),message);
    }
}
