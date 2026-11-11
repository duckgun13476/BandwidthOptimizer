package com.PinkCats.bandwidthoptimizer.channel;

import net.minecraft.network.protocol.PacketFlow;

public final class LargePacketSourceWarningRegressionMain {
    private static final long MIB = 1024L * 1024L;
    private static final long WINDOW_NANOS = LargePacketSourceWarning.WINDOW_NANOS;

    private LargePacketSourceWarningRegressionMain() {
    }

    public static void main(String[] args) {
        verifiesSizeBoundaries();
        verifiesWarningIdentifiesSourceAndDirection();
        verifiesUnknownAndSanitizedFields();
        verifiesGlobalBoundedRateLimit();
        System.out.println("large-packet-source-warning: passed");
    }

    private static void verifiesSizeBoundaries() {
        require(LargePacketSourceWarning.classify((int) (8 * MIB)) == LargePacketSourceWarning.Decision.NORMAL,
                "exactly 8 MiB must remain at the normal limit");
        require(LargePacketSourceWarning.classify((int) (8 * MIB + 1)) == LargePacketSourceWarning.Decision.WARN,
                "packets just above 8 MiB must warn");
        require(LargePacketSourceWarning.classify((int) (64 * MIB)) == LargePacketSourceWarning.Decision.WARN,
                "exactly 64 MiB must warn rather than reject");
        require(LargePacketSourceWarning.classify((int) (64 * MIB + 1)) == LargePacketSourceWarning.Decision.REJECT,
                "packets above 64 MiB must be classified for rejection");

        Collector collector = new Collector();
        boolean emitted = LargePacketSourceWarning.warnIfNeeded(
                "custom_payload:example:large_sync", "example.LargePacket", PacketFlow.CLIENTBOUND,
                (int) (8 * MIB), 1L, new LargePacketSourceWarning.RateLimiter(), collector::accept);
        require(!emitted && collector.size() == 0, "normal-sized packets must not produce warnings");
        emitted = LargePacketSourceWarning.warnIfNeeded(
                "custom_payload:example:large_sync", "example.LargePacket", PacketFlow.CLIENTBOUND,
                (int) (64 * MIB + 1), 2L, new LargePacketSourceWarning.RateLimiter(), collector::accept);
        require(!emitted && collector.size() == 0,
                "rejection classification must not produce an ordinary warning from this helper");
    }

    private static void verifiesWarningIdentifiesSourceAndDirection() {
        Collector collector = new Collector();
        LargePacketSourceWarning.RateLimiter limiter = new LargePacketSourceWarning.RateLimiter();
        boolean emitted = LargePacketSourceWarning.warnIfNeeded(
                "custom_payload:example:large_sync", "example.LargePacket", PacketFlow.CLIENTBOUND,
                (int) (9 * MIB), 10L, limiter, collector::accept);

        require(emitted && collector.size() == 1, "an admitted oversized packet must emit one warning");
        String warning = collector.last();
        require(warning.contains("direction=server_to_client"), "warning must identify packet direction");
        require(warning.contains("source=custom_payload:example:large_sync"),
                "warning must retain the resolved source key");
        require(warning.contains("packet=example.LargePacket"), "warning must identify packet class");
        require(warning.contains("bytes=" + 9 * MIB), "warning must include exact logical byte count");
        require(warning.contains("warnAboveMiB=8") && warning.contains("rejectAboveMiB=64"),
                "warning must include the applicable size limits");
        require(!warning.contains("mod="), "source namespace must not be presented as verified mod ownership");
        require(!warning.contains("payload="), "warning must not include packet contents");
    }

    private static void verifiesUnknownAndSanitizedFields() {
        Collector collector = new Collector();
        LargePacketSourceWarning.warnIfNeeded(
                null, "packet\nClass", null, (int) (10 * MIB), 20L,
                new LargePacketSourceWarning.RateLimiter(), collector::accept);
        String warning = collector.last();
        require(warning.contains("source=unknown"), "unresolved sources must be explicit as unknown");
        require(warning.contains("direction=unknown"), "unresolved direction must be explicit as unknown");
        require(warning.contains("packet=packet?Class"), "control characters must be removed from packet class");

        String longSource = "source-" + "x".repeat(512);
        collector = new Collector();
        LargePacketSourceWarning.warnIfNeeded(
                longSource, "packet.Class", PacketFlow.SERVERBOUND, (int) (11 * MIB), 30L,
                new LargePacketSourceWarning.RateLimiter(), collector::accept);
        require(collector.last().length() < 512, "source fields must have a bounded output length");
    }

    private static void verifiesGlobalBoundedRateLimit() {
        Collector collector = new Collector();
        LargePacketSourceWarning.RateLimiter limiter = new LargePacketSourceWarning.RateLimiter();
        for (int i = 0; i < LargePacketSourceWarning.MAX_WARNINGS_PER_WINDOW; i++) {
            boolean emitted = LargePacketSourceWarning.warnIfNeeded(
                    "packet:sample", "example.Packet", PacketFlow.SERVERBOUND, (int) (12 * MIB),
                    100L + i, limiter, collector::accept);
            require(emitted, "warning cap must admit each slot within the window");
        }
        boolean suppressed = LargePacketSourceWarning.warnIfNeeded(
                "packet:another", "example.OtherPacket", PacketFlow.SERVERBOUND, (int) (12 * MIB),
                200L, limiter, collector::accept);
        require(!suppressed, "warnings beyond the global window cap must be suppressed");
        require(collector.size() == LargePacketSourceWarning.MAX_WARNINGS_PER_WINDOW,
                "suppressed warnings must not be emitted");

        boolean nextWindowAdmitted = LargePacketSourceWarning.warnIfNeeded(
                "packet:sample", "example.Packet", PacketFlow.SERVERBOUND, (int) (12 * MIB),
                100L + WINDOW_NANOS, limiter, collector::accept);
        require(nextWindowAdmitted, "the global cap must reset when its bounded window expires");
        require(collector.size() == LargePacketSourceWarning.MAX_WARNINGS_PER_WINDOW + 1,
                "the next window must emit one new warning");
    }

    private static final class Collector {
        private final java.util.List<String> messages = new java.util.ArrayList<>();

        private void accept(String value) {
            messages.add(value);
        }

        private int size() {
            return messages.size();
        }

        private String last() {
            return messages.get(messages.size() - 1);
        }
    }

    private static void require(boolean value, String message) {
        if (!value) {
            throw new IllegalStateException(message);
        }
    }
}
