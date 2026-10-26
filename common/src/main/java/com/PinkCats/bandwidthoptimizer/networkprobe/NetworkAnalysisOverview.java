package com.PinkCats.bandwidthoptimizer.networkprobe;

import com.google.gson.*;
import java.util.*;

/** Player-facing observations only; never turns TCP retransmissions into a packet-loss percentage. */
final class NetworkAnalysisOverview {
    private NetworkAnalysisOverview() {}

    static List<String> keys(JsonObject report) {
        LinkedHashSet<String> issues=new LinkedHashSet<>();
        JsonObject peer=report.getAsJsonObject("peer_summary");
        if(retransmitted(report.getAsJsonObject("analysis"),"local_tcp_retransmitted_")
                || peer!=null && retransmitted(peer.getAsJsonObject("metrics"),"tcp_retransmitted_"))
            issues.add("bo.network.summary.retransmission");
        if(peer!=null) append(issues,peer.getAsJsonArray("findings"));
        else issues.add("bo.network.peer_missing");
        append(issues,report.getAsJsonArray("findings"));
        if(!report.has("outcome") || !"completed".equals(report.get("outcome").getAsString()))
            issues.add("bo.network.incomplete");
        if(issues.isEmpty()) issues.add("bo.network.summary.no_issues");
        return List.copyOf(issues);
    }

    private static boolean retransmitted(JsonObject metrics,String prefix) {
        return positive(metrics,prefix+"bytes_delta") || positive(metrics,prefix+"segments_delta")
                || positive(metrics,prefix+"bytes") || positive(metrics,prefix+"segments");
    }
    private static boolean positive(JsonObject metrics,String key) {
        return metrics!=null && metrics.has(key) && metrics.get(key).getAsDouble()>0;
    }
    private static void append(Set<String> issues,JsonArray findings) {
        if(findings==null) return;
        // Findings from the bounded local report and validated peer summary use fixed codes/phases.
        for(JsonElement element:findings) {
            JsonObject finding=element.getAsJsonObject(), evidence=finding.getAsJsonObject("evidence");
            String code=finding.get("code").getAsString();
            String phase=evidence!=null && evidence.has("phase")?evidence.get("phase").getAsString():"";
            String direction=switch(phase) {
                case "RECEIVE_UPLOAD","upload_received" -> "upload";
                case "SEND_DOWNLOAD","RECEIVE_DOWNLOAD","download_received" -> "download";
                default -> "transfer";
            };
            String symptom=switch(code) {
                case "loaded_application_latency_increase" -> "latency";
                case "receive_pause_recovered" -> "pause";
                case "receive_rate_below_configured_sender_rate" -> "rate";
                default -> null;
            };
            issues.add(symptom==null?"bo.network.summary.other":"bo.network.summary."+direction+"_"+symptom);
        }
    }
}
