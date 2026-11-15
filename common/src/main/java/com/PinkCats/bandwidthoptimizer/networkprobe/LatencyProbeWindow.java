package com.PinkCats.bandwidthoptimizer.networkprobe;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

/** Fixed-size RTT observations. All time values are monotonic nanoseconds. */
final class LatencyProbeWindow {
    static final long INTERVAL=500_000_000L, TIMEOUT=5_000_000_000L, HIGH=200_000_000L;
    final long started;
    final long[] sent, rtt;
    int count;
    LatencyProbeWindow(long started,int samples) {
        if(samples<1 || samples>240) throw new IllegalArgumentException("Invalid latency budget");
        this.started=started; sent=new long[samples]; rtt=new long[samples];
    }
    int due(long now) {
        if(count==sent.length || now-started<count*INTERVAL) return -1;
        int index=count++; sent[index]=now; return index;
    }
    boolean reply(int index,long stamp,long now) {
        if(index<0 || index>=count || sent[index]!=stamp || rtt[index]!=0) return false;
        long elapsed=now-stamp;
        if(elapsed<0 || elapsed>=TIMEOUT) return false;
        rtt[index]=Math.max(1,elapsed); return true;
    }
    boolean finished(long now) {
        if(count!=sent.length || now-started<sent.length*INTERVAL) return false;
        for(int i=0;i<count;i++) if(rtt[i]==0 && now-sent[i]<TIMEOUT) return false;
        return true;
    }
    record Spike(long from,long to,long peak,int samples) {}
    record Result(long duration,List<Long> samples,int sent,int replies,int timedOut,long average,long minimum,long maximum,List<Spike> spikes,boolean incomplete) {}

    /** Keep the eight highest peaks for both task JSON and command feedback. */
    static List<Spike> highestSpikes(List<Spike> spikes) {
        return spikes.stream().sorted(Comparator.comparingLong(Spike::peak).reversed()).limit(8).toList();
    }

    Result result(long now,boolean incomplete) {
        List<Long> samples=new ArrayList<>(); List<Spike> spikes=new ArrayList<>();
        long total=0,min=Long.MAX_VALUE,max=0,from=0,to=0,peak=0; int replies=0,highCount=0,timeouts=0;
        for(int i=0;i<count;i++) {
            long value=rtt[i]; samples.add(value);
            if(value>0) { replies++;total+=value;min=Math.min(min,value);max=Math.max(max,value); }
            else if(now-sent[i]>=TIMEOUT) timeouts++;
            if(value>HIGH) {
                if(highCount==0) from=sent[i]-started;
                to=sent[i]-started;peak=Math.max(peak,value);highCount++;
            } else if(highCount>0) {
                spikes.add(new Spike(from,to,peak,highCount));highCount=0;peak=0;
            }
        }
        if(highCount>0) spikes.add(new Spike(from,to,peak,highCount));
        return new Result(Math.min(now-started,sent.length*INTERVAL),List.copyOf(samples),count,replies,timeouts,replies==0?0:total/replies,
                replies==0?0:min,max,List.copyOf(spikes),incomplete);
    }
}
