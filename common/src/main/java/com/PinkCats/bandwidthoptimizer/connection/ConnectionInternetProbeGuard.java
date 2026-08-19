package com.PinkCats.bandwidthoptimizer.connection;

import io.netty.buffer.ByteBuf;
import io.netty.channel.Channel;
import io.netty.channel.ChannelHandlerContext;
import io.netty.util.AttributeKey;

import java.nio.charset.StandardCharsets;

public final class ConnectionInternetProbeGuard {

    private static final AttributeKey<Boolean> MINECRAFT_PACKET_DECODED_KEY =
            AttributeKey.valueOf("bandwidthoptimizer:minecraft_packet_decoded");

    private static final byte[][] PROBE_PREFIXES = {
            ascii("GET "),
            ascii("POST "),
            ascii("HEAD "),
            ascii("PUT "),
            ascii("DELETE "),
            ascii("OPTIONS "),
            ascii("CONNECT "),
            ascii("TRACE "),
            ascii("PATCH "),
            ascii("PRI * HTTP/2.0"),
            ascii("SIP/2.0"),
            ascii("REGISTER sip:"),
            ascii("INVITE sip:"),
            ascii("OPTIONS sip:"),
            ascii("SSH-")
    };

    private ConnectionInternetProbeGuard() {}

    public static boolean tryReject(ChannelHandlerContext context, ByteBuf input) {
        if (context == null || input == null || !input.isReadable()) {
            return false;
        }
        Channel channel = context.channel();
        if (channel == null
                || Boolean.TRUE.equals(channel.attr(MINECRAFT_PACKET_DECODED_KEY).get())
                || !isKnownProbe(input)) {
            return false;
        }
        input.skipBytes(input.readableBytes());
        context.close();
        return true;
    }

    public static void markMinecraftPacketDecoded(ChannelHandlerContext context) {
        if (context != null) {
            context.channel().attr(MINECRAFT_PACKET_DECODED_KEY).set(Boolean.TRUE);
        }
    }

    public static boolean isKnownProbe(byte[] payload) {
        if (payload == null) {
            return false;
        }
        for (byte[] prefix : PROBE_PREFIXES) {
            if (startsWith(payload, 0, payload.length, prefix)) {
                return true;
            }
        }
        return false;
    }

    static boolean isKnownProbe(ByteBuf input) {
        int readerIndex = input.readerIndex();
        int readableBytes = input.readableBytes();
        for (byte[] prefix : PROBE_PREFIXES) {
            if (startsWith(input, readerIndex, readableBytes, prefix)) {
                return true;
            }
        }
        return false;
    }

    private static boolean startsWith(ByteBuf input, int readerIndex, int readableBytes, byte[] prefix) {
        if (readableBytes < prefix.length) {
            return false;
        }
        for (int index = 0; index < prefix.length; index++) {
            if (input.getByte(readerIndex + index) != prefix[index]) {
                return false;
            }
        }
        return true;
    }

    private static boolean startsWith(byte[] input, int offset, int length, byte[] prefix) {
        if (length < prefix.length) {
            return false;
        }
        for (int index = 0; index < prefix.length; index++) {
            if (input[offset + index] != prefix[index]) {
                return false;
            }
        }
        return true;
    }

    private static byte[] ascii(String value) {
        return value.getBytes(StandardCharsets.US_ASCII);
    }
}
