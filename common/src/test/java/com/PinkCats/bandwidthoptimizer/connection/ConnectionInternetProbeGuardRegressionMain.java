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
        assertProbe(hex("160301002e01"));
        assertProbe(hex("0300002f2ae0"));
        assertProbe(hex("1201000800000100"));
        assertNotProbe("GE");
        assertNotProbe("\u0005\u0000test");
        assertNotProbe(hex("160301002e02"));
        assertNotProbe(hex("0300002f2a00"));
        assertNotProbe(hex("1201000700000100"));
        assertNotProbe(hex("1200f805096c6f63616c686f737463dd02"));
        assertRejectedBeforeMinecraftDecode();
        assertIgnoredAfterMinecraftDecode();
        System.out.println("Connection internet probe guard regression passed");
    }

    private static void assertProbe(String value) {
        assertProbe(value.getBytes(StandardCharsets.ISO_8859_1));
    }

    private static void assertNotProbe(String value) {
        assertNotProbe(value.getBytes(StandardCharsets.ISO_8859_1));
    }

    private static void assertProbe(byte[] bytes) {
        if (!ConnectionInternetProbeGuard.isKnownProbe(bytes)) {
            throw new AssertionError("Expected probe prefix");
        }
    }

    private static void assertNotProbe(byte[] bytes) {
        if (ConnectionInternetProbeGuard.isKnownProbe(bytes)) {
            throw new AssertionError("Unexpected probe prefix");
        }
    }

    private static byte[] hex(String value) {
        byte[] bytes = new byte[value.length() / 2];
        for (int index = 0; index < bytes.length; index++) {
            bytes[index] = (byte) Integer.parseInt(value.substring(index * 2, index * 2 + 2), 16);
        }
        return bytes;
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
