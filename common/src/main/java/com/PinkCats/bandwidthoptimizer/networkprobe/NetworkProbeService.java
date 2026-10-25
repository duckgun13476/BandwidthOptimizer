package com.PinkCats.bandwidthoptimizer.networkprobe;

import com.PinkCats.bandwidthoptimizer.debug.DiagnosticToolRegistry;
import com.PinkCats.bandwidthoptimizer.debug.NetworkProbeReports;
import com.google.gson.JsonObject;
import io.netty.channel.Channel;
import io.netty.util.AttributeKey;
import io.netty.util.concurrent.ScheduledFuture;
import java.nio.ByteBuffer;
import java.util.UUID;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;

public final class NetworkProbeService {
    private static final AttributeKey<NetworkProbeSession> SESSION=AttributeKey.valueOf("bo:independent_network_probe");
    private static final AttributeKey<ScheduledFuture<?>> TASK=AttributeKey.valueOf("bo:independent_network_probe_task");
    private static final java.util.concurrent.atomic.AtomicInteger ACTIVE=new java.util.concurrent.atomic.AtomicInteger();
    private NetworkProbeService() {}
    public static void install(Channel channel) {
        if(channel.pipeline().get(ProbeConnectionMonitor.NAME)==null)
            channel.pipeline().addFirst(ProbeConnectionMonitor.NAME,new ProbeConnectionMonitor());
    }
    static NetworkProbeSession current(Channel channel) { return ACTIVE.get()==0?null:channel.attr(SESSION).get(); }
    /** Identity only: no mutable event-loop session state is exposed to a future result screen. */
    public static NetworkProbeResults.Key resultKey(Channel channel) {
        NetworkProbeSession session=current(channel);
        return session==null?null:new NetworkProbeResults.Key(channel.id().asLongText(),session.id,session.server);
    }
    /** Server command is the only authority allowed to start a bulk test. */
    public static void start(Channel channel,long bytes,long bytesPerSecond,Consumer<String> feedback) {
        if(bytes<1 || bytes>NetworkProbeSession.MAX_BYTES || bytesPerSecond<125_000 || bytesPerSecond>16_000_000) {
            feedback.accept("invalid_budget"); return;
        }
        channel.eventLoop().execute(() -> {
            if(!channel.isActive()) { feedback.accept("connection_closed"); return; }
            if(current(channel)!=null) { feedback.accept("already_running"); return; }
            UUID id=UUID.randomUUID();
            if(!ProbeAdmission.acquire(id,System.nanoTime(),true)) { feedback.accept("busy_or_cooldown"); return; }
            NetworkProbeSession session=create(channel,id,true,bytes,bytesPerSecond,
                    report -> feedback.accept(report.get("outcome").getAsString()+" test="+id));
            feedback.accept("started test="+id);
            session.start();
        });
    }
    public static void cancel(Channel channel) {
        channel.eventLoop().execute(() -> {
            NetworkProbeSession session=current(channel);
            if(session!=null) session.abort("operator_cancelled");
        });
    }
    public static void receive(Channel channel,boolean serverSide,byte[] bytes) {
        NetworkProbeSession session=current(channel);
        ProbeFrame frame;
        try { frame=ProbeFrame.decode(bytes); }
        catch(IllegalArgumentException bad) { if(session!=null) session.abort("invalid_probe_frame"); return; }
        if(session==null) {
            // Never accept a client-originated request for data or allocate for arbitrary IDs.
            if(serverSide || frame.kind()!=ProbeFrame.OFFER || frame.sequence()!=0 || frame.body().length!=8) return;
            long rate=ByteBuffer.wrap(frame.body()).getLong();
            if(frame.value()<1 || frame.value()>NetworkProbeSession.MAX_BYTES || rate<125_000 || rate>16_000_000) return;
            if(!ProbeAdmission.acquire(frame.id(),System.nanoTime(),false)) return;
            session=create(channel,frame.id(),false,frame.value(),rate,report -> {});
            session.acceptedOffer(); return;
        }
        if(session.server!=serverSide) { session.abort("direction_mismatch"); return; }
        session.accept(frame);
    }
    public static void closed(Channel channel,String reason) {
        NetworkProbeSession session=current(channel);
        if(session!=null) session.finish(reason);
    }
    private static NetworkProbeSession create(Channel channel,UUID id,boolean server,long bytes,long rate,
                                               Consumer<JsonObject> callback) {
        DiagnosticToolRegistry.enable(DiagnosticToolRegistry.Tool.NETWORK_PROBE,5);
        NetworkProbeSession session=new NetworkProbeSession(channel,id,server,bytes,rate,System::nanoTime,
                frame -> NetworkProbePackets.send(channel,server,frame.encode()),
                report -> {
                    channel.attr(SESSION).set(null);
                    ACTIVE.decrementAndGet();
                    ScheduledFuture<?> task=channel.attr(TASK).getAndSet(null);
                    if(task!=null) task.cancel(false);
                    ProbeAdmission.release(id,System.nanoTime(),server);
                    publishResult(new NetworkProbeResults.Key(channel.id().asLongText(),id,server),report,callback);
                });
        ACTIVE.incrementAndGet();
        channel.attr(SESSION).set(session);
        try {
            channel.attr(TASK).set(channel.eventLoop().scheduleWithFixedDelay(
                    session::tick,10,10,TimeUnit.MILLISECONDS));
        } catch(java.util.concurrent.RejectedExecutionException closing) {
            session.finish("scheduler_closing");
        }
        return session;
    }
    static void publishResult(NetworkProbeResults.Key key,JsonObject report,Consumer<JsonObject> callback) {
        NetworkProbeResults.record(key,report);
        try { NetworkProbeReports.record(report); }
        finally { callback.accept(report); }
    }
}
