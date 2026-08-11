package com.PinkCats.bandwidthoptimizer.debug;

import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Set;

public final class PacketClassTraceDiagnosticRegressionMain {

    private PacketClassTraceDiagnosticRegressionMain() {}

    public static void main(String[] args) {
        verifyDefaultDisabled();
        verifyExactClassAllowlist();
        verifyStableHash();
        verifyControlPayloadRoundTrip();
        System.out.println("Exact packet-class trace regression passed.");
    }

    private static void verifyControlPayloadRoundTrip() {
        PacketClassTraceControlPayload source = PacketClassTraceControlPayload.enabled(
                30,
                240,
                Set.of("com.example.ExactPacket")
        );
        PacketClassTraceControlPayload restored = PacketClassTraceControlPayload.fromBytes(source.toBytes());
        if (!restored.enabled()
                || restored.minutes() != 30
                || restored.maxEventsPerMinute() != 240
                || !restored.packetClasses().equals(List.of("com.example.ExactPacket"))) {
            throw new IllegalStateException("Packet trace control payload did not round-trip: " + restored);
        }
        if (PacketClassTraceControlPayload.fromBytes(PacketClassTraceControlPayload.disabled().toBytes()).enabled()) {
            throw new IllegalStateException("Disabled packet trace control payload became enabled");
        }
    }

    private static void verifyExactClassAllowlist() {
        Set<String> classes = PacketClassTraceDiagnostic.parseExactClassNames(
                "net.minecraft.network.protocol.game.ServerboundUseItemOnPacket,"
                        + "com.example.Valid$Nested,"
                        + "com.example.*,"
                        + "prefix,"
                        + "com.example.Valid$Nested"
        );
        if (!classes.equals(Set.of(
                "net.minecraft.network.protocol.game.ServerboundUseItemOnPacket",
                "com.example.Valid$Nested"
        ))) {
            throw new IllegalStateException("Exact class allowlist accepted an invalid or duplicate entry: " + classes);
        }
    }

    private static void verifyStableHash() {
        String digest = PacketClassTraceDiagnostic.sha256ForTesting("packet-trace".getBytes(StandardCharsets.UTF_8));
        if (!"ac5d6610e2de48c2ee7b4e7a9c09f7b4b32dd1cd7919b88e47bde6e68093355b".equals(digest)) {
            throw new IllegalStateException("Unexpected SHA-256 digest: " + digest);
        }
    }

    private static void verifyDefaultDisabled() {
        if (DiagnosticToolRegistry.isEnabled(DiagnosticToolRegistry.Tool.PACKET_CLASS_TRACE)) {
            throw new IllegalStateException("Exact packet-class trace must be disabled by default");
        }
    }
}
