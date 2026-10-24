package com.PinkCats.bandwidthoptimizer.networkprobe;

import com.google.gson.JsonObject;
import io.netty.channel.embedded.EmbeddedChannel;
import java.nio.ByteBuffer;
import java.util.*;
import java.util.concurrent.atomic.AtomicLong;
import java.util.zip.CRC32;

public final class NetworkProbeRegressionMain {
    private static int checks;
    private static void check(boolean ok,String detail) { checks++; if(!ok) throw new AssertionError(detail); }
    public static void main(String[] args) {
        frames(); lifecycle(); invalid(); pressure(); admission();
        System.out.println("NETWORK_PROBE_PASS checks="+checks);
    }
    private static void frames() {
        UUID id=UUID.randomUUID();
        byte[] data=new byte[ProbeFrame.BLOCK]; new Random(11).nextBytes(data);
        ProbeFrame frame=new ProbeFrame(ProbeFrame.DATA,id,123,0,data);
        byte[] encoded=frame.encode();
        check(Arrays.equals(ProbeFrame.decode(encoded).body(),data),"exact body");
        check(ProbeFrame.decode(encoded).sequence()==123,"sequence");
        data[0]^=1; check(!Arrays.equals(frame.body(),data),"owned input");
        byte[] copy=frame.body(); copy[0]^=1; check(!Arrays.equals(frame.body(),copy),"owned output");
        for(byte[] bad:List.of(new byte[0],new byte[ProbeFrame.MAX_BYTES+1],Arrays.copyOf(encoded,encoded.length-1),
                Arrays.copyOf(encoded,encoded.length+1))) {
            boolean rejected=false; try { ProbeFrame.decode(bad); } catch(IllegalArgumentException expected) { rejected=true; }
            check(rejected,"length rejected");
        }
        encoded[0]=99; boolean rejected=false;
        try { ProbeFrame.decode(encoded); } catch(IllegalArgumentException expected) { rejected=true; }
        check(rejected,"version rejected");
    }
    private static void lifecycle() {
        AtomicLong time=new AtomicLong(1);
        EmbeddedChannel serverChannel=new EmbeddedChannel(), clientChannel=new EmbeddedChannel();
        UUID id=UUID.randomUUID();
        ArrayDeque<ProbeFrame> toClient=new ArrayDeque<>(),toServer=new ArrayDeque<>();
        List<JsonObject> serverReports=new ArrayList<>(),clientReports=new ArrayList<>();
        NetworkProbeSession server=new NetworkProbeSession(serverChannel,id,true,256_000,2_000_000,time::get,
                f -> { toClient.add(ProbeFrame.decode(f.encode())); return serverChannel.newSucceededFuture(); },serverReports::add);
        NetworkProbeSession client=new NetworkProbeSession(clientChannel,id,false,256_000,2_000_000,time::get,
                f -> { toServer.add(ProbeFrame.decode(f.encode())); return clientChannel.newSucceededFuture(); },clientReports::add);
        server.start(); check(toClient.remove().kind()==ProbeFrame.OFFER,"offer");
        client.acceptedOffer();
        for(int i=0;i<3500 && !server.done();i++) {
            time.addAndGet(10_000_000);
            while(!toServer.isEmpty()) server.accept(toServer.remove());
            while(!toClient.isEmpty()) client.accept(toClient.remove());
            server.tick(); client.tick();
        }
        while(!toClient.isEmpty()) client.accept(toClient.remove());
        check(server.done() && client.done(),"both complete");
        check(serverReports.size()==1 && clientReports.size()==1,"one report each");
        JsonObject sr=serverReports.get(0),cr=clientReports.get(0);
        check(sr.get("outcome").getAsString().equals("completed"),sr.toString());
        check(cr.get("outcome").getAsString().equals("completed"),cr.toString());
        check(sr.getAsJsonObject("phases").getAsJsonObject("upload_received").get("verified_bytes").getAsLong()==256_000,"upload exact");
        check(sr.getAsJsonObject("phases").getAsJsonObject("download_peer_receipt").get("peer_verified_bytes").getAsLong()==256_000,"download exact");
        check(sr.get("probe_replies").getAsInt()>0,"ping replies");
        check(server.samples.size()<=350,"window bound");
        check(server.pending==0 && client.pending==0,"no pending buffers");
        server.finish("duplicate"); check(serverReports.size()==1,"finish idempotent");
        serverChannel.finishAndReleaseAll(); clientChannel.finishAndReleaseAll();
    }
    private static void invalid() {
        AtomicLong time=new AtomicLong(1);
        EmbeddedChannel channel=new EmbeddedChannel();
        List<JsonObject> reports=new ArrayList<>();
        UUID id=UUID.randomUUID();
        NetworkProbeSession s=new NetworkProbeSession(channel,id,true,8192,125_000,time::get,
                f -> channel.newSucceededFuture(),reports::add);
        s.accept(ProbeFrame.control(ProbeFrame.READY,UUID.randomUUID()));
        check(s.phase==NetworkProbeSession.Phase.NEGOTIATING,"foreign session ignored");
        s.accept(ProbeFrame.control(ProbeFrame.READY,id)); advance(s,time,101);
        s.accept(new ProbeFrame(ProbeFrame.DATA,id,1,0,new byte[1]));
        check(s.done() && reports.get(0).get("outcome").getAsString().equals("data_sequence_or_budget"),"sequence failure");
        NetworkProbeSession timeout=new NetworkProbeSession(channel,UUID.randomUUID(),true,8192,125_000,time::get,
                f -> channel.newSucceededFuture(),reports::add);
        time.addAndGet(36*NetworkProbeSession.SECOND); timeout.tick();
        check(timeout.done(),"deadline terminates");
        check(!reports.get(1).get("missing_replies_are_packet_loss").getAsBoolean(),"no fake packet loss");
        NetworkProbeSession huge=new NetworkProbeSession(channel,UUID.randomUUID(),true,1,125_000,time::get,
                f -> channel.newSucceededFuture(),reports::add);
        huge.accept(ProbeFrame.control(ProbeFrame.READY,huge.id)); advance(huge,time,101);
        huge.accept(new ProbeFrame(ProbeFrame.DATA,huge.id,0,0,new byte[2]));
        check(huge.done(),"receive budget");
        channel.finishAndReleaseAll();
    }
    private static void advance(NetworkProbeSession session,AtomicLong time,int ticks) {
        for(int i=0;i<ticks;i++) { time.addAndGet(10_000_000); session.tick(); }
    }
    private static void pressure() {
        EmbeddedChannel channel=new EmbeddedChannel();
        AtomicLong time=new AtomicLong(1);
        List<io.netty.channel.ChannelPromise> pending=new ArrayList<>();
        List<JsonObject> reports=new ArrayList<>();
        NetworkProbeSession client=new NetworkProbeSession(channel,UUID.randomUUID(),false,100_000_000,16_000_000,time::get,
                f -> { var promise=channel.newPromise(); pending.add(promise); return promise; },reports::add);
        client.acceptedOffer(); client.accept(ProbeFrame.control(ProbeFrame.UPLOAD,client.id));
        advance(client,time,200);
        check(client.pending<=256*1024,"bounded pending byte budget");
        check(client.sent<300_000 && client.blockedTicks>0,"production stops generating on pending writes");
        client.abort("operator_cancelled");
        check(client.done() && channel.isActive(),"cancel does not close game channel");
        for(var promise:pending) promise.trySuccess();
        check(client.pending==0,"late writes drain after cancellation");
        check(reports.size()==1,"cancel report exactly once");

        NetworkProbeSession bad=new NetworkProbeSession(channel,UUID.randomUUID(),true,8192,125_000,time::get,
                f -> channel.newSucceededFuture(),reports::add);
        bad.accept(ProbeFrame.control(ProbeFrame.READY,bad.id)); advance(bad,time,101);
        bad.accept(new ProbeFrame(ProbeFrame.DATA,bad.id,0,0,new byte[]{7}));
        bad.accept(new ProbeFrame(ProbeFrame.END,bad.id,1,1,ByteBuffer.allocate(8).putLong(0).array()));
        check(bad.done() && reports.get(1).get("outcome").getAsString().equals("end_integrity"),"bad digest rejected");
        check(channel.isActive(),"invalid diagnostic does not close game channel");
        try {
            Class<?> info=Class.forName("io.netty.channel.epoll.EpollTcpInfo");
            for(String getter:List.of("rtt","rttvar","rto","rcvSpace","sndMss","sndCwnd","unacked","retrans","totalRetrans"))
                check(info.getMethod(getter).getParameterCount()==0,"public epoll getter: "+getter);
        } catch(ReflectiveOperationException missing) { throw new AssertionError("TCP metrics public API contract",missing); }
        channel.finishAndReleaseAll();
    }
    private static void admission() {
        UUID one=UUID.randomUUID(),two=UUID.randomUUID();
        check(ProbeAdmission.acquire(one,100,true),"admit first");
        check(!ProbeAdmission.acquire(two,100,true),"global concurrency");
        check(ProbeAdmission.acquire(one,100,false),"integrated client separate role");
        ProbeAdmission.release(two,101,true);
        check(!ProbeAdmission.acquire(two,102,true),"foreign release denied");
        ProbeAdmission.release(one,103,true); ProbeAdmission.release(one,103,false);
        check(!ProbeAdmission.acquire(two,104,true),"cooldown");
        check(ProbeAdmission.acquire(two,104+ProbeAdmission.COOLDOWN,true),"cooldown expiry");
        ProbeAdmission.release(two,105+ProbeAdmission.COOLDOWN,true);
    }
}
