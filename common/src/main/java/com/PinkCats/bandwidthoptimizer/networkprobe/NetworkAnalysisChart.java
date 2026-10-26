package com.PinkCats.bandwidthoptimizer.networkprobe;

import com.google.gson.*;
import java.util.*;

/** Bounded chart geometry shared by all render APIs. Thresholds use decimal KB/s. */
final class NetworkAnalysisChart {
    static final int GREEN=0xFF62CD81,YELLOW=0xFFFFCF55,RED=0xFFFF6666;
    record Box(int x1,int y1,int x2,int y2,int color) {}
    record Label(String key,int x,int y,List<Object> args) {}
    record Plot(List<Box> boxes,List<Label> labels) {}
    static int color(int kind,double value) {
        return kind==0?(value>300?RED:value>=200?YELLOW:GREEN):kind==1?(value<100?RED:value<=200?YELLOW:GREEN):(value>0?RED:GREEN);
    }
    static Plot plot(JsonObject report,int kind,int width,int height) {
        ArrayList<Box> boxes=new ArrayList<>();ArrayList<Label> labels=new ArrayList<>();
        JsonObject peer=report==null?null:report.getAsJsonObject("peer_summary"),local=report==null?null:report.getAsJsonObject("trace");
        JsonObject remote=peer==null?null:peer.getAsJsonObject("trace");
        int x=45,y=58,w=Math.max(30,width-65),h=Math.max(25,height-133);
        String key=switch(kind){case 0->"bo.network.chart.latency";case 1->"bo.network.chart.speed";default->"bo.network.chart.retransmissions";};
        labels.add(new Label(key,20,12,List.of()));
        labels.add(new Label("bo.network.chart.stages",20,26,List.of()));
        labels.add(new Label("bo.network.chart.thresholds."+kind,20,40,List.of()));
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
        double[] thresholds=kind==0?new double[]{200,300}:kind==1?new double[]{100,200}:new double[0];
        for(double threshold:thresholds) {int py=y+h-(int)(h*threshold/max);boxes.add(new Box(x,py,x+w,py+1,threshold==(kind==0?300:100)?0xFF884848:0xFF8A7845));}
        for(int s=0;s<traces.size();s++) {
            JsonObject trace=traces.get(s);if(trace==null) continue;
            for(JsonElement element:trace.getAsJsonArray("points")) {
                JsonArray row=element.getAsJsonArray();double value=row.get(column).getAsDouble();if(value<0) continue;if(kind==1)value/=1000;
                int px=x+(int)(w*row.get(0).getAsDouble()/duration),py=y+h-(int)(h*value/max),size=traces.size()>1 && s==0?2:1;
                boxes.add(new Box(px-size,py-size,px+size+1,py+size+1,color(kind,value)));
            }
        }
        labels.add(new Label("bo.network.chart.scale",4,y,List.of(String.format(Locale.ROOT,"%.0f",max))));
        labels.add(new Label("bo.network.chart.time",x,y+h+5,List.of(String.format(Locale.ROOT,"%.1f",duration/1000))));
        if(!measured) labels.add(new Label("bo.network.chart.no_data",x+4,y+8,List.of()));
        if(kind==2 && primary!=null) labels.add(new Label(primary.get("tcp_unit").getAsInt()==1?"bo.network.chart.bytes":"bo.network.chart.segments",x,y+h+17,List.of()));
        if(kind==1) labels.add(new Label("bo.network.chart.speed_legend",x,y+h+17,List.of()));
        return new Plot(List.copyOf(boxes),List.copyOf(labels));
    }
}
