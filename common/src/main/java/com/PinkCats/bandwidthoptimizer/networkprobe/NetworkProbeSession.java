package com.PinkCats.bandwidthoptimizer.networkprobe;

import com.google.gson.*;
import io.netty.channel.*;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.function.*;
import java.util.zip.CRC32;

/** Event-loop confined, finite test state. Never participates in game recovery or compression. */
final class NetworkProbeSession {
    static final long SECOND=1_000_000_000L, MAX_BYTES=100_000_000L, MAX_TIME=35*SECOND;
    enum Phase { NEGOTIATING, BASELINE, WAIT_UPLOAD, SEND_UPLOAD, RECEIVE_UPLOAD,
        WAIT_UPLOAD_RECEIPT, REST, WAIT_DOWNLOAD, SEND_DOWNLOAD, RECEIVE_DOWNLOAD, WAIT_DOWNLOAD_RECEIPT, DONE }
    final UUID id;
    final boolean server;
    final long budget, rate;
    final Channel channel;
    final LongSupplier clock;
    final Function<ProbeFrame,ChannelFuture> sender;
    final Consumer<JsonObject> completion;
    final long started;
    final long startedAtEpochMillis=System.currentTimeMillis();
    final CRC32 sendCrc=new CRC32(), receiveCrc=new CRC32();
    final long[] pingSent=new long[350], rtts=new long[350];
    final String[] pingPhases=new String[350];
    final JsonArray samples=new JsonArray();
    final JsonObject phases=new JsonObject();
    JsonObject tcpStart;
    long tcpBaselineElapsed;
    Phase phase=Phase.NEGOTIATING;
    long phaseStart, received, sent, pending, nettyInbound, nettyOutbound, blockedTicks, lastSample, nextPing;
    long lastPump, allowance, receiveFirst, receiveLast, maxSchedulingDelay;
    int sendSequence, receiveSequence, pingCount, pongCount;
    String lastError="";
    JsonObject tcpEnd;
    JsonObject peerSummary;
    volatile NetworkProbeProgress progress;
    JsonObject tcpLastObserved;
    long tcpLastObservedElapsed;
    private final byte[] randomBlock=new byte[ProbeFrame.BLOCK];

    NetworkProbeSession(Channel channel, UUID id, boolean server, long budget, long rate,
                        LongSupplier clock, Function<ProbeFrame,ChannelFuture> sender, Consumer<JsonObject> completion) {
        if (budget<1 || budget>MAX_BYTES || rate<125_000 || rate>16_000_000)
            throw new IllegalArgumentException("Network probe budget/rate out of range");
        this.channel=channel; this.id=id; this.server=server; this.budget=budget; this.rate=rate;
        this.clock=clock; this.sender=sender; this.completion=completion;
        this.started=clock.getAsLong(); this.phaseStart=started; this.lastPump=started;
        this.lastSample=started; this.nextPing=started; this.tcpStart=TcpSocketMetrics.sample(channel);
        progress=new NetworkProbeProgress(id,phase.name(),0,0,budget,0);
        new Random(id.getLeastSignificantBits()).nextBytes(randomBlock);
    }
    boolean done() { return phase==Phase.DONE; }
    void start() { if(server) send(new ProbeFrame(ProbeFrame.OFFER,id,0,budget,ByteBuffer.allocate(8).putLong(rate).array())); }
    void startRequested() { if(server) send(new ProbeFrame(ProbeFrame.OFFER,id,1,budget,ByteBuffer.allocate(8).putLong(rate).array())); }
    void acceptedOffer() { transition(Phase.WAIT_UPLOAD); send(ProbeFrame.control(ProbeFrame.READY,id)); }

