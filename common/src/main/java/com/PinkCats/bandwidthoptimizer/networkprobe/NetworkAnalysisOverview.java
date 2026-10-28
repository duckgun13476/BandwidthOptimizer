package com.PinkCats.bandwidthoptimizer.networkprobe;

import com.google.gson.*;
import java.util.*;

/** Player-facing observations only; never turns TCP retransmissions into a packet-loss percentage. */
final class NetworkAnalysisOverview {
    enum Severity {
        NORMAL(0x55FF55), UNKNOWN(0xAAAAAA), WARNING(0xFFFF55), SEVERE(0xFF5555);
        final int color;
        Severity(int color) {this.color=color;}
    }
    record Row(String key,List<Object> args,Severity severity) {
        Row(String key,List<Object> args) {
            this(key,args,key.equals("bo.network.summary.no_issues")?Severity.NORMAL:
                    key.equals("bo.network.peer_missing") || key.equals("bo.network.incomplete")?Severity.UNKNOWN:Severity.WARNING);
        }
    }
    private NetworkAnalysisOverview() {}

    static Row conclusion(List<Row> rows) {
        Severity severity=rows.stream().map(Row::severity).max(Comparator.naturalOrder()).orElse(Severity.UNKNOWN);
        String key=switch(severity) {
            case NORMAL -> "bo.network.summary.no_issues";
            case UNKNOWN -> "bo.network.incomplete";
            case WARNING -> "bo.network.summary.problems";
            case SEVERE -> "bo.network.summary.severe";
        };
        return new Row(key,List.of(),severity);
    }

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

    static List<Row> rows(JsonObject report) {
        ArrayList<Row> rows=new ArrayList<>();
        for(String key:keys(report)) {
            if(key.equals("bo.network.summary.retransmission")) {
                appendRetransmission(rows,report.getAsJsonObject("analysis"),false);
                JsonObject peer=report.getAsJsonObject("peer_summary");
                if(peer!=null) appendRetransmission(rows,peer.getAsJsonObject("metrics"),true);
            } else if(key.startsWith("bo.network.summary.") && (key.endsWith("_latency") || key.endsWith("_rate") || key.endsWith("_pause"))) {
                JsonObject evidence=evidence(report,key);
                if(evidence==null) {rows.add(new Row("bo.network.summary.other",List.of()));continue;}
                // Keep normal measurements in details; overview uses the same player thresholds as charts.
                if(key.endsWith("_latency") && number(evidence,"loaded_p95_ns")<200_000_000) continue;
                if(key.endsWith("_rate") && number(evidence,"receiver_goodput_bytes_s")>200_000) continue;
                List<Object> args=key.endsWith("_latency")?List.of(format(number(evidence,"baseline_p95_ns")/1e6),format(number(evidence,"loaded_p95_ns")/1e6),format((number(evidence,"loaded_p95_ns")-number(evidence,"baseline_p95_ns"))/1e6)):
                        key.endsWith("_pause")?List.of(format(number(evidence,"no_progress_duration_ns")/1e6)):
                        List.of(format(number(evidence,"receiver_goodput_bytes_s")/1000),format(number(evidence,"configured_sender_rate_bytes_s")/1000));
                rows.add(new Row(key,args));
            } else rows.add(new Row(key,List.of()));
        }
        if(rows.isEmpty()) rows.add(new Row("bo.network.summary.no_issues",List.of()));
        return List.copyOf(rows);
    }
    private static void appendRetransmission(List<Row> rows,JsonObject metrics,boolean server) {
        if(metrics==null) return;
        String prefix=server?"tcp_":"local_tcp_",suffix=server?"":"_delta";
        boolean bytes=positive(metrics,prefix+"retransmitted_bytes"+suffix);
        double retrans=number(metrics,prefix+"retransmitted_"+(bytes?"bytes":"segments")+suffix);
        if(!(retrans>0)) return;
        double sent=number(metrics,prefix+"sent_"+(bytes?"bytes":"segments")+suffix);
        String fraction=sent>=retrans && sent>0?format(100*retrans/sent)+"%":"bo.network.rate_unavailable";
        Severity severity=Double.isFinite(sent) && sent>=retrans && retrans/sent>0.5?Severity.SEVERE:Severity.WARNING;
        rows.add(new Row(server?"bo.network.summary.server_retransmission":"bo.network.summary.client_retransmission",List.of(format(retrans),bytes?"bo.network.unit.bytes":"bo.network.unit.segments",fraction),severity));
    }
    private static String format(double value) {return String.format(Locale.ROOT,"%.2f",value);}
    private static double number(JsonObject o,String key) {return o!=null && o.has(key)?o.get(key).getAsDouble():Double.NaN;}
    private static JsonObject evidence(JsonObject report,String key) {
        JsonObject peer=report.getAsJsonObject("peer_summary");
        for(JsonArray findings:new JsonArray[]{peer==null?null:peer.getAsJsonArray("findings"),report.getAsJsonArray("findings")}) {
            if(findings==null) continue;
            for(JsonElement element:findings) {
                JsonArray single=new JsonArray();single.add(element);Set<String> found=new HashSet<>();append(found,single);
                if(found.contains(key)) return element.getAsJsonObject().getAsJsonObject("evidence");
            }
        }
        return null;
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
