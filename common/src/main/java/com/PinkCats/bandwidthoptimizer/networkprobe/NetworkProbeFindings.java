package com.PinkCats.bandwidthoptimizer.networkprobe;

import com.google.gson.*;

/** Evidence-only rules over a bounded, completed local report. No remote cause attribution. */
final class NetworkProbeFindings {
    private static final long MS=1_000_000L;
    private NetworkProbeFindings() {}

    static JsonArray detect(JsonObject report) {
        JsonArray out=new JsonArray();
        JsonObject analysis=report.getAsJsonObject("analysis");
        JsonObject rtt=analysis.getAsJsonObject("application_rtt_by_phase");
        if(rtt!=null && rtt.has("BASELINE")) {
            JsonObject baseline=rtt.getAsJsonObject("BASELINE");
            long base=number(baseline,"p95_ns");
            if(number(baseline,"samples")>=5 && base>0) {
                for(String phase:new String[]{"RECEIVE_UPLOAD","SEND_DOWNLOAD"}) {
                    if(!rtt.has(phase)) continue;
                    JsonObject loaded=rtt.getAsJsonObject(phase);
                    long value=number(loaded,"p95_ns");
                    if(number(loaded,"samples")>=5 && value-base>20*MS && value>base*1.5) {
                        JsonObject evidence=new JsonObject();
                        evidence.addProperty("phase",phase);
                        evidence.addProperty("baseline_samples",number(baseline,"samples"));
                        evidence.addProperty("loaded_samples",number(loaded,"samples"));
                        evidence.addProperty("baseline_p95_ns",base); evidence.addProperty("loaded_p95_ns",value);
                        evidence.addProperty("minimum_increase_ns",20*MS);
                        evidence.addProperty("minimum_ratio",1.5);
                        add(out,"loaded_application_latency_increase","application_round_trip",evidence);
                    }
                }
            }
        }
        JsonArray windows=report.getAsJsonArray("windows");
        if(windows!=null) {
            JsonObject previous=null;
            long pauseStart=-1, pauseEnd=-1;
            int pauseIntervals=0;
            boolean uploadReported=false, downloadReported=false;
            for(JsonElement element:windows) {
                JsonObject current=element.getAsJsonObject();
                if(!current.has("phase") || !current.has("elapsed_ns") || !current.has("test_received_bytes")) {
                    previous=null; pauseStart=-1; pauseEnd=-1; pauseIntervals=0; continue;
                }
                String phase=current.get("phase").getAsString();
                boolean receiving=phase.equals("RECEIVE_UPLOAD") || phase.equals("RECEIVE_DOWNLOAD");
                long now=number(current,"elapsed_ns"), bytes=number(current,"test_received_bytes");
                boolean contiguous=previous!=null && receiving && phase.equals(previous.get("phase").getAsString())
                        && now>number(previous,"elapsed_ns") && now-number(previous,"elapsed_ns")<=250*MS;
                if(!contiguous || bytes<number(previous,"test_received_bytes")) {
                    pauseStart=-1; pauseEnd=-1; pauseIntervals=0;
                } else if(bytes==number(previous,"test_received_bytes")) {
                    if(pauseStart<0) pauseStart=number(previous,"elapsed_ns");
                    pauseEnd=now;
                    pauseIntervals++;
                } else {
                    boolean alreadyReported=phase.equals("RECEIVE_UPLOAD")?uploadReported:downloadReported;
                    if(!alreadyReported && pauseStart>=0 && pauseEnd-pauseStart>=500*MS) {
                        JsonObject evidence=new JsonObject(); evidence.addProperty("phase",phase);
                        evidence.addProperty("no_progress_start_ns",pauseStart);
                        evidence.addProperty("no_progress_end_ns",pauseEnd);
                        evidence.addProperty("resumed_by_ns",now);
                        evidence.addProperty("no_progress_duration_ns",pauseEnd-pauseStart);
                        evidence.addProperty("maximum_sample_gap_ns",250*MS);
                        evidence.addProperty("minimum_pause_ns",500*MS);
                        evidence.addProperty("no_progress_sample_intervals",pauseIntervals);
                        add(out,"receive_pause_recovered","local_probe_receive_progress",evidence);
                        // At most one pause finding per receive phase; samples remain available for details.
                        if(phase.equals("RECEIVE_UPLOAD")) uploadReported=true; else downloadReported=true;
                    }
                    pauseStart=-1; pauseEnd=-1; pauseIntervals=0;
                }
                previous=current;
            }
        }
        JsonObject phases=report.getAsJsonObject("phases");
        long offered=number(report,"rate_limit_bytes_s");
        if(phases!=null && offered>0) {
            for(String key:new String[]{"upload_received","download_received"}) {
                if(!phases.has(key)) continue;
                JsonObject phase=phases.getAsJsonObject(key);
                long duration=number(phase,"receiver_phase_ns"), bytes=number(phase,"verified_bytes");
                if(duration<1_000*MS || bytes<65_536) continue;
                double achieved=bytes*1_000_000_000.0/duration, ratio=achieved/offered;
                if(ratio<.7) {
                    JsonObject evidence=new JsonObject(); evidence.addProperty("phase",key);
                    evidence.addProperty("verified_bytes",bytes); evidence.addProperty("duration_ns",duration);
                    evidence.addProperty("configured_sender_rate_bytes_s",offered);
                    evidence.addProperty("receiver_goodput_bytes_s",achieved);
                    evidence.addProperty("achieved_to_configured_ratio",ratio);
                    evidence.addProperty("maximum_ratio",.7);
                    add(out,"receive_rate_below_configured_sender_rate","local_probe_receive_progress",evidence);
                }
            }
        }
        return out;
    }
    private static long number(JsonObject object,String key) {
        return object==null || !object.has(key)?-1:object.get(key).getAsLong();
    }
    private static void add(JsonArray out,String code,String scope,JsonObject evidence) {
        JsonObject finding=new JsonObject(); finding.addProperty("code",code);
        finding.addProperty("scope",scope); finding.add("evidence",evidence); out.add(finding);
    }
}