    void accept(ProbeFrame f) {
        if(done() || !id.equals(f.id())) return;
        if(f.kind()==ProbeFrame.CANCEL) { finish("peer_cancelled"); return; }
        if(f.kind()==ProbeFrame.PING) {
            if(server || f.sequence()>=350 || f.body().length!=0) { abort("invalid_ping"); return; }
            // At most 10 replies per second, independent of the peer's requested timestamp.
            long now=clock.getAsLong();
            if(now-nextPing>=0) { nextPing=now+SECOND/10; send(new ProbeFrame(ProbeFrame.PONG,id,f.sequence(),f.value(),new byte[0])); }
            return;
        }
        if(f.kind()==ProbeFrame.PONG) {
            int n=f.sequence();
            if(!server || n>=pingCount || f.value()!=pingSent[n] || rtts[n]!=0 || f.body().length!=0) return;
            rtts[n]=Math.max(1,clock.getAsLong()-pingSent[n]); pongCount++;
            return;
        }
        switch(f.kind()) {
            case ProbeFrame.READY -> {
                if(!server || phase!=Phase.NEGOTIATING || !empty(f)) { abort("unexpected_ready"); return; }
                transition(Phase.BASELINE);
            }
            case ProbeFrame.UPLOAD -> {
                if(server || phase!=Phase.WAIT_UPLOAD || !empty(f)) { abort("unexpected_upload"); return; }
                beginSending(Phase.SEND_UPLOAD);
            }
            case ProbeFrame.DOWNLOAD -> {
                if(server || phase!=Phase.WAIT_DOWNLOAD || !empty(f)) { abort("unexpected_download"); return; }
                beginReceiving(Phase.RECEIVE_DOWNLOAD);
            }
            case ProbeFrame.DATA -> receiveData(f);
            case ProbeFrame.END -> receiveEnd(f);
            case ProbeFrame.RECEIPT -> receipt(f);
            case ProbeFrame.COMPLETE -> {
                if(server || phase!=Phase.WAIT_DOWNLOAD || !empty(f) || peerSummary==null) { abort("unexpected_complete"); return; }
                finish("completed");
            }
            case ProbeFrame.RESULT -> {
                if(server || phase!=Phase.WAIT_DOWNLOAD || !phases.has("download_received") || !phases.has("upload_peer_receipt")
                        || peerSummary!=null || f.sequence()!=0 || f.value()!=0) {
                    abort("unexpected_result"); return;
                }
                try {
                    JsonObject decoded=ProbeResultSummary.decode(f.body());
                    JsonObject metrics=decoded.getAsJsonObject("metrics");
                    if(metrics.get("budget_bytes_per_direction").getAsLong()!=budget
                            || metrics.get("rate_limit_bytes_s").getAsLong()!=rate
                            || metrics.get("upload_verified_bytes").getAsLong()!=sent
                              || metrics.get("download_verified_bytes").getAsLong()!=received
                              || metrics.get("upload_receive_ns").getAsLong()!=phases.getAsJsonObject("upload_peer_receipt").get("peer_receive_phase_ns").getAsLong()
                              || metrics.get("download_receive_ns").getAsLong()!=phases.getAsJsonObject("download_received").get("receiver_phase_ns").getAsLong())
                        throw new IllegalArgumentException("Peer result does not match verified transfer");
                    peerSummary=decoded;
                } catch(IllegalArgumentException bad) { abort("invalid_result_summary"); }
            }
            default -> abort("unexpected_frame");
        }
    }
    private static boolean empty(ProbeFrame f) { return f.sequence()==0 && f.value()==0 && f.body().length==0; }
    private void receiveData(ProbeFrame f) {
        if(phase!=Phase.RECEIVE_UPLOAD && phase!=Phase.RECEIVE_DOWNLOAD) { abort("data_outside_receive"); return; }
        byte[] data=f.body();
        if(data.length==0 || f.sequence()!=receiveSequence || f.value()!=0 || received>budget-data.length) {
            abort("data_sequence_or_budget"); return;
        }
        long now=clock.getAsLong();
        if(receiveSequence==0) receiveFirst=now;
        receiveLast=now; receiveSequence++; received+=data.length; receiveCrc.update(data);
    }
    private void receiveEnd(ProbeFrame f) {
        if((phase!=Phase.RECEIVE_UPLOAD && phase!=Phase.RECEIVE_DOWNLOAD) || f.body().length!=8
                || f.sequence()!=receiveSequence || f.value()!=received || received<1
                || ByteBuffer.wrap(f.body()).getLong()!=receiveCrc.getValue()) { abort("end_integrity"); return; }
        long duration=Math.max(1,clock.getAsLong()-phaseStart);
        JsonObject measured=new JsonObject();
        measured.addProperty("verified_bytes",received);
        measured.addProperty("receiver_phase_ns",duration);
        measured.addProperty("receiver_first_to_last_ns",Math.max(0,receiveLast-receiveFirst));
        measured.addProperty("receiver_goodput_mbit_s",received*8000.0/duration);
        measured.addProperty("crc32",receiveCrc.getValue());
        measured.addProperty("byte_budget_reached",received==budget);
        phases.add(server?"upload_received":"download_received",measured);
        byte[] tcp=TcpSocketMetrics.sample(channel).toString().getBytes(StandardCharsets.UTF_8);
        ByteBuffer body=ByteBuffer.allocate(16+tcp.length).putLong(receiveCrc.getValue()).putLong(duration).put(tcp);
        if(server) transition(Phase.REST); else transition(Phase.WAIT_DOWNLOAD);
        send(new ProbeFrame(ProbeFrame.RECEIPT,id,receiveSequence,received,body.array()));
    }
    private void receipt(ProbeFrame f) {
        if((phase!=Phase.WAIT_UPLOAD_RECEIPT && phase!=Phase.WAIT_DOWNLOAD_RECEIPT) || f.body().length<16
                || f.value()!=sent || f.sequence()!=sendSequence) { abort("unexpected_receipt"); return; }
        ByteBuffer data=ByteBuffer.wrap(f.body());
        long crc=data.getLong(), duration=data.getLong();
        if(crc!=sendCrc.getValue() || duration<=0 || duration>MAX_TIME) { abort("receipt_integrity"); return; }
        JsonObject measured=new JsonObject();
        measured.addProperty("peer_verified_bytes",sent);
        measured.addProperty("peer_receive_phase_ns",duration);
        measured.addProperty("peer_receive_goodput_mbit_s",sent*8000.0/duration);
        if(data.hasRemaining()) {
            byte[] tcp=new byte[data.remaining()]; data.get(tcp);
            try { measured.add("peer_socket_tcp",JsonParser.parseString(new String(tcp,StandardCharsets.UTF_8))); }
            catch(RuntimeException bad) { abort("receipt_tcp_format"); return; }
        }
        phases.add(server?"download_peer_receipt":"upload_peer_receipt",measured);
        if(server) { finish("completed"); }
        else transition(Phase.WAIT_DOWNLOAD);
    }
    void tick() {
        if(done()) return;
        long now=clock.getAsLong();
        if(!channel.isActive()) { finish("connection_closed_cause_unknown"); return; }
        long scheduling=now-lastPump; if(scheduling>20_000_000L) maxSchedulingDelay=Math.max(maxSchedulingDelay,scheduling-10_000_000L);
        if(scheduling>SECOND/2) { abort("local_event_loop_stall"); return; }
        if(now-started>=MAX_TIME || now-phaseStart>=12*SECOND) { abort("test_deadline_not_packet_loss"); return; }
        if(now-lastSample>=SECOND/10 && samples.size()<350) {
            progress=new NetworkProbeProgress(id,phase.name(),sent,received,budget,now-started);
            JsonObject sample=new JsonObject();
            sample.addProperty("elapsed_ns",now-started); sample.addProperty("phase",phase.name());
            sample.addProperty("netty_in_bytes",nettyInbound); sample.addProperty("netty_out_bytes",nettyOutbound);
            sample.addProperty("test_sent_bytes",sent); sample.addProperty("test_received_bytes",received);
            sample.addProperty("test_pending_write_bytes",pending); sample.addProperty("writable",channel.isWritable());
            JsonObject tcp=TcpSocketMetrics.sample(channel);
            if(!"supported".equals(tcpStart.get("status").getAsString()) && "supported".equals(tcp.get("status").getAsString())) {
                tcpStart=tcp.deepCopy(); tcpBaselineElapsed=now-started;
            }
            sample.add("tcp",tcp); samples.add(sample); lastSample=now;
        }
        if(server && phase!=Phase.NEGOTIATING && pingCount<350 && now-nextPing>=0) {
            nextPing=now+SECOND/10;
            int sequence=pingCount++; pingSent[sequence]=now; pingPhases[sequence]=phase.name();
            send(new ProbeFrame(ProbeFrame.PING,id,sequence,now,new byte[0]));
        }
        if(server && phase==Phase.BASELINE && now-phaseStart>=SECOND) {
            beginReceiving(Phase.RECEIVE_UPLOAD); send(ProbeFrame.control(ProbeFrame.UPLOAD,id));
        }
        if(server && phase==Phase.REST && now-phaseStart>=SECOND/2) {
            send(ProbeFrame.control(ProbeFrame.DOWNLOAD,id)); beginSending(Phase.SEND_DOWNLOAD);
        }
        if(phase==Phase.SEND_UPLOAD || phase==Phase.SEND_DOWNLOAD) pump(now);
        lastPump=now;
    }
    private void pump(long now) {
        if(now-phaseStart>=10*SECOND) {
            if(sent==0) { abort("sender_no_progress"); return; }
            endSending(); return;
        }
        long elapsed=Math.max(0,Math.min(SECOND/10,now-lastPump));
        allowance=Math.min(256*1024,allowance+rate*elapsed/SECOND);
        if(!channel.isWritable() || pending>=256*1024) { blockedTicks++; allowance=0; return; }
        while(!done() && channel.isWritable() && sent<budget && allowance>0 && pending<256*1024-ProbeFrame.HEADER) {
            int n=(int)Math.min(Math.min(Math.min(budget-sent,ProbeFrame.BLOCK),allowance),256*1024-pending-ProbeFrame.HEADER);
            if(n<=0) break;
            byte[] body=Arrays.copyOf(randomBlock,n);
            sendCrc.update(body); sent+=n; allowance-=n;
            send(new ProbeFrame(ProbeFrame.DATA,id,sendSequence++,0,body));
        }
        if(done()) return;
        if(sent==budget) endSending();
    }
    private void endSending() {
        transition(server?Phase.WAIT_DOWNLOAD_RECEIPT:Phase.WAIT_UPLOAD_RECEIPT);
        send(new ProbeFrame(ProbeFrame.END,id,sendSequence,sent,ByteBuffer.allocate(8).putLong(sendCrc.getValue()).array()));
    }
    private void beginSending(Phase next) { sent=0; sendSequence=0; sendCrc.reset(); allowance=0; lastPump=clock.getAsLong(); transition(next); }
    private void beginReceiving(Phase next) { received=0; receiveSequence=0; receiveCrc.reset(); receiveFirst=0; receiveLast=0; transition(next); }
    private void transition(Phase next) { phase=next; phaseStart=clock.getAsLong(); }
    private void send(ProbeFrame frame) {
        if(done()) return;
        int bytes=ProbeFrame.HEADER+frame.body().length;
        pending+=bytes;
        try {
            ChannelFuture future=sender.apply(frame);
            if(future==null) throw new IllegalStateException("Probe packet writer unavailable");
            future.addListener(result -> {
                pending-=bytes;
                if(!result.isSuccess() && !done()) {
                    lastError=result.cause()==null?"write_failed":result.cause().getClass().getName();
                    com.PinkCats.bandwidthoptimizer.connection.ConnectionDisconnectClassifier.observeException(channel,result.cause());
                    finish("write_failed");
                }
            });
        } catch(RuntimeException failure) {
            pending-=bytes; lastError=failure.getClass().getName();
            com.PinkCats.bandwidthoptimizer.connection.ConnectionDisconnectClassifier.observeException(channel,failure);
            finish("write_failed");
        }
    }
    void abort(String reason) {
        if(done()) return;
        send(ProbeFrame.control(ProbeFrame.CANCEL,id));
        finish(reason);
    }
    void finish(String outcome) {
        if(done()) return;
        String finalPhase=phase.name(); phase=Phase.DONE;
        tcpEnd=TcpSocketMetrics.sample(channel);
        tcpLastObserved=tcpEnd;
        tcpLastObservedElapsed=clock.getAsLong()-started;
        if(!"supported".equals(tcpEnd.get("status").getAsString())) {
            for(int i=samples.size()-1;i>=0;i--) {
                JsonObject window=samples.get(i).getAsJsonObject();
                JsonObject tcp=window.getAsJsonObject("tcp");
                if("supported".equals(tcp.get("status").getAsString())) {
                    tcpLastObserved=tcp;
                    tcpLastObservedElapsed=window.get("elapsed_ns").getAsLong();
                    break;
                }
            }
        }
        JsonObject report=new JsonObject();
        report.addProperty("v",1); report.addProperty("event","network_probe_summary");
        report.addProperty("test_id",id.toString()); report.addProperty("side",server?"server":"client");
        report.addProperty("outcome",outcome); report.addProperty("last_phase",finalPhase);
        report.addProperty("started_at_epoch_ms",startedAtEpochMillis);
        report.addProperty("finished_at_epoch_ms",System.currentTimeMillis());
        report.addProperty("socket_transport",channel.getClass().getName());
        report.addProperty("test_path","BO compression/aggregation bypass; native MC framing/compression/encryption retained");
        report.addProperty("window","this_test_only");
        report.addProperty("elapsed_ns",clock.getAsLong()-started);
        report.addProperty("budget_bytes_per_direction",budget); report.addProperty("rate_limit_bytes_s",rate);
        report.addProperty("netty_in_bytes_including_game",nettyInbound);
        report.addProperty("netty_out_bytes_including_game",nettyOutbound);
        report.addProperty("netty_boundary","probe_monitor; not_tcp_ack_or_nic");
        report.addProperty("write_backpressure_ticks",blockedTicks);
        report.addProperty("max_event_loop_scheduling_delay_ns",maxSchedulingDelay);
        report.addProperty("last_error_class",lastError);
        var close=com.PinkCats.bandwidthoptimizer.connection.ConnectionDisconnectClassifier.snapshot(channel);
        report.addProperty("disconnect_category",close.category().name());
        report.addProperty("disconnect_is_proof_of_network_failure",false);
        report.addProperty("probe_count",pingCount); report.addProperty("probe_replies",pongCount);
        report.addProperty("missing_replies_are_packet_loss",false);
        report.addProperty("maximum_link_speed","not_established");
        report.addProperty("packet_loss_rate","unknown");
        JsonArray latency=new JsonArray();
        for(int i=0;i<pingCount;i++) {
            JsonObject row=new JsonObject(); row.addProperty("sent_elapsed_ns",pingSent[i]-started);
            row.addProperty("phase",pingPhases[i]);
            row.addProperty("rtt_ns",rtts[i]==0?null:rtts[i]); latency.add(row);
        }
        report.add("application_rtt",latency); report.add("phases",phases);
        report.add("tcp_start",tcpStart); report.add("tcp_end",tcpEnd); report.add("windows",samples);
        report.add("tcp_last_observed",tcpLastObserved);
        report.addProperty("tcp_last_observed_elapsed_ns",tcpLastObservedElapsed);
        report.addProperty("tcp_terminal_sample_supported","supported".equals(tcpEnd.get("status").getAsString()));
        report.addProperty("tcp_baseline_elapsed_ns",tcpBaselineElapsed);
        report.add("analysis",analyze());
        report.addProperty("findings_version",1);
        report.addProperty("findings_scope","observed_test_effects_only; absence_is_not_proof_of_health");
        report.add("findings",NetworkProbeFindings.detect(report));
        if(!server) {
            report.addProperty("peer_summary_status",peerSummary==null?"not_received":"received");
            if(peerSummary!=null) report.add("peer_summary",peerSummary);
        }
        if(server && outcome.equals("completed")) {
            try {
                sender.apply(new ProbeFrame(ProbeFrame.RESULT,id,0,0,ProbeResultSummary.encode(report)));
                sender.apply(ProbeFrame.control(ProbeFrame.COMPLETE,id));
            } catch(RuntimeException unavailable) {
                report.addProperty("peer_summary_delivery","not_queued");
            }
        }
        completion.accept(report);
    }

