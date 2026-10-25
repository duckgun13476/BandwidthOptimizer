package com.PinkCats.bandwidthoptimizer.networkprobe;

import com.google.gson.JsonObject;
import io.netty.bootstrap.*;
import io.netty.buffer.*;
import io.netty.channel.*;
import io.netty.channel.nio.NioEventLoopGroup;
import io.netty.channel.socket.nio.*;
import io.netty.handler.codec.*;
import java.net.InetSocketAddress;
import java.util.UUID;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicReference;

/** Actual loopback TCP and production session state machine; NOT a native Minecraft/Velocity acceptance test. */
public final class NetworkProbeTcpRegressionMain {
    public static void main(String[] args) throws Exception {
        runCase(false);
        runCase(true);
    }
    private static void runCase(boolean delayedReply) throws Exception {
        EventLoopGroup acceptor=new NioEventLoopGroup(1), workers=new NioEventLoopGroup(2);
        Channel listener=null,client=null;
        AtomicReference<Channel> backend=new AtomicReference<>();
        AtomicReference<JsonObject> sr=new AtomicReference<>(),cr=new AtomicReference<>();
        CountDownLatch finished=new CountDownLatch(2);
        UUID id=UUID.randomUUID();
        try {
            listener=new ServerBootstrap().group(acceptor,workers).channel(NioServerSocketChannel.class)
                    .childHandler(new ChannelInitializer<Channel>() {
                        @Override protected void initChannel(Channel ch) { backend.set(ch); install(ch,id,true,sr,finished,delayedReply); }
                    }).bind("127.0.0.1",0).sync().channel();
            int port=((InetSocketAddress)listener.localAddress()).getPort();
            client=new Bootstrap().group(workers).channel(NioSocketChannel.class)
                    .handler(new ChannelInitializer<Channel>() {
                        @Override protected void initChannel(Channel ch) { install(ch,id,false,cr,finished,delayedReply); }
                    }).connect("127.0.0.1",port).sync().channel();
            if(!finished.await(15,TimeUnit.SECONDS)) throw new AssertionError("TCP deadline");
            for(JsonObject report:new JsonObject[]{sr.get(),cr.get()}) {
                if(report==null || !report.get("outcome").getAsString().equals("completed"))
                    throw new AssertionError(String.valueOf(report));
                if(report.get("netty_in_bytes_including_game").getAsLong()<128_000
                        || report.get("netty_out_bytes_including_game").getAsLong()<128_000)
                    throw new AssertionError("Byte observation missing: "+report);
                JsonObject tcp=report.getAsJsonObject("tcp_end");
                if(WindowsTcpInfo.applicable()) {
                    if(!tcp.get("status").getAsString().equals("supported")) throw new AssertionError("Native TCP not available: "+tcp);
                    if(tcp.get("total_bytes_out").getAsLong()<128_000 || tcp.get("total_bytes_in").getAsLong()<128_000)
                        throw new AssertionError("Native socket bytes not observed: "+tcp);
                } else if(!tcp.get("status").getAsString().equals("unsupported"))
                    throw new AssertionError("Unsupported NIO must not invent TCP counters");
            }
            if(delayedReply) {
                // The exact report must survive the real socket as a bounded binary summary.
                JsonObject baseline=sr.get().getAsJsonObject("analysis").getAsJsonObject("application_rtt_by_phase").getAsJsonObject("BASELINE");
                if(baseline.get("samples").getAsInt()<3 || baseline.get("p50_ns").getAsLong()<60_000_000L)
                    throw new AssertionError("Injected application delay not observed: "+baseline);
                if(!sr.get().get("packet_loss_rate").getAsString().equals("unknown"))
                    throw new AssertionError("Application delay must not be called packet loss");
            }
            if(!"received".equals(cr.get().get("peer_summary_status").getAsString())
                    || !ProbeResultSummary.decode(ProbeResultSummary.encode(sr.get())).equals(cr.get().getAsJsonObject("peer_summary")))
                throw new AssertionError("Server summary did not round-trip exactly");
            System.out.println("NETWORK_PROBE_TCP_PASS java="+Runtime.version()+" delayed_application_reply="+delayedReply+" upload="+
                    sr.get().getAsJsonObject("phases").getAsJsonObject("upload_received")+
                    " download="+sr.get().getAsJsonObject("phases").getAsJsonObject("download_peer_receipt"));
        } finally {
            if(client!=null) client.close().syncUninterruptibly();
            if(backend.get()!=null) backend.get().close().syncUninterruptibly();
            if(listener!=null) listener.close().syncUninterruptibly();
            acceptor.shutdownGracefully(0,2,TimeUnit.SECONDS).syncUninterruptibly();
            workers.shutdownGracefully(0,2,TimeUnit.SECONDS).syncUninterruptibly();
        }
    }
    private static void install(Channel channel,UUID id,boolean server,AtomicReference<JsonObject> report,CountDownLatch finished,boolean delayedReply) {
        AtomicReference<NetworkProbeSession> state=new AtomicReference<>();
        channel.pipeline().addLast("wire",new ChannelDuplexHandler() {
            @Override public void channelRead(ChannelHandlerContext ctx,Object msg) throws Exception {
                if(state.get()!=null && msg instanceof ByteBuf b) state.get().nettyInbound+=b.readableBytes();
                super.channelRead(ctx,msg);
            }
            @Override public void write(ChannelHandlerContext ctx,Object msg,ChannelPromise promise) throws Exception {
                if(state.get()!=null && msg instanceof ByteBuf b) state.get().nettyOutbound+=b.readableBytes();
                super.write(ctx,msg,promise);
            }
        });
        channel.pipeline().addLast(new LengthFieldBasedFrameDecoder(ProbeFrame.MAX_BYTES+4,0,4,0,4));
        channel.pipeline().addLast(new LengthFieldPrepender(4));
        channel.pipeline().addLast(new SimpleChannelInboundHandler<ByteBuf>() {
            private ScheduledFuture<?> tick;
            @Override public void channelActive(ChannelHandlerContext ctx) {
                NetworkProbeSession session=new NetworkProbeSession(channel,id,server,128_000,2_000_000,System::nanoTime,
                        f -> channel.writeAndFlush(Unpooled.wrappedBuffer(f.encode())),
                        result -> { report.set(result); if(tick!=null) tick.cancel(false); finished.countDown(); });
                state.set(session);
                tick=ctx.executor().scheduleWithFixedDelay(session::tick,10,10,TimeUnit.MILLISECONDS);
                if(server) session.start();
            }
            @Override protected void channelRead0(ChannelHandlerContext ctx,ByteBuf buf) {
                byte[] bytes=new byte[buf.readableBytes()]; buf.readBytes(bytes);
                ProbeFrame frame=ProbeFrame.decode(bytes);
                if(!server && frame.kind()==ProbeFrame.OFFER) state.get().acceptedOffer();
                else if(!server && delayedReply && frame.kind()==ProbeFrame.PING)
                    ctx.executor().schedule(() -> state.get().accept(frame),80,TimeUnit.MILLISECONDS);
                else state.get().accept(frame);
            }
            @Override public void channelInactive(ChannelHandlerContext ctx) {
                if(tick!=null) tick.cancel(false);
                if(state.get()!=null) state.get().finish("connection_closed_cause_unknown");
            }
        });
    }
}
