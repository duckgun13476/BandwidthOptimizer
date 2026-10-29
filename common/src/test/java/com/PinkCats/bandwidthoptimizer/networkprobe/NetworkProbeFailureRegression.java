package com.PinkCats.bandwidthoptimizer.networkprobe;

import com.google.gson.JsonObject;
import io.netty.channel.embedded.EmbeddedChannel;
import java.nio.ByteBuffer;
import java.util.*;
import java.util.concurrent.atomic.AtomicLong;

/** Reproduces a receiver deadline after the sender has queued its whole upload. */
final class NetworkProbeFailureRegression {
    private static void check(boolean ok,String detail) {if(!ok) throw new AssertionError(detail);}
    static void run() {
        AtomicLong time=new AtomicLong(1);
        EmbeddedChannel sc=new EmbeddedChannel(),cc=new EmbeddedChannel();
        UUID id=UUID.randomUUID();
        List<ProbeFrame> frames=new ArrayList<>();
        List<JsonObject> sr=new ArrayList<>(),cr=new ArrayList<>();
        NetworkProbeSession server=new NetworkProbeSession(sc,id,true,2_000_000,1_000_000,time::get,
                f->{frames.add(ProbeFrame.decode(f.encode()));return sc.newSucceededFuture();},sr::add);
        NetworkProbeSession client=client(cc,id,time,cr);
        server.phase=NetworkProbeSession.Phase.RECEIVE_UPLOAD;
        // Real incident progress and RTT values; four loaded replies do not prove an increase finding.
        server.received=730_541;
        server.pingCount=10;
        for(int i=0;i<10;i++) {
            server.pingSent[i]=1;
            server.rtts[i]=i<6?1_492_656_904L:7_380_824_752L;
            server.pingPhases[i]=i<6?"BASELINE":"RECEIVE_UPLOAD";
        }
        for(int i=0;i<1200;i++) {time.addAndGet(10_000_000);server.tick();}
        check(server.done() && sr.size()==1,"deadline completes server exactly once");
        ProbeFrame failure=frames.stream().filter(f->f.kind()==ProbeFrame.CANCEL).findFirst().orElseThrow();
        check(failure.body().length<=2048 && failure.body()[0]==3,"bounded failure schema");
        check(frames.stream().filter(f->f.kind()==ProbeFrame.CANCEL).count()==1,"one atomic failure frame");
        client.accept(failure);
        check(client.done() && cr.size()==1,"client accepts server failure once");
        JsonObject report=cr.get(0),peer=report.getAsJsonObject("peer_summary"),metrics=peer.getAsJsonObject("metrics");
        check(report.get("outcome").getAsString().equals("server_test_deadline_not_packet_loss"),"actual server cause retained");
        check(metrics.get("upload_received_bytes").getAsLong()==730_541 && !metrics.has("upload_verified_bytes"),"partial bytes never become verified transfer");
        check(metrics.get("upload_p95_ns").getAsLong()==7_380_824_752L,"loaded RTT survives timeout");
        check(ProbeResultSummary.decode(ProbeResultSummary.encode(sr.get(0))).equals(peer),"exact failure summary roundtrip");
        var rows=NetworkAnalysisOverview.rows(report);
        check(rows.stream().anyMatch(r->r.key().equals("bo.network.server_failure") && r.args().get(0).equals("bo.network.server_failure.deadline")),"overview shows real reason");
        check(rows.stream().anyMatch(r->r.key().equals("bo.network.partial_upload") && r.args().equals(List.of("730.54","2000.00"))),"overview shows received progress");
        check(rows.stream().anyMatch(r->r.key().equals("bo.network.observed_latency") && r.args().get(1).equals("7380.82")),"four replies still show measured RTT");
        check(NetworkAnalysisOverview.conclusion(rows).severity()==NetworkAnalysisOverview.Severity.WARNING,"incomplete problem stays yellow");
        client.accept(failure);check(cr.size()==1,"late duplicate has no second completion");
        check(sc.isActive() && cc.isActive(),"failed test leaves game channels active");
        for(int offset:new int[]{1,2,3}) {
            byte[] bad=failure.body();bad[offset]=(byte)255;
            boolean rejected=false;try {ProbeResultSummary.decode(bad);}catch(IllegalArgumentException expected){rejected=true;}
            check(rejected,"invalid fixed reason/phase/progress rejected at "+offset);
        }
        byte[] bad=failure.body();ByteBuffer.wrap(bad).putLong(3,2_000_001);
        rejects(client(cc,UUID.randomUUID(),time,new ArrayList<>()),bad,"over-budget progress");
        bad=failure.body();ByteBuffer.wrap(bad).putLong(3,2_000_000);
        NetworkProbeSession mismatched=client(cc,UUID.randomUUID(),time,new ArrayList<>());mismatched.sent=1_000_000;
        rejects(mismatched,bad,"peer cannot claim more than client sent");
        rejects(client(cc,UUID.randomUUID(),time,new ArrayList<>()),Arrays.copyOf(failure.body(),10),"truncated summary");
        bad=failure.body();ByteBuffer.wrap(bad).putLong(27+2*8,1_000_000);
        rejects(client(cc,UUID.randomUUID(),time,new ArrayList<>()),bad,"session budget mismatch");
        NetworkProbeSession wrongRole=new NetworkProbeSession(sc,UUID.randomUUID(),true,2_000_000,1_000_000,time::get,
                f->sc.newSucceededFuture(),r->{});
        rejects(wrongRole,failure.body(),"client cannot provide server observations");
        NetworkProbeSession wrongFrame=client(cc,UUID.randomUUID(),time,new ArrayList<>());
        wrongFrame.phase=NetworkProbeSession.Phase.WAIT_DOWNLOAD;
        wrongFrame.phases.add("download_received",new JsonObject());wrongFrame.phases.add("upload_peer_receipt",new JsonObject());
        wrongFrame.accept(new ProbeFrame(ProbeFrame.RESULT,wrongFrame.id,0,0,failure.body()));
        check(wrongFrame.done() && wrongFrame.peerSummary==null,"failure schema cannot masquerade as successful RESULT");
        NetworkProbeSession foreign=client(cc,UUID.randomUUID(),time,new ArrayList<>());
        foreign.accept(failure);check(!foreign.done(),"foreign session failure ignored");
        foreign.accept(ProbeFrame.control(ProbeFrame.CANCEL,foreign.id));
        check(foreign.done() && foreign.peerSummary==null,"legacy empty cancellation remains accepted without invented evidence");
        completedUploadPartialDownload(sc,cc,time);
        sc.finishAndReleaseAll();cc.finishAndReleaseAll();
        System.out.println("NETWORK_PROBE_FAILURE_PASS");
    }
    private static NetworkProbeSession client(EmbeddedChannel channel,UUID id,AtomicLong time,List<JsonObject> reports) {
        NetworkProbeSession s=new NetworkProbeSession(channel,id,false,2_000_000,1_000_000,time::get,
                f->channel.newSucceededFuture(),reports::add);
        s.phase=NetworkProbeSession.Phase.WAIT_UPLOAD_RECEIPT;s.sent=2_000_000;return s;
    }
    private static void rejects(NetworkProbeSession client,byte[] body,String detail) {
        client.accept(new ProbeFrame(ProbeFrame.CANCEL,client.id,1,0,body));
        check(client.done() && client.peerSummary==null,detail);
    }
    private static void completedUploadPartialDownload(EmbeddedChannel sc,EmbeddedChannel cc,AtomicLong time) {
        UUID id=UUID.randomUUID();List<ProbeFrame> frames=new ArrayList<>();List<JsonObject> reports=new ArrayList<>();
        NetworkProbeSession server=new NetworkProbeSession(sc,id,true,2_000_000,1_000_000,time::get,
                f->{frames.add(f);return sc.newSucceededFuture();},r->{});
        NetworkProbeSession client=client(cc,id,time,reports);
        server.phase=NetworkProbeSession.Phase.SEND_DOWNLOAD;server.received=2_000_000;server.sent=600_000;
        client.phase=NetworkProbeSession.Phase.RECEIVE_DOWNLOAD;client.received=400_000;
        JsonObject verified=new JsonObject();verified.addProperty("verified_bytes",2_000_000);verified.addProperty("receiver_phase_ns",2_000_000_000L);
        server.phases.add("upload_received",verified);
        JsonObject receipt=new JsonObject();receipt.addProperty("peer_verified_bytes",2_000_000);receipt.addProperty("peer_receive_phase_ns",2_000_000_000L);
        client.phases.add("upload_peer_receipt",receipt);
        time.addAndGet(3_000_000_000L);server.abort("operator_cancelled");
        ProbeFrame failure=frames.get(0);client.accept(failure);
        check(client.peerSummary!=null,"partial download retains matching verified upload receipt");
        var rows=NetworkAnalysisOverview.rows(reports.get(0));
        check(rows.stream().noneMatch(r->r.key().equals("bo.network.partial_upload")),"verified upload is not described as incomplete");
        check(rows.stream().anyMatch(r->r.key().equals("bo.network.partial_download") && r.args().get(0).equals("600.00")),"download submission is distinct from receipt");
        NetworkProbeSession mismatch=client(cc,id,time,new ArrayList<>());mismatch.phases.add("upload_peer_receipt",receipt.deepCopy());
        mismatch.phases.getAsJsonObject("upload_peer_receipt").addProperty("peer_receive_phase_ns",1);
        rejects(mismatch,failure.body(),"inconsistent completed upload receipt");
    }
}
