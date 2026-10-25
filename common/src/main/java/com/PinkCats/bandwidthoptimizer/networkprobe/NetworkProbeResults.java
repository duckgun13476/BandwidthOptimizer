package com.PinkCats.bandwidthoptimizer.networkprobe;

import com.google.gson.*;
import java.nio.charset.StandardCharsets;
import java.util.*;

/** Bounded product results, independent of diagnostic logging. Accepts local session reports only. */
public final class NetworkProbeResults {
    static final int MAX_RESULTS_PER_ROLE=8, MAX_SUMMARY_BYTES=16*1024;
    public record Key(String channelId,UUID testId,boolean server) {
        public Key { Objects.requireNonNull(channelId); Objects.requireNonNull(testId); }
    }
    /** Immutable JSON ownership; parsing returns a fresh tree to the screen thread. */
    public record Result(Key key,String summary) {
        public JsonObject json() { return JsonParser.parseString(summary).getAsJsonObject(); }
    }
    private static final Store CLIENT=new Store(), SERVER=new Store();
    private NetworkProbeResults() {}

    public static Result find(Key key) { return (key.server()?SERVER:CLIENT).find(key); }
    public static List<Result> history(boolean server) { return (server?SERVER:CLIENT).history(); }
    static void record(Key key,JsonObject report) { (key.server()?SERVER:CLIENT).record(key,report); }

    static final class Store {
        private final LinkedHashMap<Key,Result> results=new LinkedHashMap<>();
        synchronized void record(Key key,JsonObject localReport) {
            if(!key.testId().toString().equals(localReport.get("test_id").getAsString())
                    || !(key.server()?"server":"client").equals(localReport.get("side").getAsString()))
                throw new IllegalArgumentException("Result owner mismatch");
            JsonObject summary=new JsonObject();
            // Raw windows and per-ping history remain in diagnostics, not retained UI state.
            String[] fields={"v","test_id","side","outcome","last_phase","started_at_epoch_ms","finished_at_epoch_ms",
                    "elapsed_ns","budget_bytes_per_direction","rate_limit_bytes_s","test_path","window",
                    "write_backpressure_ticks","max_event_loop_scheduling_delay_ns","last_error_class",
                    "disconnect_category","tcp_start","tcp_end","tcp_last_observed","tcp_last_observed_elapsed_ns",
                    "tcp_terminal_sample_supported","tcp_baseline_elapsed_ns","analysis","phases",
                    "findings_version","findings_scope","findings","peer_summary_status","peer_summary"};
            for(String field:fields) if(localReport.has(field)) summary.add(field,localReport.get(field).deepCopy());
            String text=summary.toString();
            if(text.getBytes(StandardCharsets.UTF_8).length>MAX_SUMMARY_BYTES) {
                // Retain an explicitly incomplete result, never turn missing details into a clean bill of health.
                summary=new JsonObject();
                summary.addProperty("test_id",key.testId().toString());
                summary.addProperty("side",key.server()?"server":"client");
                summary.addProperty("result_status","summary_size_limit");
                text=summary.toString();
            }
            results.remove(key); results.put(key,new Result(key,text));
            while(results.size()>MAX_RESULTS_PER_ROLE) results.remove(results.keySet().iterator().next());
        }
        synchronized Result find(Key key) { return results.get(key); }
        synchronized List<Result> history() { return List.copyOf(results.values()); }
    }
}
