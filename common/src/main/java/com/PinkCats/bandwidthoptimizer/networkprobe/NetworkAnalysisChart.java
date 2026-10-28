package com.PinkCats.bandwidthoptimizer.networkprobe;

import com.google.gson.*;
import java.util.*;

/** Bounded chart geometry shared by all render APIs. Thresholds use decimal KB/s. */
final class NetworkAnalysisChart {
    static final int GREEN=0xFF62CD81,YELLOW=0xFFFFCF55,RED=0xFFFF6666;
    record Box(int x1,int y1,int x2,int y2,int color) {}
    record Label(String key,int x,int y,List<Object> args) {}
    record Sample(int x,int y,int timeMs,double value,String unit,String source,String phase) {}
    record Plot(List<Box> boxes,List<Label> labels,List<Sample> samples,int left,int top,int right,int bottom) {
        Sample hover(int mouseX,int mouseY) {
            if(mouseX<left || mouseX>right || mouseY<top || mouseY>bottom) return null;
            Sample nearest=null;long best=Long.MAX_VALUE;
            for(Sample sample:samples) {
                int dx=Math.abs(sample.x()-mouseX);
                if(dx>10) continue;
                long distance=dx*dx+(long)(sample.y()-mouseY)*(sample.y()-mouseY);
                if(distance<best){nearest=sample;best=distance;}
            }
            return nearest;
        }
    }
    static int color(int kind,double value) {
        return kind==0?(value>300?RED:value>=200?YELLOW:GREEN):kind==1?(value<100?RED:value<=200?YELLOW:GREEN):(value>0?RED:GREEN);
    }
    static Plot plot(JsonObject report,int kind,int width,int height) {
        ArrayList<Box> boxes=new ArrayList<>();ArrayList<Label> labels=new ArrayList<>();ArrayList<Sample> samples=new ArrayList<>();
        JsonObject peer=report==null?null:report.getAsJsonObject("peer_summary"),local=report==null?null:report.getAsJsonObject("trace");
        JsonObject remote=peer==null?null:peer.getAsJsonObject("trace");
        int w=Math.max(30,Math.min(300,width-90)),h=Math.max(25,Math.min(75,height-160));
        int x=(width-w)/2,y=48;
        String key=switch(kind){case 0->"bo.network.chart.latency";case 1->"bo.network.chart.speed";default->"bo.network.chart.retransmissions";};
        labels.add(new Label(key,x,24,List.of()));
        JsonObject primary=remote!=null?remote:local;
        List<JsonObject> traces=kind==1?Arrays.asList(remote,local):Collections.singletonList(primary);
        double duration=1,max=kind==0?300:kind==1?200:1;boolean measured=false;
        int column=kind==0?2:kind==1?4:5;
        for(JsonObject trace:traces) if(trace!=null) {
            duration=Math.max(duration,trace.get("duration_ms").getAsDouble());
            for(JsonElement element:trace.getAsJsonArray("points")) {
                double value=element.getAsJsonArray().get(column).getAsDouble();if(kind==1) value/=1000;
                if(value>=0){measured=true;max=Math.max(max,value);}
            }
        }
        max=Math.max(1,max*1.15);
        if(primary!=null) {
            int previous=0;
            for(JsonElement element:primary.getAsJsonArray("points")) {
                JsonArray row=element.getAsJsonArray();int end=x+(int)(w*row.get(0).getAsDouble()/duration),mask=row.get(1).getAsInt();
                int shade=mask==1?0xFF182E42:mask==2?0xFF243A25:mask==8?0xFF342841:0xFF202830;
                boxes.add(new Box(x+previous,y,Math.max(x+previous+1,end),y+h,shade));previous=end-x;
            }
        }
        boxes.add(new Box(x,y,x+1,y+h+1,0xFFBBC5CC));boxes.add(new Box(x,y+h,x+w,y+h+1,0xFFBBC5CC));
        for(int s=0;s<traces.size();s++) {
            JsonObject trace=traces.get(s);if(trace==null) continue;
            int previousX=-1,previousY=0;double previousValue=0;
            for(JsonElement element:trace.getAsJsonArray("points")) {
                JsonArray row=element.getAsJsonArray();double value=row.get(column).getAsDouble();
                if(value<0){previousX=-1;continue;}if(kind==1)value/=1000;
                int px=x+(int)(w*row.get(0).getAsDouble()/duration),py=y+h-(int)(h*value/max);
                boolean server=trace==remote;
                String phase=switch(row.get(1).getAsInt()) {
                    case 1->"bo.network.phase.BASELINE";
                    case 2->server?"bo.network.phase.RECEIVE_UPLOAD":"bo.network.phase.SEND_UPLOAD";
                    case 4->"bo.network.phase.REST";
                    case 8->server?"bo.network.phase.SEND_DOWNLOAD":"bo.network.phase.RECEIVE_DOWNLOAD";
                    case 0->"bo.network.chart.tail";
                    default->"bo.network.chart.mixed";
                };
                String unit=kind==0?"ms":kind==1?"KB/s":trace.get("tcp_unit").getAsInt()==1?"B":"bo.network.chart.unit_segments";
                samples.add(new Sample(px,py,row.get(0).getAsInt(),value,unit,server?"bo.network.server":"bo.network.local",phase));
                if(previousX>=0) {
                    int span=Math.max(1,px-previousX),lastY=previousY;
                    for(int step=0;step<=span;step++) {
                        double fraction=(double)step/span;
                        int lineX=Math.min(px,previousX+step),lineY=previousY+(int)Math.round((py-previousY)*fraction);
                        boxes.add(new Box(lineX,Math.min(lastY,lineY),lineX+1,Math.max(lastY,lineY)+1,color(kind,previousValue+(value-previousValue)*fraction)));
                        lastY=lineY;
                    }
                }
                previousX=px;previousY=py;previousValue=value;
            }
        }
        labels.add(new Label("bo.network.chart.scale",Math.max(4,x-42),y-12,List.of(String.format(Locale.ROOT,"%.0f",max))));
        labels.add(new Label("bo.network.chart.time",x,y+h+5,List.of(String.format(Locale.ROOT,"%.1f",duration/1000))));
        if(!measured) labels.add(new Label("bo.network.chart.no_data",x+4,y+8,List.of()));
        if(kind==2 && primary!=null) labels.add(new Label(primary.get("tcp_unit").getAsInt()==1?"bo.network.chart.bytes":"bo.network.chart.segments",x,y+h+17,List.of()));
        if(kind==1) labels.add(new Label("bo.network.chart.speed_legend",x,y+h+17,List.of()));
        Sample peak=null;
        for(Sample sample:samples) if(peak==null || sample.value()>peak.value()) peak=sample;
        if(peak!=null) labels.add(new Label("bo.network.chart.peak",x,y+h+29,List.of(format(peak.value()),peak.unit(),format(peak.timeMs()/1000.0))));
        return new Plot(List.copyOf(boxes),List.copyOf(labels),List.copyOf(samples),x,y,x+w,y+h);
    }
    private static String format(double value){return String.format(Locale.ROOT,"%.2f",value);}
    static List<Label> hoverLabels(Plot plot,int mouseX,int mouseY) {
        Sample sample=plot.hover(mouseX,mouseY);if(sample==null) return List.of();
        return List.of(new Label("bo.network.chart.sample",plot.left(),plot.bottom()+41,List.of(format(sample.timeMs()/1000.0),format(sample.value()),sample.unit())),
                new Label("bo.network.chart.source_phase",plot.left(),plot.bottom()+53,List.of(sample.source(),sample.phase())));
    }
}