    private JsonObject analyze() {
        JsonObject analysis=new JsonObject();
        analysis.addProperty("local_write_backpressure_observed",blockedTicks>0);
        analysis.addProperty("qos_or_isp_throttling","not_established");
        analysis.addProperty("tcp_delta_window_start_ns",tcpBaselineElapsed);
        analysis.addProperty("tcp_delta_window_end_ns",tcpLastObservedElapsed);
        analysis.addProperty("tcp_delta_excludes_unsampled_close_tail",!"supported".equals(tcpEnd.get("status").getAsString()));
        if(tcpStart.has("total_retransmitted_bytes") && tcpLastObserved.has("total_retransmitted_bytes")) {
            long delta=tcpLastObserved.get("total_retransmitted_bytes").getAsLong()-tcpStart.get("total_retransmitted_bytes").getAsLong();
            if(delta>=0) analysis.addProperty("local_tcp_retransmitted_bytes_delta",delta);
            else analysis.addProperty("retransmission_delta_status","counter_reset_or_wrap");
        }
        if(tcpStart.has("total_retransmitted_segments") && tcpLastObserved.has("total_retransmitted_segments")) {
            long delta=tcpLastObserved.get("total_retransmitted_segments").getAsLong()-tcpStart.get("total_retransmitted_segments").getAsLong();
            if(delta>=0) analysis.addProperty("local_tcp_retransmitted_segments_delta",delta);
            else analysis.addProperty("retransmission_delta_status","counter_reset_or_wrap");
        }
        JsonObject quantiles=new JsonObject();
        for(Phase p:Phase.values()) {
            long[] values=new long[pingCount]; int n=0;
            for(int i=0;i<pingCount;i++) if(p.name().equals(pingPhases[i]) && rtts[i]>0) values[n++]=rtts[i];
            if(n==0) continue;
            Arrays.sort(values,0,n);
            JsonObject q=new JsonObject(); q.addProperty("samples",n);
            q.addProperty("p50_ns",values[(n-1)/2]); q.addProperty("p95_ns",values[(int)Math.ceil(n*.95)-1]);
            quantiles.add(p.name(),q);
        }
        analysis.add("application_rtt_by_phase",quantiles);
        if(quantiles.has("BASELINE")) {
            long baseline=quantiles.getAsJsonObject("BASELINE").get("p95_ns").getAsLong();
            boolean increase=false;
            for(String key:List.of("RECEIVE_UPLOAD","SEND_DOWNLOAD")) if(quantiles.has(key))
                increase|=quantiles.getAsJsonObject(key).get("p95_ns").getAsLong()>baseline+20_000_000L;
            analysis.addProperty("loaded_application_p95_increased_over_20ms",increase);
        }
        return analysis;
    }
}
