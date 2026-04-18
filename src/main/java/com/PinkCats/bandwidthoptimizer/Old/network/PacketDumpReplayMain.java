package com.PinkCats.bandwidthoptimizer.Old.network;

import com.PinkCats.bandwidthoptimizer.Old.network.payload.PayloadInspectionSupport;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.PinkCats.bandwidthoptimizer.Old.network.message.ServerToClientAttachmentPacket;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import io.netty.buffer.Unpooled;
import net.minecraft.network.FriendlyByteBuf;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.Base64;

public final class PacketDumpReplayMain {

    private static final Path DEFAULT_INPUT_PATH = Path.of("b.json");

    private PacketDumpReplayMain() {
    }

    public static void main(String[] args) {
        String inputLine = readInput(args);
        if (inputLine == null || inputLine.isBlank()) {
            System.out.println("Usage:");
            System.out.println("  ./gradlew.bat runPacketDumpReplay");
            System.out.println("    Reads the last non-empty JSON line from ./b.json");
            System.out.println("  ./gradlew.bat runPacketDumpReplay --args=\"@C:\\path\\to\\packet.jsonl\"");
            System.out.println("  Get-Content C:\\path\\to\\packet.jsonl | ./gradlew.bat runPacketDumpReplay");
            return;
        }

        DumpInput dumpInput = DumpInput.fromJsonLine(inputLine);
        ReplayResult result = replay(dumpInput);

        System.out.println("=== Packet Dump Replay ===");
        System.out.println("[Packet Simplified]");
        System.out.println("Packet: " + dumpInput.packetName());
        System.out.println("Direction: " + dumpInput.direction());
        System.out.println("Payload base64: " + dumpInput.payloadBase64());
        System.out.println("Payload hex: " + PayloadInspectionSupport.hex(dumpInput.payloadBytes()));
        printPayloadAnalysis("Original", dumpInput.packetClass(), dumpInput.payloadBytes());
        System.out.println();
        System.out.println("[Restored Result]");
        System.out.println("Packet: " + result.restoredPacket().packetName());
        System.out.println("Direction: " + result.restoredPacket().direction());
        System.out.println("Payload base64: " + Base64.getEncoder().encodeToString(result.restoredPacket().payloadBytes()));
        System.out.println("Payload hex: " + PayloadInspectionSupport.hex(result.restoredPacket().payloadBytes()));
        printPayloadAnalysis("Restored", result.restoredPacket().packetClass(), result.restoredPacket().payloadBytes());
        System.out.println();
        System.out.println("[Compare]");
        System.out.println("Payload equal: " + result.payloadMatches());
        System.out.println("Packet name equal: " + dumpInput.packetName().equals(result.restoredPacket().packetName()));
        System.out.println("Direction equal: " + dumpInput.direction().equals(result.restoredPacket().direction()));
        System.out.println("=== Replay Completed ===");
    }

    private static String readInput(String[] args) {
        if (args.length > 0) {
            String joined = String.join(" ", args).trim();
            if (joined.startsWith("@")) {
                return readFile(joined.substring(1).trim());
            }
            return joined;
        }

        String defaultInput = readDefaultFile();
        if (defaultInput != null && !defaultInput.isBlank()) {
            return defaultInput;
        }

        try {
            if (System.in.available() <= 0) {
                return null;
            }
            byte[] stdinBytes = System.in.readAllBytes();
            if (stdinBytes.length == 0) {
                return null;
            }
            return new String(stdinBytes, StandardCharsets.UTF_8).trim();
        } catch (IOException exception) {
            throw new IllegalStateException("Failed to read packet dump input", exception);
        }
    }

    private static String readFile(String filePath) {
        try {
            return extractReplayInput(Files.readString(Path.of(filePath), StandardCharsets.UTF_8));
        } catch (IOException exception) {
            throw new IllegalArgumentException("Failed to read input file: " + filePath, exception);
        }
    }

    private static String readDefaultFile() {
        if (!Files.exists(DEFAULT_INPUT_PATH)) {
            return null;
        }

        try {
            return extractReplayInput(Files.readString(DEFAULT_INPUT_PATH, StandardCharsets.UTF_8));
        } catch (IOException exception) {
            throw new IllegalArgumentException("Failed to read default input file: " + DEFAULT_INPUT_PATH, exception);
        }
    }

    private static String extractReplayInput(String content) {
        String trimmed = content.trim();
        if (trimmed.isEmpty()) {
            return null;
        }

        try {
            JsonElement root = JsonParser.parseString(trimmed);
            if (root.isJsonObject()) {
                return root.getAsJsonObject().toString();
            }
            if (root.isJsonArray()) {
                return extractFromJsonArray(root.getAsJsonArray());
            }
        } catch (Exception ignored) {
            // Fallback to JSONL-style line scanning.
        }

        String[] lines = content.split("\\R");
        for (int i = lines.length - 1; i >= 0; i--) {
            String line = lines[i].trim();
            if (!line.isEmpty()) {
                return line;
            }
        }
        return null;
    }

