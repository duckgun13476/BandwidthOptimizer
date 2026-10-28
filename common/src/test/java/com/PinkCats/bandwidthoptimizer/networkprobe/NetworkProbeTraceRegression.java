package com.PinkCats.bandwidthoptimizer.networkprobe;

import com.google.gson.*;
import java.io.*;

final class NetworkProbeTraceRegression {
    private static int checks;
    private static void check(boolean ok,String message){checks++;if(!ok)throw new AssertionError(message);}
    static void run() {
        JsonObject report=new JsonObject(),start=new JsonObject();report.addProperty("elapsed_ns",10_000_000_000L);
        start.addProperty("total_retransmitted_segments",10);report.add("tcp_start",start);
        JsonArray windows=new JsonArray(),pings=new JsonArray();report.add("windows",windows);report.add("application_rtt",pings);
        for(int i=0;i<100;i++) {
            JsonObject row=new JsonObject(),tcp=new JsonObject();row.addProperty("elapsed_ns",i*100_000_000L);row.addProperty("phase",i<10?"BASELINE":i<50?"RECEIVE_UPLOAD":"SEND_DOWNLOAD");
            row.addProperty("test_received_bytes",Math.max(0,i-10)*10_000L);tcp.addProperty("total_retransmitted_segments",10+i);row.add("tcp",tcp);windows.add(row);
            JsonObject ping=new JsonObject();ping.addProperty("sent_elapsed_ns",i*100_000_000L);ping.addProperty("phase",row.get("phase").getAsString());ping.addProperty("rtt_ns",i==47?473_000_000:40_000_000);pings.add(ping);
        }
        JsonObject trace=NetworkProbeTrace.build(report);JsonArray points=trace.getAsJsonArray("points");
        check(points.size()<=32,"bounded downsample");long retrans=0;boolean peak=false,measuredRate=false;
        for(JsonElement element:points) {JsonArray row=element.getAsJsonArray();if(row.get(5).getAsLong()>0)retrans+=row.get(5).getAsLong();peak|=row.get(2).getAsInt()==473;measuredRate|=row.get(4).getAsInt()==100_000;}
        check(retrans==99,"downsampling preserves retransmission increments");check(peak,"RTT spike survives downsampling");check(measuredRate,"receive speed retains measured minimum");
        try {ByteArrayOutputStream bytes=new ByteArrayOutputStream();NetworkProbeTrace.write(new DataOutputStream(bytes),trace);check(bytes.size()<=548,"wire trace fixed budget");check(trace.equals(NetworkProbeTrace.read(new DataInputStream(new ByteArrayInputStream(bytes.toByteArray())))),"trace wire roundtrip");
            byte[] bad=bytes.toByteArray();bad[3]=33;boolean rejected=false;try{NetworkProbeTrace.read(new DataInputStream(new ByteArrayInputStream(bad)));}catch(IllegalArgumentException expected){rejected=true;}check(rejected,"invalid sample count rejected");
        }catch(IOException ex){throw new AssertionError(ex);}
        check(NetworkAnalysisChart.color(0,199)==NetworkAnalysisChart.GREEN,"latency green");check(NetworkAnalysisChart.color(0,200)==NetworkAnalysisChart.YELLOW,"latency yellow start");check(NetworkAnalysisChart.color(0,300)==NetworkAnalysisChart.YELLOW,"latency yellow end");check(NetworkAnalysisChart.color(0,301)==NetworkAnalysisChart.RED,"latency red");
        check(NetworkAnalysisChart.color(1,99)==NetworkAnalysisChart.RED,"speed red");check(NetworkAnalysisChart.color(1,100)==NetworkAnalysisChart.YELLOW,"speed yellow start");check(NetworkAnalysisChart.color(1,200)==NetworkAnalysisChart.YELLOW,"speed yellow end");check(NetworkAnalysisChart.color(1,201)==NetworkAnalysisChart.GREEN,"speed green");check(NetworkAnalysisChart.color(2,1)==NetworkAnalysisChart.RED,"retransmission red");
        JsonObject saved=new JsonObject(),peer=new JsonObject();peer.add("trace",trace);saved.add("peer_summary",peer);
        var plot=NetworkAnalysisChart.plot(saved,0,427,240);check(plot.boxes().stream().anyMatch(b->b.color()==NetworkAnalysisChart.RED),"actual spike chart paints red");check(plot.boxes().size()<1024,"bounded line geometry");
        check(plot.labels().stream().noneMatch(l->l.key().contains("thresholds") || l.key().equals("bo.network.chart.stages")),"no color explanation clutter");
        check(plot.boxes().stream().filter(b->b.color()==NetworkAnalysisChart.RED || b.color()==NetworkAnalysisChart.YELLOW || b.color()==NetworkAnalysisChart.GREEN).allMatch(b->b.x2()-b.x1()==1),"lines have no enlarged dot markers");
        var maximum=plot.samples().stream().max(java.util.Comparator.comparingDouble(NetworkAnalysisChart.Sample::value)).orElseThrow();
        check(maximum.value()==473 && maximum.unit().equals("ms") && maximum.source().equals("bo.network.server"),"peak retains measured value unit and source");
        check(plot.labels().stream().anyMatch(l->l.key().equals("bo.network.chart.peak") && l.args().get(0).equals("473.00") && l.args().get(2).equals(String.format(java.util.Locale.ROOT,"%.2f",maximum.timeMs()/1000.0))),"peak annotation uses actual trace window timestamp");
        check(plot.hover(maximum.x(),maximum.y()).equals(maximum),"hover selects real peak sample");
        check(NetworkAnalysisChart.hoverLabels(plot,maximum.x(),maximum.y()).get(0).args().get(1).equals("473.00"),"hover label retains actual measurement");
        check(plot.hover(plot.left()-1,plot.top())==null && plot.hover(plot.left(),plot.bottom()+1)==null,"outside chart has no hover");
        check(plot.samples().size()<=32,"hover model bounded by trace bins");
        JsonObject emptyReport=new JsonObject();
        check(NetworkAnalysisChart.plot(emptyReport,0,427,240).labels().stream().noneMatch(l->l.key().equals("bo.network.chart.peak")),"unmeasured has no invented peak");
        JsonObject gap=JsonParser.parseString("{\"duration_ms\":300,\"points\":[[100,1,40,-1,-1,0],[200,1,-1,-1,-1,0],[300,1,400,-1,-1,0]]}").getAsJsonObject();
        JsonObject gapReport=new JsonObject();gapReport.add("trace",gap);
        check(NetworkAnalysisChart.plot(gapReport,0,427,240).boxes().stream().noneMatch(b->b.color()==NetworkAnalysisChart.RED || b.color()==NetworkAnalysisChart.YELLOW || b.color()==NetworkAnalysisChart.GREEN),"missing sample breaks line without isolated dots");
        System.out.println("NETWORK_TRACE_PASS checks="+checks);
    }
}
