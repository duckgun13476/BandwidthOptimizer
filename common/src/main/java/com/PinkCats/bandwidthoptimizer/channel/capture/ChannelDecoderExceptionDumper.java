package com.PinkCats.bandwidthoptimizer.channel.capture;

import com.PinkCats.bandwidthoptimizer.connection.ConnectionInternetProbeGuard;
import io.netty.channel.Channel;
import io.netty.channel.ChannelHandlerContext;
import io.netty.util.AttributeKey;

import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Locale;
import java.util.concurrent.atomic.AtomicInteger;

public final class ChannelDecoderExceptionDumper {

    static final int MAX_DUMPS_PER_CONNECTION = 50;
    private static final AttributeKey<AtomicInteger> DUMP_COUNT_KEY =
            AttributeKey.valueOf("bandwidthoptimizer:decoder_exception_dump_count");

    private ChannelDecoderExceptionDumper() {}

    public static void dumpIfDecoderException(ChannelHandlerContext context, Channel fallbackChannel, Throwable throwable) {
        if (!ChannelDecoderExceptionDumpConfig.isEnabled() || !isDecoderBoundaryException(throwable)) {
            return;
        }
        Channel channel = context != null ? context.channel() : fallbackChannel;
        ChannelCapturedFrame frame = ChannelCaptureHooks.lastInboundDecodeCandidate(channel);
        if (isInitialHandshakeNoise(channel, frame)) {
            ChannelCaptureHooks.clearInboundDecodeCandidate(channel);
            return;
        }
        if (!claimDumpSlot(channel)) {
            ChannelCaptureHooks.clearInboundDecodeCandidate(channel);
            return;
        }
        try {
            com.google.gson.JsonObject detail;
            if (frame == null) {
                detail = new com.google.gson.JsonObject();
                detail.addProperty("channel_id", channelId(channel));
            } else {
                byte[] bytes = frame.encodedBytes();
                ChannelCapturedFrame bounded = new ChannelCapturedFrame(frame.channelId(), frame.direction(),
                        frame.protocolName(), frame.packetClassName(), frame.packetId(), frame.byteLength(),
                        java.util.Arrays.copyOf(bytes, Math.min(bytes.length, 2048)), frame.capturedAtMillis());
                detail = com.google.gson.JsonParser.parseString(toJson(bounded, throwable)).getAsJsonObject();
            }
            detail.addProperty("candidate_available", frame != null);
            detail.addProperty("capture_boundary", "minecraft_packet_decoder_input; not pre-inflate wire bytes");
            detail.addProperty("hash_scope", "captured_prefix");
            detail.add("exception_chain", com.PinkCats.bandwidthoptimizer.debug.ConnectionFailureReport.causes(throwable));
            com.PinkCats.bandwidthoptimizer.debug.ConnectionFailureReport.decoderDetail(detail);
            ChannelCaptureHooks.clearInboundDecodeCandidate(channel);
        } catch (RuntimeException | LinkageError dumpFailure) {
            // Diagnostic failures must never replace the original decoder exception.
        } finally {
            ChannelCaptureHooks.clearInboundDecodeCandidate(channel);
        }
    }

    static boolean shouldCaptureFailure(Channel channel, String protocolName) {
        return protocolName == null
                || !"HANDSHAKING".equalsIgnoreCase(protocolName)
                || ConnectionInternetProbeGuard.hasMinecraftPacketDecoded(channel);
    }

    static boolean claimDumpSlot(Channel channel) {
        if (channel == null) {
            return true;
        }
        AtomicInteger newCounter = new AtomicInteger();
        AtomicInteger counter = channel.attr(DUMP_COUNT_KEY).setIfAbsent(newCounter);
        if (counter == null) {
            counter = newCounter;
        }
        while (true) {
            int current = counter.get();
            if (current >= MAX_DUMPS_PER_CONNECTION) {
                return false;
            }
            if (counter.compareAndSet(current, current + 1)) {
                return true;
            }
        }
    }

    private static boolean isInitialHandshakeNoise(Channel channel, ChannelCapturedFrame frame) {
        if (ConnectionInternetProbeGuard.hasMinecraftPacketDecoded(channel)) {
            return false;
        }
        return frame == null || !shouldCaptureFailure(channel, frame.protocolName());
    }

    private static boolean isDecoderBoundaryException(Throwable throwable) {
        Throwable current = throwable;
        for (int depth = 0; current != null && depth < 12; depth++) {
            String className = current.getClass().getName();
            String message = current.getMessage();
            if (className.contains("DecoderException")) {
                return true;
            }
            if (message != null) {
                String lowerMessage = message.toLowerCase(Locale.ROOT);
                if (lowerMessage.contains("larger than i expected")
                        || lowerMessage.contains("extra whilst reading packet")
                        || lowerMessage.contains("packet")
                        && lowerMessage.contains("was larger than")) {
                    return true;
                }
            }
            current = current.getCause();
        }
        return false;
    }

