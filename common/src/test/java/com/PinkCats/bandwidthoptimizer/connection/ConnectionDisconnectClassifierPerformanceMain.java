package com.PinkCats.bandwidthoptimizer.connection;

import io.netty.channel.embedded.EmbeddedChannel;
import io.netty.channel.ChannelInboundHandlerAdapter;

import java.net.SocketException;
import java.util.List;

public final class ConnectionDisconnectClassifierPerformanceMain {

    private static final int WARMUP_ITERATIONS = 200_000;
    private static final int HOT_PATH_ITERATIONS = 2_000_000;
    private static final int EXCEPTION_ITERATIONS = 500_000;

    private ConnectionDisconnectClassifierPerformanceMain() {}

    public static void main(String[] args) {
        EmbeddedChannel channel = new EmbeddedChannel(new ChannelInboundHandlerAdapter());
        Object packet = new OrdinaryPacket();
        List<Object> packets = List.of(packet);
        Throwable wrappedReset = new IllegalStateException(
                "wrapped",
                new SocketException("Connection reset")
        );

        for (int index = 0; index < WARMUP_ITERATIONS; index++) {
            ConnectionDisconnectClassifier.observeOutboundPacket(channel, packet);
            ConnectionDisconnectClassifier.observeInboundDecodedPackets(channel.pipeline().firstContext(), packets, 0);
            ConnectionDisconnectClassifier.classifyThrowable(wrappedReset);
        }

        long outboundNanos = measure(HOT_PATH_ITERATIONS, () ->
                ConnectionDisconnectClassifier.observeOutboundPacket(channel, packet));
        long inboundNanos = measure(HOT_PATH_ITERATIONS, () ->
                ConnectionDisconnectClassifier.observeInboundDecodedPackets(channel.pipeline().firstContext(), packets, 0));
        long exceptionNanos = measure(EXCEPTION_ITERATIONS, () ->
                ConnectionDisconnectClassifier.classifyThrowable(wrappedReset));

        double outboundPerOperation = nanosPerOperation(outboundNanos, HOT_PATH_ITERATIONS);
        double inboundPerOperation = nanosPerOperation(inboundNanos, HOT_PATH_ITERATIONS);
        double exceptionPerOperation = nanosPerOperation(exceptionNanos, EXCEPTION_ITERATIONS);
        System.out.printf(
                "Connection classifier performance: outbound=%.1f ns/op inbound=%.1f ns/op exception=%.1f ns/op%n",
                outboundPerOperation,
                inboundPerOperation,
                exceptionPerOperation
        );

        assertBelow("outbound", outboundPerOperation, 2_000.0D);
        assertBelow("inbound", inboundPerOperation, 3_000.0D);
        assertBelow("exception", exceptionPerOperation, 10_000.0D);
        channel.finishAndReleaseAll();
    }

    private static long measure(int iterations, Runnable operation) {
        long started = System.nanoTime();
        for (int index = 0; index < iterations; index++) {
            operation.run();
        }
        return System.nanoTime() - started;
    }

    private static double nanosPerOperation(long nanos, int operations) {
        return (double) nanos / operations;
    }

    private static void assertBelow(String path, double actual, double limit) {
        if (actual > limit) {
            throw new AssertionError(path + " classifier cost " + actual + " ns/op exceeded " + limit + " ns/op");
        }
    }

    private static final class OrdinaryPacket { }
}
