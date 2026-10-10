package com.PinkCats.bandwidthoptimizer.channel.algorithm.batch;

import com.PinkCats.bandwidthoptimizer.channel.*;

import io.netty.bootstrap.Bootstrap;
import io.netty.bootstrap.ServerBootstrap;
import io.netty.buffer.ByteBuf;
import io.netty.buffer.Unpooled;
import io.netty.channel.*;
import io.netty.channel.nio.NioEventLoopGroup;
import io.netty.channel.socket.SocketChannel;
import io.netty.channel.socket.nio.NioServerSocketChannel;
import io.netty.channel.socket.nio.NioSocketChannel;
import io.netty.handler.codec.LengthFieldBasedFrameDecoder;
import io.netty.handler.codec.MessageToByteEncoder;
import io.netty.handler.traffic.ChannelTrafficShapingHandler;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.protocol.Packet;
import net.minecraft.network.protocol.PacketFlow;

import java.net.InetSocketAddress;
import java.nio.ByteBuffer;
import java.security.MessageDigest;
import java.util.Random;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;

/** Real local TCP backlog, real BO batching/compression, deterministic source and exact ordered recovery. */
public final class OutboundFloodRegressionMain {
    private static final int PROFILE_BYTES = 2_643_050;
    private static final int PART_BYTES = 512 * 1024;
    private static final int MAX_PROFILES = 150;

