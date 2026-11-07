package com.PinkCats.bandwidthoptimizer.networkprobe;

public final class LatencyProbeWindowRegressionMain {
    public static void main(String[] args) {
        long start=1_000_000_000L;
        LatencyProbeWindow window=new LatencyProbeWindow(start,6);
        long[] millis={80,250,380,90,0,300};
        for(int i=0;i<6;i++) {
            long now=start+i*LatencyProbeWindow.INTERVAL;
            check(window.due(now)==i,"sample schedule");
            if(millis[i]>0) check(window.reply(i,now,now+millis[i]*1_000_000),"reply");
            check(!window.reply(i,now+1,now+1),"mismatched stamp");
        }
        check(!window.reply(0,start,start+900_000_000),"duplicate");
        check(!window.finished(start+3_000_000_000L),"wait for outstanding reply");
        check(window.finished(start+7_000_000_000L),"bounded timeout");
        var result=window.result(start+7_000_000_000L,false);
        check(result.replies()==5 && result.timedOut()==1,"reply and timeout accounting");
        check(result.average()==220_000_000L && result.minimum()==80_000_000L && result.maximum()==380_000_000L,"average excludes timeout");
        check(result.spikes().size()==2 && result.spikes().get(0).samples()==2,"consecutive spikes merged");
        check(result.spikes().get(0).from()==500_000_000L && result.spikes().get(0).to()==1_000_000_000L,"spike timing");
        LatencyProbeWindow boundary=new LatencyProbeWindow(start,1);
        boundary.due(start);check(!boundary.reply(0,start,start+LatencyProbeWindow.TIMEOUT),"late reply excluded");
        var partial=boundary.result(start+1_000_000_000L,true);
        check(partial.incomplete() && partial.timedOut()==0 && partial.replies()==0,"pending is not timeout");
        LatencyProbeWindow exact=new LatencyProbeWindow(start,1);exact.due(start);exact.reply(0,start,start+200_000_000L);
        check(exact.result(start+500_000_000L,false).spikes().isEmpty(),"strict high threshold");
        check(!exact.finished(start+200_000_000L) && exact.finished(start+500_000_000L),"fixed measurement interval");
        var channel=new io.netty.channel.embedded.EmbeddedChannel();
        for(int i=0;i<240;i++) check(LatencyProbeService.allowEcho(channel,start),"coalesced echo burst");
        check(!LatencyProbeService.allowEcho(channel,start),"echo budget bounded");
        check(LatencyProbeService.allowEcho(channel,start+1_000_000_000L),"echo budget renews");
        LatencyProbeService.closed(channel);channel.finishAndReleaseAll();
        var channels=new java.util.ArrayList<io.netty.channel.embedded.EmbeddedChannel>();
        var statuses=new java.util.ArrayList<String>();
        for(int i=0;i<33;i++) {
            var active=new io.netty.channel.embedded.EmbeddedChannel();channels.add(active);
            LatencyProbeService.start(active,240,statuses::add,r -> {});active.runPendingTasks();
        }
        check(statuses.stream().filter("started"::equals).count()==32 && statuses.get(32).equals("busy"),"global admission bound");
        var duplicate=new java.util.ArrayList<String>();
        LatencyProbeService.start(channels.get(0),5,duplicate::add,r -> {});channels.get(0).runPendingTasks();
        check(duplicate.equals(java.util.List.of("busy")),"duplicate admission");
        for(var active:channels) {LatencyProbeService.closed(active);active.finishAndReleaseAll();}
        var reopened=new io.netty.channel.embedded.EmbeddedChannel();var restarted=new java.util.ArrayList<String>();
        LatencyProbeService.start(reopened,5,restarted::add,r -> {});reopened.runPendingTasks();
        check(restarted.equals(java.util.List.of("started")),"all reservations released on close");
        LatencyProbeService.closed(reopened);reopened.finishAndReleaseAll();
        var targets=new java.util.ArrayList<Integer>();for(int i=0;i<75;i++) targets.add(i);
        var callbacks=new java.util.HashMap<Integer,Runnable>();var launched=new java.util.ArrayList<Integer>();
        int[] active={0},peak={0},finished={0};
        var batch=new LatencyProbeBatch<Integer>(targets,16,(target,done) -> {
            active[0]++;peak[0]=Math.max(peak[0],active[0]);launched.add(target);
            callbacks.put(target,() -> {active[0]--;done.run();});
        },() -> finished[0]++);
        batch.start();check(launched.size()==16,"batch initial bound");
        for(int i=0;i<75;i++) {Runnable done=callbacks.get(i);check(done!=null,"batch did not launch target "+i);done.run();}
        check(peak[0]==16 && launched.size()==75 && finished[0]==1 && active[0]==0,"all batch targets completed once");
        int[] immediate={0};
        new LatencyProbeBatch<>(targets,16,(target,done) -> {immediate[0]++;done.run();},() -> immediate[0]++).start();
        check(immediate[0]==76,"synchronous failure queue does not recurse or stall");
        System.out.println("PASS latency list, fixed window, aggregate, spike intervals, timeout, duplicate and partial cases");
    }
    private static void check(boolean passed,String message) {if(!passed) throw new AssertionError(message);}
}
