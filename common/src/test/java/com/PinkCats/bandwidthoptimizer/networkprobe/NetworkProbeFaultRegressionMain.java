package com.PinkCats.bandwidthoptimizer.networkprobe;

import com.google.gson.JsonObject;
import io.netty.bootstrap.*;
import io.netty.buffer.*;
import io.netty.channel.*;
import io.netty.channel.epoll.*;
import io.netty.handler.codec.*;
import java.net.InetSocketAddress;
import java.nio.file.*;
import java.lang.reflect.Field;
import java.util.UUID;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicReference;

/** Linux-only isolated kernel fault experiment. Production probe session and real epoll TCP_INFO. */
public final class NetworkProbeFaultRegressionMain {
    public static void main(String[] args) throws Exception {
        requireLinuxTcpInfoLoaded();
        if(!Epoll.isAvailable()) throw new IllegalStateException("epoll required",Epoll.unavailabilityCause());
        Path root=Path.of(args[0]).toAbsolutePath().normalize();
        if(!Files.isDirectory(root) || Files.exists(root.resolve("result.json"))) throw new IllegalArgumentException("Fresh output required");
        String scenario=args[1];
        EventLoopGroup acceptor=new EpollEventLoopGroup(1),workers=new EpollEventLoopGroup(2);
        AtomicReference<NetworkProbeSession> ss=new AtomicReference<>();
        AtomicReference<Channel> peer=new AtomicReference<>();
        AtomicReference<JsonObject> sr=new AtomicReference<>(),cr=new AtomicReference<>();
        CountDownLatch finished=new CountDownLatch(2);
        Channel listener=null,client=null;
        try {
            UUID id=UUID.randomUUID();
            listener=new ServerBootstrap().group(acceptor,workers).channel(EpollServerSocketChannel.class)
                    .childOption(ChannelOption.SO_SNDBUF,65536).childOption(ChannelOption.SO_RCVBUF,65536)
                    .childHandler(new ChannelInitializer<Channel>() {
                        protected void initChannel(Channel ch) { peer.set(ch); install(ch,id,true,sr,finished,ss); }
                    }).bind("127.0.0.1",25991).sync().channel();
            client=new Bootstrap().group(workers).channel(EpollSocketChannel.class)
                    .option(ChannelOption.SO_SNDBUF,65536).option(ChannelOption.SO_RCVBUF,65536)
                    .handler(new ChannelInitializer<Channel>() {
                        protected void initChannel(Channel ch) { install(ch,id,false,cr,finished,new AtomicReference<>()); }
                    }).connect(new InetSocketAddress("127.0.0.1",25991)).sync().channel();
            long activeDeadline=System.nanoTime()+TimeUnit.SECONDS.toNanos(3);
            while(ss.get()==null) {
                if(System.nanoTime()>activeDeadline) throw new AssertionError("Server channelActive missing");
                Thread.sleep(1);
            }
            // TCP established before impairment. Main thread owns file IO; never stalls the session event loop.
            Files.writeString(root.resolve("ready"),id.toString(),StandardOpenOption.CREATE_NEW);
            long deadline=System.nanoTime()+TimeUnit.SECONDS.toNanos(15);
            while(!Files.exists(root.resolve("go"))) {
                if(System.nanoTime()>deadline) throw new AssertionError("Injector did not arm");
                Thread.sleep(10);
            }
            Channel backend=peer.get();
            backend.eventLoop().execute(() -> ss.get().start());
            boolean uploadMarked=false,reset=false;
            deadline=System.nanoTime()+TimeUnit.SECONDS.toNanos(40);
            while(finished.getCount()>0 && System.nanoTime()<deadline) {
                // The fixture asks the owner event loop for its phase instead of racing mutable production fields.
                String phase=backend.eventLoop().submit(() -> ss.get().phase.name()).get(2,TimeUnit.SECONDS);
                if(!uploadMarked && phase.equals("RECEIVE_UPLOAD")) {
                    Files.writeString(root.resolve("upload"),"observed",StandardOpenOption.CREATE_NEW); uploadMarked=true;
                }
                if(scenario.equals("reset") && uploadMarked && !reset) {
                    reset=true;
                    backend.eventLoop().schedule(() -> {
                        backend.config().setOption(ChannelOption.SO_LINGER,0); backend.close();
                    },200,TimeUnit.MILLISECONDS);
                }
                Thread.sleep(10);
            }
            if(finished.getCount()!=0) throw new AssertionError("Probe did not terminate");
            long serverRetrans=assertLinuxTcpInfo(sr.get(),"server",scenario);
            long clientRetrans=assertLinuxTcpInfo(cr.get(),"client",scenario);
            if(scenario.startsWith("loss") && serverRetrans+clientRetrans<=0)
                throw new AssertionError("Injected loss produced no TCP retransmission on either exact probe socket");
            JsonObject peerSummary=cr.get().getAsJsonObject("peer_summary");
            JsonObject encoded=null;
            if("completed".equals(sr.get().get("outcome").getAsString()) && "completed".equals(cr.get().get("outcome").getAsString())) {
                if(peerSummary==null || !"received".equals(cr.get().get("peer_summary_status").getAsString()))
                    throw new AssertionError("Client did not decode the server result summary");
                assertLinuxTrace(peerSummary.getAsJsonObject("trace"),"peer summary");
                encoded=ProbeResultSummary.decode(ProbeResultSummary.encode(sr.get()));
                assertLinuxTrace(encoded.getAsJsonObject("trace"),"summary encode/decode round trip");
            }
            NetworkProbeResults.Store retained=new NetworkProbeResults.Store();
            UUID testId=UUID.fromString(sr.get().get("test_id").getAsString());
            NetworkProbeResults.Key key=new NetworkProbeResults.Key("fault-fixture",testId,true);
            retained.record(key,sr.get());
            JsonObject localTrace=retained.find(key).json().getAsJsonObject("trace");
            assertLinuxTrace(localTrace,"local retained results");
            JsonObject result=new JsonObject(); result.addProperty("scenario",scenario);
            result.add("server",sr.get()); result.add("client",cr.get());
            result.add("server_peer_summary",peerSummary);
            result.add("server_summary_round_trip",encoded);
            result.add("server_retained_trace",localTrace);
            Files.writeString(root.resolve("result.json"),result.toString(),StandardOpenOption.CREATE_NEW);
            System.out.println("FAULT_PROBE_FINISHED "+scenario);
        } finally {
            if(client!=null) client.close().syncUninterruptibly();
            if(peer.get()!=null) peer.get().close().syncUninterruptibly();
            if(listener!=null) listener.close().syncUninterruptibly();
            acceptor.shutdownGracefully(0,2,TimeUnit.SECONDS).syncUninterruptibly();
            workers.shutdownGracefully(0,2,TimeUnit.SECONDS).syncUninterruptibly();
        }
    }
    private static void requireLinuxTcpInfoLoaded() throws Exception {
        if(!System.getProperty("os.name","").equals("Linux")) throw new AssertionError("Linux required");
        LinuxTcpInfo.prepare();
        Field loaded=LinuxTcpInfo.class.getDeclaredField("loaded");
        loaded.setAccessible(true);
        long deadline=System.nanoTime()+TimeUnit.SECONDS.toNanos(3);
        while(!loaded.getBoolean(null) && System.nanoTime()<deadline) Thread.sleep(10);
        if(!loaded.getBoolean(null)) throw new AssertionError("LinuxTcpInfo JNI loader did not load within 3s");
    }
    private static long assertLinuxTcpInfo(JsonObject report,String role,String scenario) {
        for(String key:new String[]{"tcp_start","tcp_last_observed"}) {
            JsonObject sample=report.getAsJsonObject(key);
            if(sample==null || !sample.has("total_sent_segments") || !sample.has("total_retransmitted_segments")
                    || !sample.has("retransmission_rate_basis")
                    || !sample.get("retransmission_rate_basis").getAsString().equals("data_segments_including_retransmissions"))
                throw new AssertionError(role+" missing Linux TCP_INFO counters at "+key+": "+sample);
        }
        JsonObject analysis=report.getAsJsonObject("analysis");
        if(analysis==null || !analysis.has("local_tcp_sent_segments_delta")
                || analysis.get("local_tcp_sent_segments_delta").getAsLong()<=0)
            throw new AssertionError(role+" has no positive sent-segment delta: "+analysis);
        long retrans=analysis.has("local_tcp_retransmitted_segments_delta")
                ? analysis.get("local_tcp_retransmitted_segments_delta").getAsLong() : 0;
        if(scenario.equals("baseline") && retrans!=0)
            throw new AssertionError(role+" baseline retransmissions expected 0, got "+retrans);
        return retrans;
    }
    private static void assertLinuxTrace(JsonObject trace,String source) {
        if(trace==null || trace.get("tcp_unit").getAsInt()!=2 || trace.getAsJsonArray("points").isEmpty())
            throw new AssertionError(source+" missing segment-based trace: "+trace);
    }
    private static void install(Channel ch,UUID id,boolean server,AtomicReference<JsonObject> report,
                                CountDownLatch finished,AtomicReference<NetworkProbeSession> state) {
        ch.pipeline().addLast("wire",new ChannelDuplexHandler() {
            public void channelRead(ChannelHandlerContext ctx,Object msg) throws Exception {
                if(state.get()!=null && msg instanceof ByteBuf b) state.get().nettyInbound+=b.readableBytes();
                super.channelRead(ctx,msg);
            }
            public void write(ChannelHandlerContext ctx,Object msg,ChannelPromise promise) throws Exception {
                if(state.get()!=null && msg instanceof ByteBuf b) state.get().nettyOutbound+=b.readableBytes();
                super.write(ctx,msg,promise);
            }
        });
        ch.pipeline().addLast(new LengthFieldBasedFrameDecoder(ProbeFrame.MAX_BYTES+4,0,4,0,4));
        ch.pipeline().addLast(new LengthFieldPrepender(4));
        ch.pipeline().addLast(new SimpleChannelInboundHandler<ByteBuf>() {
            ScheduledFuture<?> tick;
            public void channelActive(ChannelHandlerContext ctx) {
                state.set(new NetworkProbeSession(ch,id,server,2_000_000,1_000_000,System::nanoTime,
                        f -> ch.writeAndFlush(Unpooled.wrappedBuffer(f.encode())),r -> {
                            report.set(r); if(tick!=null) tick.cancel(false); finished.countDown();
                        }));
                tick=ctx.executor().scheduleWithFixedDelay(state.get()::tick,10,10,TimeUnit.MILLISECONDS);
            }
            protected void channelRead0(ChannelHandlerContext ctx,ByteBuf bytes) {
                byte[] data=new byte[bytes.readableBytes()]; bytes.readBytes(data);
                ProbeFrame frame=ProbeFrame.decode(data);
                if(!server && frame.kind()==ProbeFrame.OFFER) state.get().acceptedOffer();
                else state.get().accept(frame);
            }
            public void channelInactive(ChannelHandlerContext ctx) {
                if(tick!=null) tick.cancel(false);
                if(state.get()!=null) state.get().finish("connection_closed_cause_unknown");
            }
            public void exceptionCaught(ChannelHandlerContext ctx,Throwable failure) {
                state.get().lastError=failure.getClass().getName();
                // Preserve actual socket exception in the same classifier used by the game.
                com.PinkCats.bandwidthoptimizer.connection.ConnectionDisconnectClassifier.observeException(ch,failure);
                ctx.close();
            }
        });
    }
}
