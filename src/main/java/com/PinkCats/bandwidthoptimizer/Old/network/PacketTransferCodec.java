package com.PinkCats.bandwidthoptimizer.Old.network;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import java.util.Base64;

public final class PacketTransferCodec {

    private PacketTransferCodec() {
    }

    public static String encodeDumpInput(PacketDumpReplayMain.DumpInput dumpInput) {
        JsonObject object = new JsonObject();
        object.addProperty("captured_at_ms", dumpInput.capturedAtMs());
        object.addProperty("direction", dumpInput.direction());
        object.addProperty("packet", dumpInput.packetName());
        object.addProperty("packet_class", dumpInput.packetClass());
        object.addProperty("payload_base64", Base64.getEncoder().encodeToString(dumpInput.payloadBytes()));
        return object.toString();
    }

    public static PacketDumpReplayMain.DumpInput decodeDumpInput(String encodedPayload) {
        JsonObject object = JsonParser.parseString(encodedPayload).getAsJsonObject();
        String payloadBase64 = object.get("payload_base64").getAsString();
        return new PacketDumpReplayMain.DumpInput(
                object.has("captured_at_ms") ? object.get("captured_at_ms").getAsLong() : -1L,
                object.get("direction").getAsString(),
                object.get("packet").getAsString(),
                object.get("packet_class").getAsString(),
                payloadBase64.isEmpty() ? 0 : Base64.getDecoder().decode(payloadBase64).length,
                payloadBase64,
                Base64.getDecoder().decode(payloadBase64)
        );
    }
}
