package com.PinkCats.bandwidthoptimizer.networkprobe;

import com.google.gson.*;
import java.io.*;

/** Fixed binary result schema. Never parses peer JSON, raw windows or arbitrary property names. */
final class ProbeResultSummary {
    private static final String[] METRICS={"started_at_epoch_ms","elapsed_ns","budget_bytes_per_direction","rate_limit_bytes_s",
            "upload_verified_bytes","upload_receive_ns","download_verified_bytes","download_receive_ns",
            "baseline_p50_ns","baseline_p95_ns","upload_p50_ns","upload_p95_ns","download_p50_ns","download_p95_ns",
            "tcp_retransmitted_bytes","tcp_retransmitted_segments","tcp_delta_window_start_ns","tcp_delta_window_end_ns","tcp_sent_bytes","tcp_sent_segments"};
    private static final String[] CODES={"loaded_application_latency_increase","receive_pause_recovered","receive_rate_below_configured_sender_rate"};
    private static final String[] FAILURES={"test_deadline_not_packet_loss","local_event_loop_stall","sender_no_progress",
            "write_failed","operator_cancelled","peer_cancelled","connection_closed_cause_unknown",
            "backend_login_replaced_session","scheduler_closing","protocol_error"};
    private static final String[] PARTIAL={"upload_received_bytes","download_sent_bytes","phase_elapsed_ns"};
    private static final String[] PHASES={"RECEIVE_UPLOAD","SEND_DOWNLOAD","upload_received"};
    private static final String[] VALUES={"baseline_samples","loaded_samples","baseline_p95_ns","loaded_p95_ns",
            "minimum_increase_ns","minimum_ratio","no_progress_start_ns","no_progress_end_ns","resumed_by_ns",
            "no_progress_duration_ns","maximum_sample_gap_ns","minimum_pause_ns","no_progress_sample_intervals",
            "verified_bytes","duration_ns","configured_sender_rate_bytes_s","receiver_goodput_bytes_s",
            "achieved_to_configured_ratio","maximum_ratio"};
    private ProbeResultSummary() {}
    static byte[] encode(JsonObject report) {
        if(!"server".equals(report.get("side").getAsString())) throw new IllegalArgumentException("Only server summaries");
        boolean completed="completed".equals(report.get("outcome").getAsString());
        JsonObject metrics=new JsonObject();
        for(String key:new String[]{"started_at_epoch_ms","elapsed_ns","budget_bytes_per_direction","rate_limit_bytes_s"})
            metrics.add(key,report.get(key));
        JsonObject phases=report.getAsJsonObject("phases"), analysis=report.getAsJsonObject("analysis");
        copy(metrics,"upload_verified_bytes",phases.getAsJsonObject("upload_received"),"verified_bytes");
        copy(metrics,"upload_receive_ns",phases.getAsJsonObject("upload_received"),"receiver_phase_ns");
        copy(metrics,"download_verified_bytes",phases.getAsJsonObject("download_peer_receipt"),"peer_verified_bytes");
        copy(metrics,"download_receive_ns",phases.getAsJsonObject("download_peer_receipt"),"peer_receive_phase_ns");
        JsonObject rtt=analysis.getAsJsonObject("application_rtt_by_phase");
        String[] names={"BASELINE","RECEIVE_UPLOAD","SEND_DOWNLOAD"}, prefixes={"baseline","upload","download"};
        for(int i=0;i<names.length;i++) for(String q:new String[]{"p50_ns","p95_ns"})
            copy(metrics,prefixes[i]+"_"+q,rtt.getAsJsonObject(names[i]),q);
        copy(metrics,"tcp_retransmitted_bytes",analysis,"local_tcp_retransmitted_bytes_delta");
        copy(metrics,"tcp_retransmitted_segments",analysis,"local_tcp_retransmitted_segments_delta");
        copy(metrics,"tcp_delta_window_start_ns",analysis,"tcp_delta_window_start_ns");
        copy(metrics,"tcp_delta_window_end_ns",analysis,"tcp_delta_window_end_ns");
        copy(metrics,"tcp_sent_bytes",analysis,"local_tcp_sent_bytes_delta");
        copy(metrics,"tcp_sent_segments",analysis,"local_tcp_sent_segments_delta");
        try {
            ByteArrayOutputStream bytes=new ByteArrayOutputStream(); DataOutputStream out=new DataOutputStream(bytes);
            out.writeByte(completed?2:3);
            if(!completed) {
                String outcome=report.get("outcome").getAsString();
                int reason=java.util.Arrays.asList(FAILURES).indexOf(outcome);
                out.writeByte(reason<0?FAILURES.length-1:reason);
                out.writeByte(index(new String[]{"NEGOTIATING","BASELINE","RECEIVE_UPLOAD","REST","SEND_DOWNLOAD","WAIT_DOWNLOAD_RECEIPT"},report.get("last_phase").getAsString()));
                for(String key:PARTIAL) out.writeLong(required(report,key));
            }
            for(String key:METRICS) out.writeLong(metrics.has(key)?metrics.get(key).getAsLong():-1);
            JsonArray findings=report.getAsJsonArray("findings");
            if(findings.size()>6) throw new IllegalArgumentException("Too many findings");
            out.writeByte(findings.size());
            for(JsonElement element:findings) {
                JsonObject finding=element.getAsJsonObject(), evidence=finding.getAsJsonObject("evidence");
                out.writeByte(index(CODES,finding.get("code").getAsString()));
                out.writeByte(index(PHASES,evidence.get("phase").getAsString()));
                for(String key:VALUES) out.writeDouble(evidence.has(key)?evidence.get(key).getAsDouble():-1);
            }
            NetworkProbeTrace.write(out,NetworkProbeTrace.build(report));
            out.flush(); byte[] data=bytes.toByteArray();
            decode(data); // Local output must obey the same schema as remote input.
            return data;
        } catch(IOException failure) { throw new IllegalArgumentException(failure); }
    }
    static JsonObject decode(byte[] bytes) {
        if(bytes.length>2048) throw new IllegalArgumentException("Oversized summary");
        try {
            DataInputStream in=new DataInputStream(new ByteArrayInputStream(bytes));
            int version=in.readUnsignedByte();if(version<1 || version>3) throw new IllegalArgumentException("Summary version");
            String outcome="completed",lastPhase=null;
            JsonObject partial=new JsonObject();
            if(version==3) {
                int reason=in.readUnsignedByte(),stage=in.readUnsignedByte();
                String[] stages={"NEGOTIATING","BASELINE","RECEIVE_UPLOAD","REST","SEND_DOWNLOAD","WAIT_DOWNLOAD_RECEIPT"};
                if(reason>=FAILURES.length || stage>=stages.length) throw new IllegalArgumentException("Failure reason/phase");
                outcome=FAILURES[reason];lastPhase=stages[stage];
                for(String key:PARTIAL) {long value=in.readLong();if(value<0) throw new IllegalArgumentException("Negative partial progress");partial.addProperty(key,value);}
            }
            JsonObject metrics=new JsonObject();
            for(String key:METRICS) {
                if(version==1 && (key.equals("tcp_sent_bytes") || key.equals("tcp_sent_segments"))) continue;
                long value=in.readLong(); if(value< -1) throw new IllegalArgumentException("Invalid metric");
                if(value>=0) metrics.addProperty(key,value);
            }
            long budget=required(metrics,"budget_bytes_per_direction"),rate=required(metrics,"rate_limit_bytes_s");
            if(budget<1 || budget>NetworkProbeSession.MAX_BYTES || rate<125000 || rate>16000000
                    || required(metrics,"elapsed_ns")>NetworkProbeSession.MAX_TIME+NetworkProbeSession.SECOND
                    || metrics.has("upload_verified_bytes") && required(metrics,"upload_verified_bytes")>budget
                    || metrics.has("download_verified_bytes") && required(metrics,"download_verified_bytes")>budget)
                throw new IllegalArgumentException("Summary budget");
            required(metrics,"started_at_epoch_ms");
            for(String[] pair:new String[][]{{"upload_verified_bytes","upload_receive_ns"},{"download_verified_bytes","download_receive_ns"}}) {
                if(version<3 || metrics.has(pair[0]) || metrics.has(pair[1]))
                    if(required(metrics,pair[0])<1 || required(metrics,pair[1])<1) throw new IllegalArgumentException("Incomplete transfer summary");
            }
            if(version==3) {
                if(required(partial,"upload_received_bytes")>budget || required(partial,"download_sent_bytes")>budget
                        || required(partial,"phase_elapsed_ns")>required(metrics,"elapsed_ns")
                        || metrics.has("upload_verified_bytes") && required(metrics,"upload_verified_bytes")!=required(partial,"upload_received_bytes")
                        || metrics.has("download_verified_bytes") && required(metrics,"download_verified_bytes")!=required(partial,"download_sent_bytes"))
                    throw new IllegalArgumentException("Partial progress bounds");
                for(String key:PARTIAL) metrics.add(key,partial.get(key));
            }
            for(String key:METRICS) if((key.endsWith("_ns") || key.equals("elapsed_ns")) && metrics.has(key)
                    && metrics.get(key).getAsLong()>NetworkProbeSession.MAX_TIME+NetworkProbeSession.SECOND)
                throw new IllegalArgumentException("Summary duration");
            int count=in.readUnsignedByte(); if(count>6) throw new IllegalArgumentException("Finding count");
            JsonArray findings=new JsonArray();
            for(int i=0;i<count;i++) {
                int code=in.readUnsignedByte(),phase=in.readUnsignedByte();
                if(code>=CODES.length || phase>=PHASES.length || (code<2 && phase!=0 && !(code==0 && phase==1)) || (code==2 && phase!=2))
                    throw new IllegalArgumentException("Finding type/phase");
                JsonObject finding=new JsonObject(),evidence=new JsonObject();
                finding.addProperty("code",CODES[code]); evidence.addProperty("phase",PHASES[phase]);
                for(String key:VALUES) {
                    double value=in.readDouble();
                    if(!Double.isFinite(value) || (value<0 && value!= -1)) throw new IllegalArgumentException("Invalid evidence");
                    if(value>=0) evidence.addProperty(key,value);
                }
                String[][] requiredEvidence={{"baseline_samples","loaded_samples","baseline_p95_ns","loaded_p95_ns","minimum_increase_ns","minimum_ratio"},
                        {"no_progress_start_ns","no_progress_end_ns","resumed_by_ns","no_progress_duration_ns","maximum_sample_gap_ns","minimum_pause_ns","no_progress_sample_intervals"},
                        {"verified_bytes","duration_ns","configured_sender_rate_bytes_s","receiver_goodput_bytes_s","achieved_to_configured_ratio","maximum_ratio"}};
                for(String key:requiredEvidence[code]) if(!evidence.has(key)) throw new IllegalArgumentException("Incomplete finding evidence");
                finding.add("evidence",evidence); findings.add(finding);
            }
            JsonObject trace=version>=2?NetworkProbeTrace.read(in):null;
            if(in.available()!=0) throw new IllegalArgumentException("Trailing summary data");
            JsonObject result=new JsonObject(); result.addProperty("v",1); result.addProperty("side","server");
            result.addProperty("outcome",outcome);if(lastPhase!=null)result.addProperty("last_phase",lastPhase);
            result.addProperty("scope","peer_report; TCP counters cover server local socket only");
            result.add("metrics",metrics); result.add("findings",findings);if(trace!=null) result.add("trace",trace);return result;
        } catch(IOException failure) { throw new IllegalArgumentException("Incomplete summary",failure); }
    }
    private static long required(JsonObject object,String key) {
        if(!object.has(key)) throw new IllegalArgumentException("Missing metric"); return object.get(key).getAsLong();
    }
    private static void copy(JsonObject target,String key,JsonObject source,String field) {
        if(source!=null && source.has(field)) target.add(key,source.get(field));
    }
    private static int index(String[] values,String value) {
        for(int i=0;i<values.length;i++) if(values[i].equals(value)) return i;
        throw new IllegalArgumentException("Unknown summary field");
    }
}
