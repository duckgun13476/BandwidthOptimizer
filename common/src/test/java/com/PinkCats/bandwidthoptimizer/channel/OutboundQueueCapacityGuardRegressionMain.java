package com.PinkCats.bandwidthoptimizer.channel;

import com.PinkCats.bandwidthoptimizer.diag.OutboundFloodAlarm;
import io.netty.buffer.Unpooled;
import io.netty.channel.*;
import io.netty.channel.embedded.EmbeddedChannel;
import io.netty.util.ReferenceCountUtil;
import java.util.ArrayList;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.TimeUnit;

public final class OutboundQueueCapacityGuardRegressionMain {
    static final class Sink extends ChannelDuplexHandler {
        final ArrayList<Object> messages = new ArrayList<>();
        final ArrayList<ChannelPromise> promises = new ArrayList<>();
        boolean drain;
        Sink(boolean drain) { this.drain = drain; }
        public void write(ChannelHandlerContext ctx, Object message, ChannelPromise promise) {
            if (drain) { ReferenceCountUtil.release(message); promise.trySuccess(); }
            else { messages.add(message); promises.add(promise); }
        }
        public void close(ChannelHandlerContext ctx, ChannelPromise promise) {
            for (Object message : messages) ReferenceCountUtil.release(message);
            for (ChannelPromise pending : promises) pending.tryFailure(new java.io.IOException("closed"));
            messages.clear(); promises.clear(); ctx.close(promise);
        }
        void drain() {
            for (Object message : messages) ReferenceCountUtil.release(message);
            for (ChannelPromise pending : promises) pending.trySuccess();
            messages.clear(); promises.clear();
        }
    }
    public static void main(String[] args) {
        var lines = new ArrayList<String>();
        var global = new AtomicLong();
        var sources = new OutboundFloodAlarm();
        long second = TimeUnit.NANOSECONDS.toSeconds(System.nanoTime());
        for (int i = 0; i < 10000; i++) sources.submitted("class" + i, 8, second);
        for (int i = 0; i < 30; i++) sources.submitted("packet payloadChannel=vss:main", 2577531, second);
        var sink = new Sink(false);
        var channel = new EmbeddedChannel(sink, new OutboundQueueCapacityGuard(sources, lines::add, 1024, 4096, global));
        var first = Unpooled.buffer(500).writeZero(500);
        var firstPromise = channel.newPromise();
        channel.pipeline().write(first, firstPromise);
        check(!firstPromise.isDone() && global.get() == 596, "unfinished admission accounting");
        var rejected = Unpooled.buffer(500).writeZero(500);
        var rejectedPromise = channel.newPromise();
        channel.pipeline().write(rejected, rejectedPromise);
        check(!channel.isOpen() && rejectedPromise.isDone() && !rejectedPromise.isSuccess(), "capacity must close/fail");
        check(first.refCnt() == 0 && rejected.refCnt() == 0 && global.get() == 0, "close must release/refund");
        check(lines.size() == 1 && lines.get(0).contains("source=vss:main")
                && lines.get(0).contains("action=close_connection"), "source/reason missing");
        channel.finishAndReleaseAll();
        lines.clear();
        var normal = new EmbeddedChannel(new Sink(true), new OutboundQueueCapacityGuard(sources, lines::add, 1024, 4096, global));
        for (int i = 0; i < 10000; i++) normal.pipeline().write(Unpooled.buffer(500).writeZero(500), normal.newPromise());
        check(normal.isOpen() && lines.isEmpty() && global.get() == 0, "large draining traffic falsely blocked");
        normal.finishAndReleaseAll();
        var aSink = new Sink(false);
        var a = new EmbeddedChannel(aSink, new OutboundQueueCapacityGuard(sources, lines::add, 2048, 1024, global));
        var b = new EmbeddedChannel(new Sink(false), new OutboundQueueCapacityGuard(sources, lines::add, 2048, 1024, global));
        a.pipeline().write(Unpooled.buffer(500).writeZero(500), a.newPromise());
        b.pipeline().write(Unpooled.buffer(500).writeZero(500), b.newPromise());
        check(a.isOpen() && !b.isOpen() && global.get() == 596, "global cap must leave other connection intact");
        aSink.drain();
        check(global.get() == 0, "successful completion refund");
        var cancellation = a.newPromise();
        a.pipeline().write(Unpooled.buffer(500).writeZero(500), cancellation);
        cancellation.cancel(false);
        check(global.get() == 596, "caller cancellation refunded still-held bytes");
        aSink.drain();
        check(global.get() == 0, "cancelled caller actual completion refund");
        a.finishAndReleaseAll(); b.finishAndReleaseAll();
        var huge = new EmbeddedChannel(new Sink(false), new OutboundQueueCapacityGuard(sources, lines::add, 1024, 4096, global));
        var bytes = Unpooled.buffer(2048).writeZero(2048);
        huge.pipeline().write(bytes, huge.voidPromise());
        check(!huge.isOpen() && bytes.refCnt() == 0 && global.get() == 0, "oversized void-promise cleanup");
        // EmbeddedChannel records the void-promise failure as an inbound exception.
        try { huge.finishAndReleaseAll(); } catch (Exception expected) { }
        nativeQueue(sources);
        System.out.println("OUTBOUND_QUEUE_CAPACITY_REGRESSION_OK late_source blocked draining global refund oversized");
    }
    static void nativeQueue(OutboundFloodAlarm sources) {
        var group = new io.netty.channel.nio.NioEventLoopGroup(1);
        Channel listener = null;
        var accepted = new java.util.concurrent.CompletableFuture<Channel>();
        var global = new AtomicLong();
        var lines = new java.util.concurrent.CopyOnWriteArrayList<String>();
        var buffers = new java.util.concurrent.CopyOnWriteArrayList<io.netty.buffer.ByteBuf>();
        try {
            listener = new io.netty.bootstrap.ServerBootstrap().group(group)
                    .channel(io.netty.channel.socket.nio.NioServerSocketChannel.class)
                    .childHandler(new ChannelInitializer<io.netty.channel.socket.SocketChannel>() {
                        protected void initChannel(io.netty.channel.socket.SocketChannel channel) {
                            channel.pipeline().addLast(new OutboundQueueCapacityGuard(sources, lines::add,
                                    4L * 1024 * 1024, 16L * 1024 * 1024, global));
                            accepted.complete(channel);
                        }
                    }).bind("127.0.0.1", 0).syncUninterruptibly().channel();
            try (var peer = new java.net.Socket()) {
                peer.connect(listener.localAddress());
                Channel connection = accepted.get(5, java.util.concurrent.TimeUnit.SECONDS);
                connection.eventLoop().submit(() -> {
                    // Reproduce two VSS-sized submissions before the socket drains. Exercise
                    // real ChannelOutboundBuffer failure/refcount cleanup, not only a fake sink.
                    for (int i = 0; i < 2; i++) {
                        var bytes = Unpooled.buffer(2577531).writeZero(2577531);
                        buffers.add(bytes);
                        connection.write(bytes, connection.newPromise());
                    }
                    connection.flush();
                }).syncUninterruptibly();
                check(connection.closeFuture().awaitUninterruptibly(5000), "native queue did not close");
                connection.eventLoop().submit(() -> {}).syncUninterruptibly();
                check(global.get() == 0 && buffers.stream().allMatch(buffer -> buffer.refCnt() == 0),
                        "native queue retained buffers or charges");
                check(lines.size() == 1 && lines.get(0).contains("connection_capacity"), "native close reason");
            }
        } catch (Exception failure) {
            throw new AssertionError("native queue regression", failure);
        } finally {
            if (listener != null) listener.close().syncUninterruptibly();
            group.shutdownGracefully(0, 2, java.util.concurrent.TimeUnit.SECONDS).syncUninterruptibly();
        }
    }
    static void check(boolean ok, String message) { if (!ok) throw new AssertionError(message); }
}
