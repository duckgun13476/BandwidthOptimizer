package com.PinkCats.bandwidthoptimizer.channel.mes;

import com.PinkCats.bandwidthoptimizer.Old.network.payload.PayloadInspectionSupport;
import com.PinkCats.bandwidthoptimizer.channel.capture.ChannelCapturedFrame;
import net.minecraftforge.fml.loading.FMLEnvironment;

import java.io.BufferedWriter;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;


public final class ChannelFrameJsonlLogger {

    private static final Object LOCK = new Object();
    private static final Path SEND_OUTPUT_PATH = Path.of("send.jsonl");
    private static final Path RECEIVE_OUTPUT_PATH = Path.of("receive.jsonl");

    private static final Path LEGACY_SEND_COMPARE_OUTPUT_PATH = Path.of("send.compare.jsonl");
    private static final Path LEGACY_RECEIVE_COMPARE_OUTPUT_PATH = Path.of("receive.compare.jsonl");

    private static BufferedWriter sendWriter;
    private static BufferedWriter receiveWriter;
    private static boolean initialized;
    private static boolean shutdownHookInstalled;


    private ChannelFrameJsonlLogger() {}

    public static void initializeOutputFiles() {
        synchronized (LOCK) {
            closeWritersUnsafe();
            deleteLegacyCompareOutputs();
            sendWriter = openFreshWriter(SEND_OUTPUT_PATH);
            receiveWriter = openFreshWriter(RECEIVE_OUTPUT_PATH);
            initialized = true;
            installShutdownHookIfNeeded();
        }
    }


    public static void appendOutboundFrame(ChannelCapturedFrame frame) {
        appendFrame(frame, true);
    }

    public static void appendInboundFrame(ChannelCapturedFrame frame) {
        appendFrame(frame, false);
    }

    private static void appendFrame(ChannelCapturedFrame frame, boolean outbound) {
        if (frame == null) {
            return;
        }

        synchronized (LOCK) {
            ensureInitialized();

            BufferedWriter writer = outbound ? sendWriter : receiveWriter;
            if (writer == null) {
                return;
            }

            FrameSerializedFields serializedFields = serializeFields(frame);
            try {
                writeLine(writer, toJsonLine(frame, serializedFields));
            } catch (IOException exception) {
                throw new IllegalStateException("Failed to write channel frame jsonl", exception);
            }
        }
    }

    private static void ensureInitialized() {
        if (initialized) {
            return;
        }
        initializeOutputFiles();
    }

    private static BufferedWriter openFreshWriter(Path outputPath) {
        try {
            Path parent = outputPath.getParent();
            if (parent != null) {
                Files.createDirectories(parent);
            }
            return Files.newBufferedWriter(
                    outputPath,
                    StandardCharsets.UTF_8,
                    StandardOpenOption.CREATE,
                    StandardOpenOption.TRUNCATE_EXISTING,
                    StandardOpenOption.WRITE
            );
        } catch (IOException exception) {
            throw new IllegalStateException("Failed to open channel jsonl output: " + outputPath, exception);
        }
    }

    private static void deleteLegacyCompareOutputs() {
        deleteIfExists(LEGACY_SEND_COMPARE_OUTPUT_PATH);
        deleteIfExists(LEGACY_RECEIVE_COMPARE_OUTPUT_PATH);
    }

    private static void deleteIfExists(Path outputPath) {
        try {
            Files.deleteIfExists(outputPath);
        } catch (IOException ignored) {
        }
    }

    private static void writeLine(BufferedWriter writer, String line) throws IOException {
        writer.write(line);
        writer.newLine();
        writer.flush();
    }

    private static String toJsonLine(ChannelCapturedFrame frame, FrameSerializedFields fields) {
        return "{"
                + "\"captured_at_ms\":" + frame.capturedAtMillis() + ","
                + "\"physical_side\":\"" + escapeJson(FMLEnvironment.dist.name()) + "\","
                + "\"channel_id\":\"" + escapeJson(frame.channelId()) + "\","
                + "\"direction\":\"" + escapeJson(frame.direction()) + "\","
                + "\"protocol\":\"" + escapeJson(frame.protocolName()) + "\","
                + "\"packet_class\":\"" + escapeJson(frame.packetClassName()) + "\","
                + "\"packet_id\":" + frame.packetId() + ","
                + "\"byte_length\":" + frame.byteLength() + ","
                + "\"thread\":\"" + escapeJson(Thread.currentThread().getName()) + "\","
                + "\"payload_hex\":\"" + fields.payloadHex() + "\","
                + "\"wire_fingerprint\":\"" + fields.wireFingerprint() + "\","
                + "\"semantic_fingerprint\":\"" + fields.semanticFingerprint() + "\""
                + "}";
    }

    private static FrameSerializedFields serializeFields(ChannelCapturedFrame frame) {
        String payloadHex = hex(frame.encodedBytes());
        String wireFingerprint = sha256(
                frame.protocolName() + "|" + frame.packetId() + "|" + frame.byteLength() + "|" + payloadHex
        );
        String semanticFingerprint = sha256(
                frame.protocolName() + "|" + frame.packetClassName() + "|" + frame.packetId() + "|" + frame.byteLength() + "|" + payloadHex
        );
        return new FrameSerializedFields(payloadHex, wireFingerprint, semanticFingerprint);
    }

    private static String hex(byte[] bytes) {return PayloadInspectionSupport.hex(bytes);}

    private static String sha256(String value) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] hashedBytes = digest.digest(value.getBytes(StandardCharsets.UTF_8));
            return hex(hashedBytes);
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("Missing SHA-256 support", exception);
        }
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

    private static void installShutdownHookIfNeeded() {
        if (shutdownHookInstalled) {
            return;
        }
        Runtime.getRuntime().addShutdownHook(new Thread(ChannelFrameJsonlLogger::closeWritersSafely, "bo-channel-jsonl-close"));
        shutdownHookInstalled = true;
    }

    private static void closeWritersSafely() {
        synchronized (LOCK) {
            closeWritersUnsafe();
        }
    }
    private static void closeWritersUnsafe() {
        closeWriter(sendWriter);
        closeWriter(receiveWriter);
        sendWriter = null;
        receiveWriter = null;
        initialized = false;
    }

    private static void closeWriter(BufferedWriter writer) {
        if (writer == null) {
            return;
        }
        try {
            writer.close();
        } catch (IOException ignored) {
        }
    }





    private record FrameSerializedFields(
            String payloadHex,
            String wireFingerprint,
            String semanticFingerprint
    ) {
    }
}
