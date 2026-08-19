package com.PinkCats.bandwidthoptimizer.connection;

import io.netty.buffer.ByteBuf;
import io.netty.buffer.Unpooled;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.ChannelInboundHandlerAdapter;
import io.netty.channel.embedded.EmbeddedChannel;

import java.nio.charset.StandardCharsets;

public final class ConnectionInternetProbeGuardRegressionMain {

    private ConnectionInternetProbeGuardRegressionMain() {}

    public static void main(String[] arguments) {
        assertProbe("GET / HTTP/1.1\r\nHost: localhost\r\n\r\n");
        assertProbe("POST /status HTTP/1.1\r\n\r\n");
        assertProbe("PRI * HTTP/2.0\r\n\r\nSM\r\n\r\n");
        assertProbe("SSH-2.0-test\r\n");
        assertNotProbe("GE");
        assertNotProbe("\u0005\u0000test");
        assertRejectedBeforeMinecraftDecode();
        assertIgnoredAfterMinecraftDecode();
        System.out.println("Connection internet probe guard regression passed");
    }

    private static void assertProbe(String value) {
        byte[] bytes = value.getBytes(StandardCharsets.ISO_8859_1);
        if (!ConnectionInternetProbeGuard.isKnownProbe(bytes)) {
            throw new AssertionError("Expected probe prefix: " + value);
        }
    }

    private static void assertNotProbe(String value) {
        byte[] bytes = value.getBytes(StandardCharsets.ISO_8859_1);
        if (ConnectionInternetProbeGuard.isKnownProbe(bytes)) {
            throw new AssertionError("Unexpected probe prefix: " + value);
        }
    }

    private static void assertRejectedBeforeMinecraftDecode() {
        EmbeddedChannel channel = channel();
        ChannelHandlerContext context = channel.pipeline().firstContext();
        ByteBuf input = Unpooled.copiedBuffer("GET / HTTP/1.1\r\n\r\n", StandardCharsets.US_ASCII);
        boolean rejected = ConnectionInternetProbeGuard.tryReject(context, input);
        if (!rejected || input.isReadable() || channel.isOpen()) {
            throw new AssertionError("Expected the initial HTTP probe to close the channel");
        }
        input.release();
    }

    private static void assertIgnoredAfterMinecraftDecode() {
        EmbeddedChannel channel = channel();
        ChannelHandlerContext context = channel.pipeline().firstContext();
        ConnectionInternetProbeGuard.markMinecraftPacketDecoded(context);
        ByteBuf input = Unpooled.copiedBuffer("GET / HTTP/1.1\r\n\r\n", StandardCharsets.US_ASCII);
        boolean rejected = ConnectionInternetProbeGuard.tryReject(context, input);
        if (rejected || !channel.isOpen() || input.readerIndex() != 0) {
            throw new AssertionError("Probe guard must be disabled after Minecraft decoding starts");
        }
        input.release();
        channel.close();
    }

    private static EmbeddedChannel channel() {
        return new EmbeddedChannel(new ChannelInboundHandlerAdapter());
    }
}
