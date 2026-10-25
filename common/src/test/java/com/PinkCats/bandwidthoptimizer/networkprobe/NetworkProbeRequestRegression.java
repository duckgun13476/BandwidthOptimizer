package com.PinkCats.bandwidthoptimizer.networkprobe;

import io.netty.channel.embedded.EmbeddedChannel;
import java.nio.ByteBuffer;
import java.util.*;
import java.util.concurrent.TimeUnit;

final class NetworkProbeRequestRegression {
    private static int checks;
    private static void check(boolean ok,String reason) { checks++; if(!ok) throw new AssertionError(reason); }
    private static void available(boolean server) {
        UUID id=UUID.randomUUID(); long now=System.nanoTime();
        if(!ProbeAdmission.acquire(id,now+TimeUnit.HOURS.toNanos(1),server)) throw new AssertionError("Leaked admission owner");
        ProbeAdmission.release(id,now-ProbeAdmission.COOLDOWN,server);
    }
    private static ProbeFrame offer(UUID id) {
        return new ProbeFrame(ProbeFrame.OFFER,id,1,NetworkProbeService.BASIC_BYTES,
                ByteBuffer.allocate(8).putLong(NetworkProbeService.BASIC_RATE).array());
    }
    static void run() {
        available(true); available(false);
        EmbeddedChannel client=new EmbeddedChannel(),server=new EmbeddedChannel();
        ArrayDeque<ProbeFrame> toClient=new ArrayDeque<>(),toServer=new ArrayDeque<>();
        var cs=(java.util.function.Function<ProbeFrame,io.netty.channel.ChannelFuture>)(f -> {toServer.add(f); return client.newSucceededFuture();});
        var ss=(java.util.function.Function<ProbeFrame,io.netty.channel.ChannelFuture>)(f -> {toClient.add(f); return server.newSucceededFuture();});
        try {
            NetworkProbeRequest request=NetworkProbeService.request(client,cs); client.runPendingTasks();
            check(request.status().equals("waiting_for_authorization"),"request waits without bulk session");
            check(NetworkProbeService.current(client)==null && toServer.peek().kind()==ProbeFrame.REQUEST,"no client data before authority");
            var duplicate=NetworkProbeService.request(client,cs); client.runPendingTasks();
            check(duplicate.status().equals("already_running") && toServer.size()==1,"duplicate local request bounded");
            NetworkProbeService.receive(server,true,toServer.remove().encode(),ss);
            ProbeFrame granted=toClient.remove();
            check(granted.kind()==ProbeFrame.OFFER && granted.sequence()==1 && granted.id().equals(request.id()),"authorization identity");
            check(granted.value()==2_000_000 && ByteBuffer.wrap(granted.body()).getLong()==1_000_000,"server owns budget and rate");
            check(NetworkProbeService.current(server).sent==0,"no bulk before READY");
            NetworkProbeService.receive(client,false,granted.encode(),cs);
            check(request.status().equals("running") && toServer.remove().kind()==ProbeFrame.READY,"valid grant starts client");
            NetworkProbeService.closed(client,"test_closed"); NetworkProbeService.closed(server,"test_closed");
            check(request.status().equals("test_closed"),"completion updates published ticket");
            available(false); toServer.clear();
            var cancelled=NetworkProbeService.request(client,cs); client.runPendingTasks();
            NetworkProbeService.closed(client,"backend_login_replaced_session");
            NetworkProbeService.receive(client,false,offer(cancelled.id()).encode(),cs);
            check(NetworkProbeService.current(client)==null,"late grant after backend replacement ignored");
            available(false);
            var next=NetworkProbeService.request(client,cs); client.runPendingTasks();
            NetworkProbeService.receive(client,false,offer(cancelled.id()).encode(),cs);
            check(next.waiting() && NetworkProbeService.current(client)==null,"old grant cannot start new request");
            NetworkProbeService.cancel(client,cancelled.id(),cs); client.runPendingTasks();
            check(next.waiting(),"old UI ticket cannot cancel current request");
            client.advanceTimeBy(6,TimeUnit.SECONDS); client.runScheduledPendingTasks();
            check(next.status().equals("authorization_timeout"),"authorization deadline explicit");
            NetworkProbeService.receive(client,false,offer(next.id()).encode(),cs);
            check(NetworkProbeService.current(client)==null,"late grant after timeout ignored");
            available(false);
            var mismatch=NetworkProbeService.request(client,cs); client.runPendingTasks();
            ProbeFrame valid=offer(mismatch.id());
            NetworkProbeService.receive(client,false,new ProbeFrame(ProbeFrame.OFFER,mismatch.id(),1,valid.value()+1,valid.body()).encode(),cs);
            check(mismatch.status().equals("invalid_authorization") && NetworkProbeService.current(client)==null,"single-field budget mismatch refused");
            available(false);
            var denied=NetworkProbeService.request(client,cs); client.runPendingTasks();
            NetworkProbeService.receive(client,false,new ProbeFrame(ProbeFrame.STATUS,UUID.randomUUID(),2,0,new byte[0]).encode(),cs);
            check(denied.waiting(),"foreign rejection ignored");
            NetworkProbeService.receive(client,false,new ProbeFrame(ProbeFrame.STATUS,denied.id(),2,0,new byte[0]).encode(),cs);
            check(denied.status().equals("server_disabled"),"explicit denied state");
            available(false);
            var cancelledByUser=NetworkProbeService.request(client,cs); client.runPendingTasks(); toServer.clear();
            NetworkProbeService.cancel(client,cancelledByUser.id(),cs); client.runPendingTasks();
            check(cancelledByUser.status().equals("cancelled") && toServer.remove().kind()==ProbeFrame.CANCEL,"pending cancellation sends bounded control");
            NetworkProbeService.receive(client,false,offer(cancelledByUser.id()).encode(),cs);
            check(NetworkProbeService.current(client)==null,"cancelled request cannot start late");
            available(false);
            var crossed=NetworkProbeService.request(client,cs); client.runPendingTasks(); toServer.clear();
            UUID operatorId=UUID.randomUUID();
            NetworkProbeService.receive(client,false,new ProbeFrame(ProbeFrame.OFFER,operatorId,0,8192,
                    ByteBuffer.allocate(8).putLong(125_000).array()).encode(),cs);
            check(crossed.status().equals("superseded_by_operator") && NetworkProbeService.current(client).id.equals(operatorId),"crossing operator offer receives reservation");
            check(toServer.remove().kind()==ProbeFrame.READY,"crossing operator offer does not stall awaiting READY");
            NetworkProbeService.cancel(client,crossed.id(),cs); client.runPendingTasks();
            check(NetworkProbeService.current(client)!=null,"stale requested ticket cannot cancel operator session");
            NetworkProbeService.closed(client,"test_closed");
            toClient.clear();
            NetworkProbeService.receive(server,true,ProbeFrame.control(ProbeFrame.REQUEST,UUID.randomUUID()).encode(),ss);
            check(toClient.isEmpty() && NetworkProbeService.current(server)==null,"same-connection request rate bounded");
            available(true);
            EmbeddedChannel disabled=new EmbeddedChannel();
            String old=System.getProperty("bandwidthoptimizer.networkProbe.clientRequests");
            try {
                System.setProperty("bandwidthoptimizer.networkProbe.clientRequests","false");
                NetworkProbeService.receive(disabled,true,ProbeFrame.control(ProbeFrame.REQUEST,UUID.randomUUID()).encode(),ss);
                check(toClient.remove().sequence()==2 && NetworkProbeService.current(disabled)==null,"server policy refuses before allocation");
            } finally {
                if(old==null) System.clearProperty("bandwidthoptimizer.networkProbe.clientRequests"); else System.setProperty("bandwidthoptimizer.networkProbe.clientRequests",old);
                disabled.finishAndReleaseAll();
            }
            EmbeddedChannel invalid=new EmbeddedChannel();
            try {
                NetworkProbeService.receive(invalid,true,new ProbeFrame(ProbeFrame.REQUEST,UUID.randomUUID(),0,1,new byte[0]).encode(),ss);
                check(toClient.isEmpty() && NetworkProbeService.current(invalid)==null,"request cannot supply byte budget");
                UUID owner=UUID.randomUUID(); check(ProbeAdmission.acquire(owner,System.nanoTime(),true),"invalid request did not acquire global slot");
                NetworkProbeService.receive(invalid,true,ProbeFrame.control(ProbeFrame.REQUEST,UUID.randomUUID()).encode(),ss);
                check(toClient.remove().sequence()==1 && NetworkProbeService.current(invalid)==null,"global busy explicit without allocating session");
                ProbeAdmission.release(owner,System.nanoTime(),true);
            } finally { invalid.finishAndReleaseAll(); }
        } finally {
            NetworkProbeService.closed(client,"test_cleanup"); NetworkProbeService.closed(server,"test_cleanup");
            client.finishAndReleaseAll(); server.finishAndReleaseAll();
        }
        available(true); available(false);
        System.out.println("NETWORK_PROBE_REQUEST_PASS checks="+checks);
    }
}
