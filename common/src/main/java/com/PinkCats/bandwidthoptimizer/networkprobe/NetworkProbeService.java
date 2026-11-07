package com.PinkCats.bandwidthoptimizer.networkprobe;

import com.PinkCats.bandwidthoptimizer.debug.DiagnosticToolRegistry;
import com.PinkCats.bandwidthoptimizer.debug.NetworkProbeReports;
import com.google.gson.JsonObject;
import io.netty.channel.Channel;
import io.netty.channel.ChannelFuture;
import io.netty.util.AttributeKey;
import io.netty.util.concurrent.ScheduledFuture;
import java.nio.ByteBuffer;
import java.util.UUID;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;
import java.util.function.Function;

public final class NetworkProbeService {
    private static final AttributeKey<NetworkProbeSession> SESSION=AttributeKey.valueOf("bo:independent_network_probe");
    private static final AttributeKey<ScheduledFuture<?>> TASK=AttributeKey.valueOf("bo:independent_network_probe_task");
    private static final java.util.concurrent.atomic.AtomicInteger ACTIVE=new java.util.concurrent.atomic.AtomicInteger();
    static final long BASIC_BYTES=2_000_000, BASIC_RATE=1_000_000, AUTH_TIMEOUT=5_000_000_000L;
    private static final AttributeKey<NetworkProbeRequest> REQUEST=AttributeKey.valueOf("bo:network_probe_request");
    private static final AttributeKey<ScheduledFuture<?>> REQUEST_TASK=AttributeKey.valueOf("bo:network_probe_request_timeout");
    private static final AttributeKey<Long> LAST_REQUEST=AttributeKey.valueOf("bo:network_probe_request_last");
    private static final AttributeKey<Long> GENERATION=AttributeKey.valueOf("bo:network_probe_backend_generation");
    private NetworkProbeService() {}
    public static void install(Channel channel) {
        LinuxTcpInfo.prepare();
        if(channel.pipeline().get(ProbeConnectionMonitor.NAME)==null)
            channel.pipeline().addFirst(ProbeConnectionMonitor.NAME,new ProbeConnectionMonitor());
    }
    static NetworkProbeSession current(Channel channel) { return ACTIVE.get()==0?null:channel.attr(SESSION).get(); }
    /** Identity only: no mutable event-loop session state is exposed to a future result screen. */
    public static NetworkProbeResults.Key resultKey(Channel channel) {
        NetworkProbeSession session=current(channel);
        return session==null?null:new NetworkProbeResults.Key(channel.id().asLongText(),session.id,session.server);
    }
    public static long generation(Channel channel) {
        Long generation=channel.attr(GENERATION).get(); return generation==null?0:generation;
    }
    public static NetworkProbeProgress progress(Channel channel) {
        NetworkProbeSession session=current(channel); return session==null?null:session.progress;
    }
    /** Client may request only the server's fixed basic profile. Caller checks optional peer capability first. */
    public static NetworkProbeRequest request(Channel channel) {
        return request(channel,frame -> NetworkProbePackets.send(channel,false,frame.encode()));
    }
    static NetworkProbeRequest request(Channel channel,Function<ProbeFrame,ChannelFuture> sender) {
        NetworkProbeRequest request=new NetworkProbeRequest(UUID.randomUUID());
        try { channel.eventLoop().execute(() -> {
            if(!channel.isActive()) { request.status("connection_closed"); return; }
            NetworkProbeRequest previous=channel.attr(REQUEST).get();
            if(current(channel)!=null || (previous!=null && previous.waiting())) { request.status("already_running"); return; }
            if(!ProbeAdmission.acquire(request.id(),System.nanoTime(),false)) { request.status("busy_or_cooldown"); return; }
            channel.attr(REQUEST).set(request); request.status("waiting_for_authorization");
            try {
                channel.attr(REQUEST_TASK).set(channel.eventLoop().schedule(() -> {
                    if(request.waiting()) {
                        endRequest(channel,request,"authorization_timeout");
                        sender.apply(ProbeFrame.control(ProbeFrame.CANCEL,request.id()));
                    }
                },AUTH_TIMEOUT,TimeUnit.NANOSECONDS));
                sender.apply(ProbeFrame.control(ProbeFrame.REQUEST,request.id())).addListener(f -> {
                    if(!f.isSuccess() && request.waiting()) endRequest(channel,request,"request_write_failed");
                });
            } catch(RuntimeException unavailable) { endRequest(channel,request,"request_write_failed"); }
        }); } catch(java.util.concurrent.RejectedExecutionException closing) { request.status("connection_closed"); }
        return request;
    }
    private static void cancelRequestTimer(Channel channel) {
        ScheduledFuture<?> timer=channel.attr(REQUEST_TASK).getAndSet(null);
        if(timer!=null) timer.cancel(false);
    }
    private static void endRequest(Channel channel,NetworkProbeRequest request,String status) {
        cancelRequestTimer(channel); request.status(status);
        ProbeAdmission.release(request.id(),System.nanoTime(),false);
    }
    /** Operator can separately authorize a larger bounded profile. */
    public record Feedback(String status,UUID testId) {}
    public static void start(Channel channel,long bytes,long bytesPerSecond,Consumer<Feedback> feedback) {
        if(bytes<1 || bytes>NetworkProbeSession.MAX_BYTES || bytesPerSecond<125_000 || bytesPerSecond>16_000_000) {
            feedback.accept(new Feedback("invalid_budget",null)); return;
        }
        channel.eventLoop().execute(() -> {
            if(!channel.isActive()) { feedback.accept(new Feedback("connection_closed",null)); return; }
            if(current(channel)!=null) { feedback.accept(new Feedback("already_running",null)); return; }
            UUID id=UUID.randomUUID();
            if(!ProbeAdmission.acquire(id,System.nanoTime(),true)) { feedback.accept(new Feedback("busy_or_cooldown",null)); return; }
            NetworkProbeSession session=create(channel,id,true,bytes,bytesPerSecond,
                    report -> feedback.accept(new Feedback(report.get("outcome").getAsString(),id)));
            feedback.accept(new Feedback("started",id));
            session.start();
        });
    }
    public static void cancel(Channel channel) {
        cancel(channel,null);
    }
    /** UI cancellation is scoped to its ticket and cannot cancel a newer operator test. */
    public static void cancel(Channel channel,UUID testId) {
        cancel(channel,testId,frame -> NetworkProbePackets.send(channel,false,frame.encode()));
    }
    static void cancel(Channel channel,UUID testId,Function<ProbeFrame,ChannelFuture> sender) {
        try { channel.eventLoop().execute(() -> {
            NetworkProbeSession session=current(channel);
            if(session!=null) {
                if(testId==null || testId.equals(session.id)) session.abort("operator_cancelled");
            }
            else {
                NetworkProbeRequest request=channel.attr(REQUEST).get();
                if(request!=null && request.waiting() && (testId==null || testId.equals(request.id()))) {
                    endRequest(channel,request,"cancelled");
                    sender.apply(ProbeFrame.control(ProbeFrame.CANCEL,request.id()));
                }
            }
        }); } catch(java.util.concurrent.RejectedExecutionException closing) { /* Closed channel owns cleanup. */ }
    }
    public static void receive(Channel channel,boolean serverSide,byte[] bytes) {
        if (LatencyProbeService.receive(channel,serverSide,bytes)) return;
        receive(channel,serverSide,bytes,frame -> NetworkProbePackets.send(channel,serverSide,frame.encode()));
    }
    static void receive(Channel channel,boolean serverSide,byte[] bytes,Function<ProbeFrame,ChannelFuture> sender) {
        NetworkProbeSession session=current(channel);
        ProbeFrame frame;
        try { frame=ProbeFrame.decode(bytes); }
        catch(IllegalArgumentException bad) { if(session!=null) session.abort("invalid_probe_frame"); return; }
        if(frame.kind()==ProbeFrame.REQUEST) {
            if(!serverSide || frame.sequence()!=0 || frame.value()!=0 || frame.body().length!=0 || !channel.isActive()) return;
            long now=System.nanoTime(); Long last=channel.attr(LAST_REQUEST).get();
            if(last!=null && now-last<ProbeAdmission.COOLDOWN) return;
            channel.attr(LAST_REQUEST).set(now);
            boolean enabled=Boolean.parseBoolean(System.getProperty("bandwidthoptimizer.networkProbe.clientRequests","true"));
            if(!enabled || session!=null || !ProbeAdmission.acquire(frame.id(),now,true)) {
                sender.apply(new ProbeFrame(ProbeFrame.STATUS,frame.id(),enabled?1:2,0,new byte[0])); return;
            }
            create(channel,frame.id(),true,BASIC_BYTES,BASIC_RATE,report -> {},sender).startRequested(); return;
        }
        if(frame.kind()==ProbeFrame.STATUS) {
            NetworkProbeRequest request=channel.attr(REQUEST).get();
            if(!serverSide && request!=null && request.waiting() && request.id().equals(frame.id())
                    && frame.value()==0 && frame.body().length==0 && (frame.sequence()==1 || frame.sequence()==2))
                endRequest(channel,request,frame.sequence()==1?"server_busy_or_cooldown":"server_disabled");
            return;
        }
        if(session==null) {
            if(serverSide || frame.kind()!=ProbeFrame.OFFER || frame.sequence()>1 || frame.body().length!=8) return;
            long rate=ByteBuffer.wrap(frame.body()).getLong();
            if(frame.value()<1 || frame.value()>NetworkProbeSession.MAX_BYTES || rate<125_000 || rate>16_000_000) return;
            NetworkProbeRequest request=channel.attr(REQUEST).get();
            if(frame.sequence()==1) {
                if(request==null || !request.waiting() || !request.id().equals(frame.id())) return;
                if(frame.value()!=BASIC_BYTES || rate!=BASIC_RATE) {
                    endRequest(channel,request,"invalid_authorization");
                    sender.apply(ProbeFrame.control(ProbeFrame.CANCEL,frame.id())); return;
                }
                cancelRequestTimer(channel); request.status("running");
            } else {
                // A genuine operator OFFER may cross a pending client REQUEST in flight.
                // Hand over the reservation instead of leaving the operator test waiting for READY.
                if(request!=null && request.waiting()) {
                    if(!ProbeAdmission.handover(request.id(),frame.id(),false)) return;
                    cancelRequestTimer(channel); request.status("superseded_by_operator");
                } else if(!ProbeAdmission.acquire(frame.id(),System.nanoTime(),false)) return;
            }
            session=create(channel,frame.id(),false,frame.value(),rate,report -> {},sender);
            if(!session.done()) session.acceptedOffer(); return;
        }
        if(session.server!=serverSide) { session.abort("direction_mismatch"); return; }
        session.accept(frame);
    }
    public static void closed(Channel channel,String reason) {
        LatencyProbeService.closed(channel);
        if(reason.equals("backend_login_replaced_session")) channel.attr(GENERATION).set(generation(channel)+1);
        NetworkProbeSession session=current(channel);
        if(session!=null) session.finish(reason);
        NetworkProbeRequest request=channel.attr(REQUEST).get();
        if(request!=null && request.waiting()) endRequest(channel,request,reason);
    }
    private static NetworkProbeSession create(Channel channel,UUID id,boolean server,long bytes,long rate,
                                               Consumer<JsonObject> callback) {
        return create(channel,id,server,bytes,rate,callback,frame -> NetworkProbePackets.send(channel,server,frame.encode()));
    }
    private static NetworkProbeSession create(Channel channel,UUID id,boolean server,long bytes,long rate,
                                               Consumer<JsonObject> callback,Function<ProbeFrame,ChannelFuture> sender) {
        DiagnosticToolRegistry.enable(DiagnosticToolRegistry.Tool.NETWORK_PROBE,5);
        NetworkProbeSession session=new NetworkProbeSession(channel,id,server,bytes,rate,System::nanoTime,
                sender,
                report -> {
                    channel.attr(SESSION).set(null);
                    ACTIVE.decrementAndGet();
                    ScheduledFuture<?> task=channel.attr(TASK).getAndSet(null);
                    if(task!=null) task.cancel(false);
                    ProbeAdmission.release(id,System.nanoTime(),server);
                    NetworkProbeRequest request=channel.attr(REQUEST).get();
                    try { publishResult(new NetworkProbeResults.Key(channel.id().asLongText(),id,server),report,callback); }
                    finally { if(!server && request!=null && request.id().equals(id)) request.status(report.get("outcome").getAsString()); }
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
