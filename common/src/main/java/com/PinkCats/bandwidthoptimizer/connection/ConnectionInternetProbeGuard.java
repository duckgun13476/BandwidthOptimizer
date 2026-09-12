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

    public static boolean hasMinecraftPacketDecoded(Channel channel) {
        return channel != null && Boolean.TRUE.equals(channel.attr(MINECRAFT_PACKET_DECODED_KEY).get());
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
        return isBinaryProbe(payload, 0, payload.length);
    }

    static boolean isKnownProbe(ByteBuf input) {
        int readerIndex = input.readerIndex();
        int readableBytes = input.readableBytes();
        for (byte[] prefix : PROBE_PREFIXES) {
            if (startsWith(input, readerIndex, readableBytes, prefix)) {
                return true;
            }
        }
        return isBinaryProbe(input, readerIndex, readableBytes);
    }

    private static boolean isBinaryProbe(ByteBuf input, int offset, int length) {
        return isTlsClientHello(length, index -> input.getUnsignedByte(offset + index))
                || isRdpConnectionRequest(length, index -> input.getUnsignedByte(offset + index))
                || isTdsPreLogin(length, index -> input.getUnsignedByte(offset + index));
    }

    private static boolean isBinaryProbe(byte[] input, int offset, int length) {
        return isTlsClientHello(length, index -> input[offset + index] & 0xFF)
                || isRdpConnectionRequest(length, index -> input[offset + index] & 0xFF)
                || isTdsPreLogin(length, index -> input[offset + index] & 0xFF);
    }

    private static boolean isTlsClientHello(int length, ByteReader input) {
        return length >= 6
                && input.get(0) == 0x16
                && input.get(1) == 0x03
                && input.get(2) <= 0x04
                && input.get(5) == 0x01;
    }

    private static boolean isRdpConnectionRequest(int length, ByteReader input) {
        if (length < 6 || input.get(0) != 0x03 || input.get(1) != 0x00) {
            return false;
        }
        int declaredLength = input.get(2) << 8 | input.get(3);
        int x224Code = input.get(5);
        return declaredLength >= 7 && (x224Code == 0xE0 || x224Code == 0xD0);
    }

    private static boolean isTdsPreLogin(int length, ByteReader input) {
        if (length < 8
                || input.get(0) != 0x12
                || (input.get(1) & 0x01) == 0
                || input.get(4) != 0
                || input.get(5) != 0
                || input.get(7) != 0) {
            return false;
        }
        int declaredLength = input.get(2) << 8 | input.get(3);
        return declaredLength >= 8 && declaredLength <= length;
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

    @FunctionalInterface
    private interface ByteReader {
        int get(int index);
    }
}
