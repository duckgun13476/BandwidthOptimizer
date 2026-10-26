package com.PinkCats.bandwidthoptimizer.networkprobe;

import com.google.gson.*;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;
import net.minecraft.util.FormattedCharSequence;
import java.util.*;
import java.time.*;
import java.time.format.DateTimeFormatter;

/** Shared layout and product text; version adapters implement only rendering and widget construction. */
public abstract class NetworkAnalysisScreenBase extends Screen {
    protected record TextAt(FormattedCharSequence text,int x,int y) {}
    protected record RectAt(int x1,int y1,int x2,int y2,int color) {}
    private final Screen parent;
    private int page,ticks;
    private boolean details,charts;
    private JsonObject chartReport;
    private NetworkAnalysisChart.Plot plot;
    private int plotPage=-1,plotWidth,plotHeight;
    private List<Component> text=List.of();
    private Button action,previous,next,detailsToggle;
    protected NetworkAnalysisScreenBase(Screen parent){super(Component.translatable("bo.network.title"));this.parent=parent;}
    @Override protected void init(){
        int w=Math.max(60,Math.min(110,(width-40)/3)),x=(width-w*3-8)/2;
        addRenderableWidget(NetworkAnalysisPlatform.button(x,height-24,w,Component.translatable("gui.done"),this::onClose));
        addRenderableWidget(NetworkAnalysisPlatform.button(x+w+4,height-24,w,Component.translatable("bo.network.copy"),()->{
            StringBuilder copied=new StringBuilder(); for(Component line:text) copied.append(line.getString()).append('\n');
            minecraft.keyboardHandler.setClipboard(copied.toString());
        }));
        action=addRenderableWidget(NetworkAnalysisPlatform.button(x+2*w+8,height-24,w,Component.empty(),()->{
            if(NetworkAnalysisClient.FLOW.active()) NetworkAnalysisClient.cancel(); else NetworkAnalysisClient.retry();
            refresh();
        }));
        int tabW=Math.max(45,Math.min(82,(width-40-12)/4)),tabX=(width-tabW*4-12)/2;
        previous=addRenderableWidget(NetworkAnalysisPlatform.button(tabX,height-48,tabW,Component.literal("<"),()->page=Math.max(0,page-1)));
        addRenderableWidget(NetworkAnalysisPlatform.button(tabX+tabW+4,height-48,tabW,Component.translatable("bo.network.charts"),()->{charts=!charts;details=false;page=0;refresh();}));
        detailsToggle=addRenderableWidget(NetworkAnalysisPlatform.button(tabX+2*tabW+8,height-48,tabW,Component.translatable("bo.network.details"),()->{details=!details;charts=false;page=0;refresh();}));
        next=addRenderableWidget(NetworkAnalysisPlatform.button(tabX+3*tabW+12,height-48,tabW,Component.literal(">"),()->page++));
        refresh();
    }
    @Override public void tick(){if(++ticks%10==0) refresh();}
    private static String number(double value){return String.format(Locale.ROOT,"%.2f",value);}
    private static double value(JsonObject o,String key){return o!=null && o.has(key)?o.get(key).getAsDouble():Double.NaN;}
    private static Component metric(String label,double value,String unit){return Component.translatable("bo.network.metric",Component.translatable(label),Double.isFinite(value)?number(value):Component.translatable("bo.network.unmeasured"),unit);}
    private void refresh(){
        NetworkAnalysisFlow flow=NetworkAnalysisClient.FLOW;
        ArrayList<Component> lines=new ArrayList<>(); lines.add(title);
        String state=flow.state(); String known=switch(state){
            case "idle","waiting_multiplayer","waiting_world","stabilizing","queued","waiting_for_authorization","running","completed","cancelled","connection_changed","unsupported","world_not_ready","server_busy_or_cooldown","server_disabled","authorization_timeout","busy_or_cooldown","already_running" -> state;
            default -> "failed";
        };
        lines.add(Component.translatable("bo.network.state."+known));
        NetworkProbeProgress progress=flow.progress();
        if(flow.active() && progress!=null) {
            lines.add(Component.translatable("bo.network.phase",Component.translatable("bo.network.phase."+progress.phase())));
            lines.add(Component.translatable("bo.network.progress",number(progress.sentBytes()/1e6),number(progress.receivedBytes()/1e6),number(progress.budgetBytes()/1e6)));
        }
        var saved=flow.result();
        chartReport=null;plot=null;
        if(saved!=null) {
            JsonObject report=saved.json();
            chartReport=report;
            if(report.has("result_status")) lines.add(Component.translatable("bo.network.incomplete"));
            else {
                if(report.has("outcome") && !report.get("outcome").getAsString().equals("completed") && report.has("disconnect_category")) {
                    String observed=switch(report.get("disconnect_category").getAsString()) {
                        case "CONNECTION_RESET" -> "bo.network.connection_reset";
                        case "BROKEN_PIPE" -> "bo.network.broken_pipe";
                        case "READ_TIMEOUT" -> "bo.network.read_timeout";
                        default -> null;
                    };
                    if(observed!=null) lines.add(Component.translatable(observed));
                }
                for(var row:NetworkAnalysisOverview.rows(report)) {
                    Object[] args=row.args().stream().map(arg->arg instanceof String key && key.startsWith("bo.network.")?Component.translatable(key):arg).toArray();
                    lines.add(Component.translatable(row.key(),args));
                }
                if(details) appendDetails(lines,report);
            }
        } else if(state.equals("completed")) lines.add(Component.translatable("bo.network.incomplete"));
        if(details) lines.add(Component.translatable("bo.network.scope"));
        text=List.copyOf(lines);
        detailsToggle.setMessage(Component.translatable(details?"bo.network.overview":"bo.network.details"));
        action.setMessage(Component.translatable(flow.active()?"bo.network.cancel":flow.result()==null?"bo.network.start":"bo.network.retry"));
        action.active=true;
    }
    private void appendDetails(List<Component> lines,JsonObject report) {
        if(report.has("started_at_epoch_ms")) lines.add(Component.translatable("bo.network.started",DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss").withZone(ZoneId.systemDefault()).format(Instant.ofEpochMilli(report.get("started_at_epoch_ms").getAsLong()))));
        if(report.has("finished_at_epoch_ms")) lines.add(Component.translatable("bo.network.finished_at",DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss").withZone(ZoneId.systemDefault()).format(Instant.ofEpochMilli(report.get("finished_at_epoch_ms").getAsLong()))));
        if(report.has("elapsed_ns")) lines.add(metric("bo.network.duration",value(report,"elapsed_ns")/1e9,"s"));
        lines.add(metric("bo.network.budget",value(report,"budget_bytes_per_direction")/1e6,"MB"));
        lines.add(metric("bo.network.rate",value(report,"rate_limit_bytes_s")*8/1e6,"Mbit/s"));
        JsonObject phases=report.getAsJsonObject("phases"),analysis=report.getAsJsonObject("analysis");
        if(phases!=null) {
            JsonObject up=phases.getAsJsonObject("upload_peer_receipt"),down=phases.getAsJsonObject("download_received");
            lines.add(metric("bo.network.upload",value(up,"peer_receive_goodput_mbit_s"),"Mbit/s"));
            lines.add(metric("bo.network.download",value(down,"receiver_goodput_mbit_s"),"Mbit/s"));
        }
        if(analysis!=null) appendTcpMetrics(lines,analysis,"local_tcp_retransmitted_bytes_delta","local_tcp_retransmitted_segments_delta",false);
        appendFindings(lines,report.getAsJsonArray("findings"),"bo.network.local");
        JsonObject peer=report.getAsJsonObject("peer_summary");
        if(peer!=null) {
            appendFindings(lines,peer.getAsJsonArray("findings"),"bo.network.server");
            JsonObject metrics=peer.getAsJsonObject("metrics");
            lines.add(metric("bo.network.server_baseline",value(metrics,"baseline_p95_ns")/1e6,"ms"));
            lines.add(metric("bo.network.server_upload_rtt",value(metrics,"upload_p95_ns")/1e6,"ms"));
            lines.add(metric("bo.network.server_download_rtt",value(metrics,"download_p95_ns")/1e6,"ms"));
            appendTcpMetrics(lines,metrics,"tcp_retransmitted_bytes","tcp_retransmitted_segments",true);
        }
    }
    private static void appendTcpMetrics(List<Component> lines,JsonObject metrics,String bytesKey,String segmentsKey,boolean server){
        if(metrics==null || !metrics.has(segmentsKey) || metrics.has(bytesKey))
            lines.add(metric(server?"bo.network.server_retransmitted":"bo.network.retransmitted",value(metrics,bytesKey),"B"));
        if(metrics!=null && metrics.has(segmentsKey))
            lines.add(metric(server?"bo.network.server_retransmitted_segments":"bo.network.retransmitted_segments",value(metrics,segmentsKey),""));
        if(value(metrics,bytesKey)>0 || value(metrics,segmentsKey)>0)
            lines.add(Component.translatable("bo.network.retransmission_observed"));
    }
    private void appendFindings(List<Component> lines,JsonArray findings,String source){
        lines.add(Component.translatable(source));
        if(findings==null || findings.isEmpty()) {lines.add(Component.translatable("bo.network.no_findings"));return;}
        for(JsonElement element:findings) {
            JsonObject f=element.getAsJsonObject(),e=f.getAsJsonObject("evidence"); String code=f.get("code").getAsString();
            lines.add(Component.translatable("bo.network.finding."+code));
            if(details && e!=null) switch(code) {
                case "loaded_application_latency_increase" -> lines.add(Component.translatable("bo.network.evidence.rtt",number(value(e,"baseline_p95_ns")/1e6),number(value(e,"loaded_p95_ns")/1e6)));
                case "receive_pause_recovered" -> lines.add(Component.translatable("bo.network.evidence.pause",number(value(e,"no_progress_duration_ns")/1e6)));
                case "receive_rate_below_configured_sender_rate" -> lines.add(Component.translatable("bo.network.evidence.rate",number(value(e,"receiver_goodput_bytes_s")*8/1e6),number(value(e,"configured_sender_rate_bytes_s")*8/1e6)));
            }
        }
    }
    protected List<TextAt> layout(){
        if(charts) {
            preparePlot();ArrayList<TextAt> labels=new ArrayList<>();
            for(var label:plot.labels()) labels.add(new TextAt(Component.translatable(label.key(),label.args().toArray()).getVisualOrderText(),label.x(),label.y()));
            return labels;
        }
        ArrayList<FormattedCharSequence> wrapped=new ArrayList<>();
        for(Component line:text) wrapped.addAll(font.split(line,Math.max(80,width-40)));
        int perPage=Math.max(1,(height-70)/12),pages=Math.max(1,(wrapped.size()+perPage-1)/perPage);
        page=Math.min(page,pages-1); ArrayList<TextAt> drawn=new ArrayList<>();
        previous.active=page>0;next.active=page<pages-1;next.setMessage(Component.literal((page+1)+"/"+pages+" >"));
        for(int i=page*perPage;i<Math.min(wrapped.size(),(page+1)*perPage);i++) drawn.add(new TextAt(wrapped.get(i),20,12+(i-page*perPage)*12));
        return drawn;
    }
    private void preparePlot(){
        page=Math.max(0,Math.min(2,page));previous.active=page>0;next.active=page<2;next.setMessage(Component.literal((page+1)+"/3 >"));
        if(plot==null || plotPage!=page || plotWidth!=width || plotHeight!=height) {
            plot=NetworkAnalysisChart.plot(chartReport,page,width,height);plotPage=page;plotWidth=width;plotHeight=height;
        }
    }
    protected List<RectAt> chartRects(){
        if(!charts) return List.of();preparePlot();
        return plot.boxes().stream().map(b->new RectAt(b.x1(),b.y1(),b.x2(),b.y2(),b.color())).toList();
    }
    @Override public void onClose(){NetworkAnalysisPlatform.screen(parent);}
    @Override public boolean isPauseScreen(){return false;}
}
