package com.PinkCats.bandwidthoptimizer.networkprobe;

import io.netty.channel.Channel;
import io.netty.util.AttributeKey;
import io.netty.util.concurrent.ScheduledFuture;
import java.util.UUID;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Consumer;

/** Small independent echo probe. State and callbacks are confined to the channel event loop. */
final class LatencyProbeService {
    private static final AttributeKey<Session> SESSION=AttributeKey.valueOf("bo:latency_probe");
    private static final AttributeKey<EchoBudget> ECHO=AttributeKey.valueOf("bo:latency_echo_budget");
    private static final AtomicInteger ACTIVE=new AtomicInteger();
    private static final int MAX_ACTIVE=32;
    private LatencyProbeService() {}
    static void start(Channel channel,int samples,Consumer<String> status,Consumer<LatencyProbeWindow.Result> result) {
        if(samples<1 || samples>240) throw new IllegalArgumentException("Invalid sample count");
        try { channel.eventLoop().execute(() -> {
            if(!channel.isActive()) {status.accept("unavailable");return;}
            if(channel.attr(SESSION).get()!=null) {status.accept("busy");return;}
            if(ACTIVE.incrementAndGet()>MAX_ACTIVE) {ACTIVE.decrementAndGet();status.accept("busy");return;}
            Session session=new Session(channel,samples,result);
            channel.attr(SESSION).set(session);
            try {
                session.task=channel.eventLoop().scheduleWithFixedDelay(session::tick,0,25,TimeUnit.MILLISECONDS);
                status.accept("started");
            } catch(RuntimeException failure) {session.finish(true);}
        }); } catch(java.util.concurrent.RejectedExecutionException closed) {status.accept("unavailable");}
    }
    static boolean receive(Channel channel,boolean server,byte[] bytes) {
        // Cheap dispatch: do not decode or allocate for unrelated throughput frames.
        if(bytes==null || bytes.length<2 || bytes[0]!=3 || (bytes[1]!=ProbeFrame.LATENCY_PING && bytes[1]!=ProbeFrame.LATENCY_PONG)) return false;
        ProbeFrame frame;
        try {frame=ProbeFrame.decode(bytes);} catch(IllegalArgumentException invalid) {return true;}
        if(frame.body().length!=0 || frame.sequence()>=240) return true;
        if(!server && frame.kind()==ProbeFrame.LATENCY_PING) {
            if(!allowEcho(channel,System.nanoTime())) return true;
            NetworkProbePackets.send(channel,false,new ProbeFrame(ProbeFrame.LATENCY_PONG,frame.id(),frame.sequence(),frame.value(),new byte[0]).encode());
        } else if(server && frame.kind()==ProbeFrame.LATENCY_PONG) {
            Session session=channel.attr(SESSION).get();
            if(session!=null && session.id.equals(frame.id())) session.window.reply(frame.sequence(),frame.value(),System.nanoTime());
        }
        return true;
    }
    static void closed(Channel channel) {
        Session session=channel.attr(SESSION).get();if(session!=null) session.finish(true);
        channel.attr(ECHO).set(null);
    }
    static boolean allowEcho(Channel channel,long now) {
        EchoBudget budget=channel.attr(ECHO).get();
        if(budget==null) {budget=new EchoBudget();budget.started=now;channel.attr(ECHO).set(budget);}
        if(now-budget.started>=1_000_000_000L) {budget.started=now;budget.count=0;}
        // A stalled TCP path can deliver every valid sample together. Permit that bounded
        // burst instead of manufacturing missing replies with an inter-arrival throttle.
        if(budget.count>=240) return false;
        budget.count++;return true;
    }
    private static final class EchoBudget {
        long started;int count;
    }
    private static final class Session {
        final Channel channel;final UUID id=UUID.randomUUID();final LatencyProbeWindow window;
        final Consumer<LatencyProbeWindow.Result> callback;ScheduledFuture<?> task;boolean done;
        Session(Channel channel,int samples,Consumer<LatencyProbeWindow.Result> callback) {
            this.channel=channel;this.callback=callback;window=new LatencyProbeWindow(System.nanoTime(),samples);
        }
        void tick() {
            if(done) return;
            if(!channel.isActive()) {finish(true);return;}
            long now=System.nanoTime();int sequence=window.due(now);
            if(sequence>=0) {
                try {
                    NetworkProbePackets.send(channel,true,new ProbeFrame(ProbeFrame.LATENCY_PING,id,sequence,window.sent[sequence],new byte[0]).encode())
                            .addListener(f -> {if(!f.isSuccess()) finish(true);});
                } catch(RuntimeException failure) {finish(true);return;}
            }
            if(window.finished(now)) finish(false);
        }
        void finish(boolean incomplete) {
            if(done) return;done=true;if(task!=null) task.cancel(false);
            channel.attr(SESSION).compareAndSet(this,null);ACTIVE.decrementAndGet();
            callback.accept(window.result(System.nanoTime(),incomplete));
        }
    }
}
