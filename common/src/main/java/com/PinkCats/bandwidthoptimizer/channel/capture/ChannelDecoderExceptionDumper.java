package com.PinkCats.bandwidthoptimizer.channel.capture;

import com.PinkCats.bandwidthoptimizer.Bandwidthoptimizer;
import com.PinkCats.bandwidthoptimizer.connection.ConnectionInternetProbeGuard;
import com.PinkCats.bandwidthoptimizer.util.BandwidthOptimizerOutputPaths;
import io.netty.channel.Channel;
import io.netty.channel.ChannelHandlerContext;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Locale;

public final class ChannelDecoderExceptionDumper {

    private static final Object LOCK = new Object();
    private static final String REPORT_DIRECTORY = "decoder-exception-dump";
    private static final String LATEST_JSON_FILE_NAME = "latest-decoder-exception.json";
    private static final String HISTORY_JSONL_FILE_NAME = "decoder-exception-history.jsonl";
    private static final String LATEST_PAYLOAD_FILE_NAME = "latest-decoder-exception-payload.bin";

    private ChannelDecoderExceptionDumper() {}

    public static void dumpIfDecoderException(ChannelHandlerContext context, Channel fallbackChannel, Throwable throwable) {
        if (!ChannelDecoderExceptionDumpConfig.isEnabled() || !isDecoderBoundaryException(throwable)) {
            return;
        }
        Channel channel = context != null ? context.channel() : fallbackChannel;
        ChannelCapturedFrame frame = ChannelCaptureHooks.lastInboundDecodeCandidate(channel);
        if (frame == null) {
            Bandwidthoptimizer.LOGGER.warn(
                    "[DecoderExceptionDump] Decoder exception detected but no inbound candidate frame was captured. channel={}, exception={}: {}",
                    channelId(channel),
                    throwable == null ? "null" : throwable.getClass().getName(),
                    throwable == null ? "" : throwable.getMessage()
            );
            return;
        }

        try {
            DumpPaths paths = writeDump(frame, throwable);
            ChannelCaptureHooks.clearInboundDecodeCandidate(channel);
            if (!ConnectionInternetProbeGuard.isKnownProbe(frame.encodedBytes())) {
                Bandwidthoptimizer.LOGGER.warn(
                        "[DecoderExceptionDump] Dumped decoder failure packet. channel={}, protocol={}, packetId={}, bytes={}, capturedPayloadBytes={}, truncated={}, fingerprint={}, report={}, payload={}",
                        frame.channelId(),
                        frame.protocolName(),
                        frame.packetId(),
                        frame.byteLength(),
                        frame.encodedBytes().length,
                        frame.encodedBytes().length < frame.byteLength(),
                        sha256Hex(frame.encodedBytes()),
                        paths.latestJson(),
                        paths.latestPayload()
                );
            }
        } catch (RuntimeException dumpFailure) {
            Bandwidthoptimizer.LOGGER.warn(
                    "[DecoderExceptionDump] Failed to dump decoder failure packet. channel={}, protocol={}, packetId={}, bytes={}",
                    frame.channelId(),
                    frame.protocolName(),
                    frame.packetId(),
                    frame.byteLength(),
                    dumpFailure
            );
        }
    }

    private static boolean isDecoderBoundaryException(Throwable throwable) {
        Throwable current = throwable;
        while (current != null) {
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

    private static DumpPaths writeDump(ChannelCapturedFrame frame, Throwable throwable) {
        Path directory = BandwidthOptimizerOutputPaths.resolve(REPORT_DIRECTORY);
        Path latestJson = directory.resolve(LATEST_JSON_FILE_NAME);
        Path historyJsonl = directory.resolve(HISTORY_JSONL_FILE_NAME);
        Path latestPayload = directory.resolve(LATEST_PAYLOAD_FILE_NAME);
        String json = toJson(frame, throwable);
        try {
            Files.createDirectories(directory);
            Files.writeString(
                    latestJson,
                    json + System.lineSeparator(),
                    StandardCharsets.UTF_8,
                    StandardOpenOption.CREATE,
                    StandardOpenOption.TRUNCATE_EXISTING,
                    StandardOpenOption.WRITE
            );
            synchronized (LOCK) {
                Files.writeString(
                        historyJsonl,
                        json + System.lineSeparator(),
                        StandardCharsets.UTF_8,
                        StandardOpenOption.CREATE,
                        StandardOpenOption.APPEND,
                        StandardOpenOption.WRITE
                );
            }
            Files.write(
                    latestPayload,
                    frame.encodedBytes(),
                    StandardOpenOption.CREATE,
                    StandardOpenOption.TRUNCATE_EXISTING,
                    StandardOpenOption.WRITE
            );
            return new DumpPaths(latestJson.toAbsolutePath().normalize(), latestPayload.toAbsolutePath().normalize());
        } catch (IOException exception) {
            throw new IllegalStateException("Failed to write decoder exception dump", exception);
        }
    }

    private static String toJson(ChannelCapturedFrame frame, Throwable throwable) {
        byte[] payload = frame.encodedBytes();
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
        while (current != null && current != previous) {
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

    private record DumpPaths(Path latestJson, Path latestPayload) {
    }
}