    public static void main(String[] args) throws Exception {
        int profiles = bounded("profiles", 150, 4, MAX_PROFILES);
        int interval = bounded("intervalMillis", 360, 0, 1000);
        int pauseMillis = bounded("pauseMillis", 56000, 1000, 160000);
        int readRate = bounded("readBytesPerSecond", 8 * 1024 * 1024, 1024 * 1024, 64 * 1024 * 1024);
        String variant = System.getProperty("boFlood.variant", "optimized");
        boolean baseline = ChannelTransportBatchManager.class.getProtectionDomain().getCodeSource().getLocation()
                .toString().contains("outbound-flood-baseline-classes");
        require(baseline == variant.startsWith("baseline-"), "Wrong batch implementation on test classpath");
        require(!Boolean.parseBoolean(System.getProperty("bandwidthoptimizer.streamingZstd", "true")),
                "Fixture requires independent batch frames; streaming acknowledgement is a separate acceptance test");
        NioEventLoopGroup group = new NioEventLoopGroup(2);
        AtomicReference<SocketChannel> sender = new AtomicReference<>();
        AtomicReference<Throwable> failure = new AtomicReference<>();
        CountDownLatch accepted = new CountDownLatch(1);
        AtomicLong receivedBytes = new AtomicLong(), receivedPackets = new AtomicLong();
        AtomicInteger peaksUnwritable = new AtomicInteger();
        AtomicLong peakPending = new AtomicLong(), peakHeap = new AtomicLong();
        MessageDigest expected = MessageDigest.getInstance("SHA-256"), actual = MessageDigest.getInstance("SHA-256");
        Channel server = null, receiver = null;
        ChannelTransportSession decodeSession = new ChannelTransportSession();
        ChannelTransportFragmentReassembler fragments = new ChannelTransportFragmentReassembler();
        ChannelTrafficShapingHandler shaping = new ChannelTrafficShapingHandler(0, readRate, 100);
        long started = System.nanoTime(), submittedBytes = 0, submittedPackets = 0;
        ScheduledFuture<?> sampler = null;
        try {
            server = new ServerBootstrap().group(group).channel(NioServerSocketChannel.class)
                    .childOption(ChannelOption.SO_SNDBUF, 64 * 1024)
                    .childOption(ChannelOption.WRITE_BUFFER_WATER_MARK, new WriteBufferWaterMark(64 * 1024, 128 * 1024))
                    .childHandler(new ChannelInitializer<SocketChannel>() {
                        @Override protected void initChannel(SocketChannel channel) {
                            channel.pipeline().addLast("encoder", new MessageToByteEncoder<Packet<?>>() {
                                @Override protected void encode(ChannelHandlerContext context, Packet<?> packet, ByteBuf out) {
                                    int start = out.writerIndex();
                                    out.writeInt(0);
                                    packet.write(new FriendlyByteBuf(out));
                                    out.setInt(start, out.writerIndex() - start - 4);
                                }
                            });
                            sender.set(channel);
                            accepted.countDown();
                        }
                    }).bind(new InetSocketAddress("127.0.0.1", 0)).sync().channel();
            receiver = new Bootstrap().group(group).channel(NioSocketChannel.class)
                    .option(ChannelOption.AUTO_READ, false).option(ChannelOption.SO_RCVBUF, 64 * 1024)
                    .handler(new ChannelInitializer<SocketChannel>() {
                        @Override protected void initChannel(SocketChannel channel) {
                            channel.pipeline().addLast("slowReader", shaping);
                            channel.pipeline().addLast(new LengthFieldBasedFrameDecoder(2 * 1024 * 1024, 0, 4, 0, 4));
                            channel.pipeline().addLast(new SimpleChannelInboundHandler<ByteBuf>() {
                                @Override protected void channelRead0(ChannelHandlerContext context, ByteBuf message) {
                                    try {
                                        FriendlyByteBuf data = new FriendlyByteBuf(message);
                                        require(data.readResourceLocation().equals(ChannelTransportNetworkChannel.TRANSPORT_PAYLOAD_ID), "Unexpected carrier ID");
                                        byte[] bytes = new byte[data.readableBytes()];
                                        data.readBytes(bytes);
                                        var fragment = fragments.accept(bytes);
                                        if (fragment.kind() == ChannelTransportFragmentReassembler.ReceiveResult.Kind.INCOMPLETE) return;
                                        byte[] frame = fragment.kind() == ChannelTransportFragmentReassembler.ReceiveResult.Kind.COMPLETE
                                                ? fragment.transportFrameBytes() : bytes;
                                        var restored = ChannelTransportPacketCodec.tryUnwrapPacket(decodeSession, frame);
                                        require(restored != null, "Carrier not decoded by BO");
                                        for (byte[] packet : restored.restoredPacketBytesList()) {
                                            actual.update(packet);
                                            receivedBytes.addAndGet(packet.length);
                                            receivedPackets.incrementAndGet();
                                        }
                                    } catch (Throwable error) {
                                        failure.compareAndSet(null, error);
                                        context.close();
                                    }
                                }
                                @Override public void exceptionCaught(ChannelHandlerContext context, Throwable error) {
                                    failure.compareAndSet(null, error);
                                    context.close();
                                }
                            });
                        }
                    }).connect(server.localAddress()).sync().channel();
            require(accepted.await(10, TimeUnit.SECONDS), "Loopback connection not accepted");
            SocketChannel sending = sender.get();
            ChannelHandlerContext encoder = sending.pipeline().context("encoder");
            sampler = sending.eventLoop().scheduleAtFixedRate(() -> {
                var buffer = sending.unsafe().outboundBuffer();
                long pending = buffer == null ? 0 : buffer.totalPendingWriteBytes();
                peakPending.accumulateAndGet(pending, Math::max);
                peakHeap.accumulateAndGet(heapUsed(), Math::max);
                if (!sending.isWritable()) peaksUnwritable.incrementAndGet();
            }, 0, 100, TimeUnit.MILLISECONDS);
            System.out.printf("FLOOD start variant=%s profiles=%d profileBytes=%d intervalMillis=%d pauseMillis=%d readBytesPerSecond=%d independentFrames=true%n",
                    variant, profiles, PROFILE_BYTES, interval, pauseMillis, readRate);
            for (int profile = 0; profile < profiles; profile++) {
                if (failure.get() != null) throw new AssertionError("Receiver failed", failure.get());
                for (int offset = 0; offset < PROFILE_BYTES; offset += PART_BYTES) {
                    int length = Math.min(PART_BYTES, PROFILE_BYTES - offset);
                    byte[] packet = new byte[length + 16];
                    new Random(0xB0_134L + profile * 65537L + offset).nextBytes(packet);
                    ByteBuffer.wrap(packet).putInt(profile).putInt(offset).putInt(PROFILE_BYTES).putInt(length);
                    expected.update(packet);
                    submittedBytes += packet.length;
                    submittedPackets++;
                    byte[] ownedByProducer = packet;
                    sending.eventLoop().submit(() -> require(ChannelTransportBatchManager.enqueueOutboundPacket(
                            encoder, ownedByProducer, ownedByProducer, PacketFlow.CLIENTBOUND, null, null, null), "Batch admission failed")).sync();
                }
                if (interval > 0) Thread.sleep(interval);
            }
            // A sensitive boundary commits all remaining packets before measuring the stopped reader.
            sending.eventLoop().submit(() -> ChannelTransportBatchManager.flushOutboundBatchNow(encoder)).sync();
            long remainingPause = pauseMillis - TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - started);
            if (remainingPause > 0) Thread.sleep(remainingPause);
            System.gc();
            long liveAtPause = heapUsed();
            var paused = sending.eventLoop().submit(() -> sending.unsafe().outboundBuffer().totalPendingWriteBytes()).sync().getNow();
            require(paused > 1024 * 1024, "No real socket backlog was created");
            require(peaksUnwritable.get() > 0, "Socket never crossed Netty high watermark");
            require(receivedPackets.get() == 0, "Paused receiver unexpectedly consumed packets");
            System.out.printf("FLOOD paused variant=%s submittedPackets=%d submittedBytes=%d nettyPendingBytes=%d liveHeapBytes=%d%n",
                    variant, submittedPackets, submittedBytes, paused, liveAtPause);
            receiver.config().setAutoRead(true);
            long drainTimeoutSeconds = 30 + 3 * (submittedBytes / readRate + 1);
            long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(drainTimeoutSeconds);
            while (receivedPackets.get() < submittedPackets && failure.get() == null && System.nanoTime() < deadline) Thread.sleep(100);
            require(failure.get() == null, "Receiver failed: " + failure.get());
            require(receivedPackets.get() == submittedPackets && receivedBytes.get() == submittedBytes, "Missing or extra restored packets");
            require(java.util.Arrays.equals(expected.digest(), actual.digest()), "Ordered packet SHA256 mismatch");
            sending.eventLoop().submit(() -> { }).sync();
            var drained = sending.eventLoop().submit(() -> sending.unsafe().outboundBuffer().totalPendingWriteBytes()).sync().getNow();
            require(drained == 0, "Completed stream still has Netty backlog");

            System.gc();
            System.out.printf("FLOOD PASS variant=%s submittedBytes=%d restoredBytes=%d packets=%d peakPendingBytes=%d peakHeapBytes=%d liveAtPauseBytes=%d liveAfterDrainBytes=%d elapsedMillis=%d%n",
                    variant, submittedBytes, receivedBytes.get(), receivedPackets.get(), peakPending.get(), peakHeap.get(), liveAtPause, heapUsed(),
                    TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - started));
        } finally {
            if (sampler != null) sampler.cancel(false);
            if (receiver != null) receiver.close().syncUninterruptibly();
            if (sender.get() != null) sender.get().close().syncUninterruptibly();
            if (server != null) server.close().syncUninterruptibly();
            group.shutdownGracefully(0, 5, TimeUnit.SECONDS).syncUninterruptibly();
            fragments.clear();
            decodeSession.close();
        }
    }

    private static long heapUsed() { return Runtime.getRuntime().totalMemory() - Runtime.getRuntime().freeMemory(); }
    private static int bounded(String key, int defaultValue, int min, int max) {
        int value = Integer.getInteger("boFlood." + key, defaultValue);
        require(value >= min && value <= max, "Invalid test bound: " + key);
        return value;
    }
    private static void require(boolean condition, String message) { if (!condition) throw new AssertionError(message); }
}
