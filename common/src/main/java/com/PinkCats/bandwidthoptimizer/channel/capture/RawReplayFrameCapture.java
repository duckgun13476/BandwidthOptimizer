package com.PinkCats.bandwidthoptimizer.channel.capture;

import com.PinkCats.bandwidthoptimizer.channel.ChannelIdentity;
import com.PinkCats.bandwidthoptimizer.integration.minecraft.ConnectionProtocolNameCompat;
import com.PinkCats.bandwidthoptimizer.util.BandwidthOptimizerOutputPaths;
import io.netty.buffer.ByteBuf;
import io.netty.channel.ChannelHandlerContext;
import net.minecraft.network.protocol.Packet;

import java.io.BufferedWriter;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;

/** Test-only raw frame export for deterministic transport replay. */
public final class RawReplayFrameCapture {

    private static final String ENABLED_PROPERTY = "bandwidthoptimizer.replayRawCaptureEnabled";
    private static final Object LOCK = new Object();
    private static final char[] HEX = "0123456789abcdef".toCharArray();
    private static BufferedWriter writer;

    private RawReplayFrameCapture() {
    }

    public static void captureOutbound(
            ChannelHandlerContext context,
            Packet<?> packet,
            ByteBuf encodedBuffer,
            int startIndexInclusive
    ) {
        if (!Boolean.getBoolean(ENABLED_PROPERTY) || context == null || packet == null || encodedBuffer == null) {
            return;
        }
        int endIndexExclusive = encodedBuffer.writerIndex();
        if (endIndexExclusive <= startIndexInclusive) {
            return;
        }

        byte[] bytes = new byte[endIndexExclusive - startIndexInclusive];
        encodedBuffer.getBytes(startIndexInclusive, bytes);
        String line = "{"
                + "\"captured_at_ms\":" + System.currentTimeMillis() + ","
                + "\"channel_id\":\"" + escape(ChannelIdentity.longText(context.channel())) + "\","
                + "\"direction\":\"OUTBOUND\","
                + "\"protocol\":\"" + escape(ConnectionProtocolNameCompat.readProtocolName(context.channel())) + "\","
                + "\"packet_class\":\"" + escape(packet.getClass().getName()) + "\","
                + "\"packet_id\":" + readLeadingVarInt(bytes) + ","
                + "\"byte_length\":" + bytes.length + ","
                + "\"payload_hex\":\"" + hex(bytes) + "\""
                + "}";
        synchronized (LOCK) {
            try {
                if (writer == null) {
                    Path output = BandwidthOptimizerOutputPaths.resolve("replay-raw-outbound.jsonl");
                    Files.createDirectories(output.getParent());
                    writer = Files.newBufferedWriter(
                            output,
                            StandardCharsets.UTF_8,
                            StandardOpenOption.CREATE,
                            StandardOpenOption.TRUNCATE_EXISTING,
                            StandardOpenOption.WRITE
                    );
                }
                writer.write(line);
                writer.newLine();
                writer.flush();
            } catch (IOException exception) {
                throw new IllegalStateException("Failed to write raw replay frame", exception);
            }
        }
    }

    private static int readLeadingVarInt(byte[] bytes) {
        int value = 0;
        int shift = 0;
        for (int index = 0; index < bytes.length && index < 5; index++) {
            int current = bytes[index] & 0xFF;
            value |= (current & 0x7F) << shift;
            if ((current & 0x80) == 0) {
                return value;
            }
            shift += 7;
        }
        return -1;
    }

    private static String hex(byte[] bytes) {
        char[] encoded = new char[bytes.length * 2];
        for (int index = 0; index < bytes.length; index++) {
            int value = bytes[index] & 0xFF;
            encoded[index * 2] = HEX[value >>> 4];
            encoded[index * 2 + 1] = HEX[value & 0x0F];
        }
        return new String(encoded);
    }

    private static String escape(String value) {
        return value == null ? "" : value.replace("\\", "\\\\").replace("\"", "\\\"");
    }
}