    private static String extractFromJsonArray(JsonArray array) {
        for (int i = array.size() - 1; i >= 0; i--) {
            JsonElement element = array.get(i);
            if (element != null && element.isJsonObject()) {
                return element.getAsJsonObject().toString();
            }
        }
        return null;
    }

    public static ReplayResult replay(DumpInput dumpInput) {
        ProcessedDump processedDump = processDumpInput(dumpInput);
        return new ReplayResult(
                processedDump.restoredPacket(),
                Arrays.equals(dumpInput.payloadBytes(), processedDump.restoredPacket().payloadBytes())
        );
    }

    public static ProcessedDump processDumpInput(DumpInput dumpInput) {
        String transferPayload = PacketTransferCodec.encodeDumpInput(dumpInput);
        DumpInput restoredPacket = PacketTransferCodec.decodeDumpInput(transferPayload);
        return new ProcessedDump(transferPayload, transferPayload.getBytes(StandardCharsets.UTF_8), restoredPacket);
    }

    private static byte[] encode(ServerToClientAttachmentPacket packet) {
        FriendlyByteBuf buffer = new FriendlyByteBuf(Unpooled.buffer());
        try {
            ServerToClientAttachmentPacket.encode(packet, buffer);
            byte[] bytes = new byte[buffer.readableBytes()];
            buffer.getBytes(0, bytes);
            return bytes;
        } finally {
            buffer.release();
        }
    }

    private static ServerToClientAttachmentPacket decode(byte[] bytes) {
        FriendlyByteBuf buffer = new FriendlyByteBuf(Unpooled.wrappedBuffer(bytes));
        try {
            return ServerToClientAttachmentPacket.decode(buffer);
        } finally {
            buffer.release();
        }
    }

    private static void printPayloadAnalysis(String label, String packetClass, byte[] payloadBytes) {
        JsonObject analysis = PayloadInspectionSupport.inspectPayload(packetClass, payloadBytes);
        System.out.println(label + " payload matched_parser: " + analysis.get("matched_parser").getAsBoolean());
        System.out.println(label + " payload complete_parse: " + analysis.get("complete_parse").getAsBoolean());
        if (analysis.has("printable")) {
            System.out.println(label + " payload printable: " + analysis.get("printable").getAsString());
        }
        if (analysis.has("packet_specific")) {
            System.out.println(label + " payload packet_specific: " + analysis.get("packet_specific"));
        }
        if (analysis.has("leading_utf")) {
            System.out.println(label + " payload leading_utf: " + analysis.get("leading_utf"));
        }
    }

    public record ReplayResult(
            DumpInput restoredPacket,
            boolean payloadMatches
    ) {
    }

    public record ProcessedDump(
            String processedPayload,
            byte[] processedBytes,
            DumpInput restoredPacket
    ) {
    }

    public record DumpInput(
            long capturedAtMs,
            String direction,
            String packetName,
            String packetClass,
            int wireBytes,
            String payloadBase64,
            byte[] payloadBytes
    ) {
        public static DumpInput fromJsonLine(String jsonLine) {
            JsonObject object = JsonParser.parseString(jsonLine).getAsJsonObject();
            String payloadBase64 = readOptionalString(object, "serialized_payload_base64", "");
            if (payloadBase64.isEmpty()) {
                payloadBase64 = readOptionalString(object, "payload_base64", "");
            }

            return new DumpInput(
                    readOptionalLong(object, "captured_at_ms", -1L),
                    readOptionalString(object, "direction", "<unknown>"),
                    readOptionalString(object, "packet", "<unknown>"),
                    readOptionalString(object, "packet_class", "<unknown>"),
                    readOptionalInt(object, "bytes", payloadBase64.isEmpty() ? 0 : Base64.getDecoder().decode(payloadBase64).length),
                    payloadBase64,
                    payloadBase64.isEmpty() ? new byte[0] : Base64.getDecoder().decode(payloadBase64)
            );
        }
    }

    private static String readOptionalString(JsonObject object, String key, String fallback) {
        return object.has(key) && !object.get(key).isJsonNull() ? object.get(key).getAsString() : fallback;
    }

    private static long readOptionalLong(JsonObject object, String key, long fallback) {
        return object.has(key) && !object.get(key).isJsonNull() ? object.get(key).getAsLong() : fallback;
    }

    private static int readOptionalInt(JsonObject object, String key, int fallback) {
        return object.has(key) && !object.get(key).isJsonNull() ? object.get(key).getAsInt() : fallback;
    }
}
