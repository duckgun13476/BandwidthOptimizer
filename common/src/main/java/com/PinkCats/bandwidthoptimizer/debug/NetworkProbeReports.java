package com.PinkCats.bandwidthoptimizer.debug;

import com.PinkCats.bandwidthoptimizer.util.BandwidthOptimizerOutputPaths;
import com.google.gson.JsonObject;

/** All network-test diagnostics enter through this facade, including bounded detailed files. */
public final class NetworkProbeReports {
    private NetworkProbeReports() {}
    public static void record(JsonObject report) {
        String message="v=1 domain=network event=network_probe_summary";
        if(!DiagnosticLog.acquireFileEvent(DiagnosticToolRegistry.Tool.NETWORK_PROBE,message)) return;
        try {
            report.addProperty("writer_dropped_lines",Holder.WRITER.droppedLines());
            Holder.WRITER.offer("[BO.diag] "+message+" "+report);
        } catch(RuntimeException | LinkageError unavailable) {
            DiagnosticLog.debug(DiagnosticToolRegistry.Tool.NETWORK_PROBE,
                    "v=1 domain=network event=network_probe_report_unavailable error={}",unavailable.getClass().getName());
        }
    }
    private static final class Holder {
        static final BoundedDiagnosticFileWriter WRITER=new BoundedDiagnosticFileWriter(
                BandwidthOptimizerOutputPaths.resolve("diagnostics/network","probes.jsonl").toAbsolutePath().normalize(),
                "network-probes",32,256*1024L,1024*1024L,8);
        static { Runtime.getRuntime().addShutdownHook(new Thread(WRITER::close,"bo-network-report-close")); }
    }
}