    private static String toJson(ChannelCapturedFrame frame, Throwable throwable) {
        byte[] payload = frame.encodedBytes();
        LevelChunkNbtDepthFailureDiagnostic.Result chunkDepthDiagnostic =
                LevelChunkNbtDepthFailureDiagnostic.inspect(frame, throwable);
        return "{"
                + "\"captured_at_ms\":" + frame.capturedAtMillis() + ","
                + "\"dumped_at_ms\":" + System.currentTimeMillis() + ","
                + "\"channel_id\":\"" + escapeJson(frame.channelId()) + "\","
                + "\"direction\":\"" + escapeJson(frame.direction()) + "\","
                + "\"protocol\":\"" + escapeJson(frame.protocolName()) + "\","
                + "\"packet_class\":\"" + escapeJson(frame.packetClassName()) + "\","
                + "\"packet_id\":" + frame.packetId() + ","
                + "\"byte_length\":" + frame.byteLength() + ","
                + "\"captured_payload_bytes\":" + payload.length + ","
                + "\"payload_truncated\":" + (payload.length < frame.byteLength()) + ","
                + "\"thread\":\"" + escapeJson(Thread.currentThread().getName()) + "\","
                + "\"exception_class\":\"" + escapeJson(throwable == null ? "" : throwable.getClass().getName()) + "\","
                + "\"exception_message\":\"" + escapeJson(throwable == null ? "" : throwable.getMessage()) + "\","
                + "\"root_cause_class\":\"" + escapeJson(rootCauseClassName(throwable)) + "\","
                + "\"root_cause_message\":\"" + escapeJson(rootCauseMessage(throwable)) + "\","
                + "\"payload_sha256\":\"" + sha256Hex(payload) + "\","
                + "\"payload_hex\":\"" + hex(payload) + "\""
                + chunkDepthDiagnosticJson(chunkDepthDiagnostic)
                + "}";
    }

    private static String chunkDepthDiagnosticJson(LevelChunkNbtDepthFailureDiagnostic.Result result) {
        if (result == null || !result.matched()) {
            return "";
        }
        LevelChunkNbtDepthFailureDiagnostic.Coordinates coordinates = result.coordinates();
        if (coordinates == null || !coordinates.available()) {
            return ",\"chunk_nbt_depth_diagnostic\":{\"packet_type\":\"clientbound/minecraft:level_chunk_with_light\",\"chunk_coordinate\":\"unavailable\"}";
        }
        return ",\"chunk_nbt_depth_diagnostic\":{"
                + "\"packet_type\":\"clientbound/minecraft:level_chunk_with_light\","
                + "\"chunk_x\":" + coordinates.chunkX() + ","
                + "\"chunk_z\":" + coordinates.chunkZ() + ","
                + "\"block_x\":" + coordinates.blockX() + ","
                + "\"block_z\":" + coordinates.blockZ()
                + "}";
    }

    private static String rootCauseClassName(Throwable throwable) {
        Throwable rootCause = rootCause(throwable);
        return rootCause == null ? "" : rootCause.getClass().getName();
    }

    private static String rootCauseMessage(Throwable throwable) {
        Throwable rootCause = rootCause(throwable);
        return rootCause == null ? "" : rootCause.getMessage();
    }

    private static Throwable rootCause(Throwable throwable) {
        Throwable current = throwable;
        Throwable previous = null;
        for (int depth = 0; current != null && current != previous && depth < 12; depth++) {
            previous = current;
            current = current.getCause();
        }
        return previous;
    }

    private static String channelId(Channel channel) {
        return channel == null ? "<null-channel>" : com.PinkCats.bandwidthoptimizer.channel.ChannelIdentity.longText(channel);
    }

    private static String sha256Hex(byte[] bytes) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            return hex(digest.digest(bytes == null ? new byte[0] : bytes));
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("Missing SHA-256 support", exception);
        }
    }

    private static String hex(byte[] bytes) {
        byte[] safeBytes = bytes == null ? new byte[0] : bytes;
        StringBuilder builder = new StringBuilder(safeBytes.length * 2);
        for (byte value : safeBytes) {
            builder.append(String.format("%02x", value & 0xFF));
        }
        return builder.toString();
    }

    private static String escapeJson(String value) {
        if (value == null) {
            return "";
        }
        value = value.substring(0, Math.min(value.length(), 512));
        StringBuilder builder = new StringBuilder(value.length() + 16);
        for (int index = 0; index < value.length(); index++) {
            char current = value.charAt(index);
            switch (current) {
                case '\\' -> builder.append("\\\\");
                case '"' -> builder.append("\\\"");
                case '\r' -> builder.append("\\r");
                case '\n' -> builder.append("\\n");
                case '\t' -> builder.append("\\t");
                default -> {
                    if (current < 32) {
                        builder.append(String.format("\\u%04x", (int) current));
                    } else {
                        builder.append(current);
                    }
                }
            }
        }
        return builder.toString();
    }

}
