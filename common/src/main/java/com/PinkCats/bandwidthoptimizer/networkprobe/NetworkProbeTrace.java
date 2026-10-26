package com.PinkCats.bandwidthoptimizer.networkprobe;

import com.google.gson.*;
import java.io.*;

/** Fixed 32 time bins: stage mask, peak RTT, measured receive rate/minimum and retransmit increments. */
final class NetworkProbeTrace {
    static final int BINS=32, MAX_MS=36_000;
    private NetworkProbeTrace() {}
    static JsonObject build(JsonObject report) {
        long duration=Math.max(1,Math.min(MAX_MS,number(report,"elapsed_ns",0)/1_000_000));
        long[][] bins=new long[BINS][8];
        for(long[] bin:bins) {bin[2]=-1;bin[5]=-1;bin[6]=-1;}
        String counter="total_retransmitted_segments";int unit=2;
        JsonObject start=report.getAsJsonObject("tcp_start");
        if(start!=null && start.has("total_retransmitted_bytes")) {counter="total_retransmitted_bytes";unit=1;}
        long previousCounter=number(start,counter,-1);
        JsonObject previous=null;
        JsonArray windows=report.getAsJsonArray("windows");
        if(windows!=null) for(int i=0;i<Math.min(350,windows.size());i++) {
            JsonObject row=windows.get(i).getAsJsonObject();long time=number(row,"elapsed_ns",-1);
            if(time<0 || time/1_000_000>duration) continue;
            long[] bin=bins[index(time/1_000_000,duration)];bin[0]=1;
            String phase=row.has("phase")?row.get("phase").getAsString():"";
            bin[1]|=mask(phase);
            JsonObject tcp=row.getAsJsonObject("tcp");long count=number(tcp,counter,-1);
            if(count>=0 && previousCounter>=0 && count>=previousCounter) bin[6]=Math.max(0,bin[6])+count-previousCounter;
            if(count>=0) previousCounter=count;
            if(previous!=null && (phase.equals("RECEIVE_UPLOAD") || phase.equals("RECEIVE_DOWNLOAD"))
                    && previous.has("phase") && phase.equals(previous.get("phase").getAsString())) {
                long dt=time-number(previous,"elapsed_ns",time),bytes=number(row,"test_received_bytes",-1)-number(previous,"test_received_bytes",-1);
                if(row.has("test_received_bytes") && previous.has("test_received_bytes") && dt>0 && dt<=500_000_000 && bytes>=0) {
                    long rate=bytes*1_000_000_000L/dt;
                    bin[3]+=bytes;bin[4]+=dt;bin[5]=bin[5]<0?rate:Math.min(bin[5],rate);
                }
            }
            previous=row;
        }
        JsonObject terminal=report.getAsJsonObject("tcp_last_observed");long tail=number(terminal,counter,-1);
        if(tail>=previousCounter && previousCounter>=0) {
            long[] bin=bins[index(Math.min(duration,number(report,"tcp_last_observed_elapsed_ns",duration*1_000_000)/1_000_000),duration)];
            bin[0]=1;bin[6]=Math.max(0,bin[6])+tail-previousCounter;
        }
        JsonArray pings=report.getAsJsonArray("application_rtt");
        if(pings!=null) for(int i=0;i<Math.min(350,pings.size());i++) {
            JsonObject ping=pings.get(i).getAsJsonObject();long time=number(ping,"sent_elapsed_ns",-1),rtt=number(ping,"rtt_ns",-1);
            if(time<0 || rtt<0 || time/1_000_000>duration) continue;
            long[] bin=bins[index(time/1_000_000,duration)];bin[0]=1;bin[2]=Math.max(bin[2],rtt/1_000_000);
            if(ping.has("phase")) bin[1]|=mask(ping.get("phase").getAsString());
        }
        JsonArray points=new JsonArray();
        for(int i=0;i<BINS;i++) if(bins[i][0]>0) {
            long[] b=bins[i];JsonArray row=new JsonArray();
            long[] values={Math.min(duration,(i+1)*duration/BINS),b[1],b[2],b[4]>0?b[3]*1_000_000_000L/b[4]:-1,b[5],b[6]};
            for(long value:values) row.add(value);points.add(row);
        }
        JsonObject trace=new JsonObject();trace.addProperty("duration_ms",duration);trace.addProperty("tcp_unit",unit);trace.add("points",points);
        return trace;
    }
    private static int index(long ms,long duration) {return (int)Math.min(BINS-1,Math.max(0,ms)*BINS/Math.max(1,duration));}
    private static int mask(String phase) {return switch(phase) {case "BASELINE" -> 1;case "RECEIVE_UPLOAD","SEND_UPLOAD" -> 2;case "REST" -> 4;case "SEND_DOWNLOAD","RECEIVE_DOWNLOAD" -> 8;default -> 0;};}
    private static long number(JsonObject o,String key,long fallback) {return o!=null && o.has(key) && !o.get(key).isJsonNull()?o.get(key).getAsLong():fallback;}
    static void write(DataOutputStream out,JsonObject trace) throws IOException {
        out.writeShort(trace.get("duration_ms").getAsInt());out.writeByte(trace.get("tcp_unit").getAsInt());
        JsonArray points=trace.getAsJsonArray("points");out.writeByte(points.size());
        for(JsonElement element:points) {JsonArray row=element.getAsJsonArray();out.writeShort(row.get(0).getAsInt());out.writeByte(row.get(1).getAsInt());out.writeShort(row.get(2).getAsInt()<0?65535:row.get(2).getAsInt());for(int i=3;i<6;i++) out.writeInt(row.get(i).getAsInt());}
    }
    static JsonObject read(DataInputStream in) throws IOException {
        int duration=in.readUnsignedShort(),unit=in.readUnsignedByte(),count=in.readUnsignedByte();
        if(duration<1 || duration>MAX_MS || unit<1 || unit>2 || count>BINS) throw new IllegalArgumentException("Trace bounds");
        JsonArray points=new JsonArray();int previous=-1;
        for(int i=0;i<count;i++) {
            int time=in.readUnsignedShort(),phase=in.readUnsignedByte(),rtt=in.readUnsignedShort();
            if(time<previous || time>duration || phase>15 || (rtt!=65535 && rtt>MAX_MS)) throw new IllegalArgumentException("Trace coordinate");
            previous=time;JsonArray row=new JsonArray();row.add(time);row.add(phase);row.add(rtt==65535?-1:rtt);
            for(int j=3;j<6;j++) {int value=in.readInt();if(value< -1 || value>2_000_000_000) throw new IllegalArgumentException("Trace value");row.add(value);}
            if(row.get(3).getAsInt()>=0 && (row.get(4).getAsInt()<0 || row.get(4).getAsInt()>row.get(3).getAsInt())) throw new IllegalArgumentException("Trace rate bounds");
            points.add(row);
        }
        JsonObject trace=new JsonObject();trace.addProperty("duration_ms",duration);trace.addProperty("tcp_unit",unit);trace.add("points",points);return trace;
    }
}
