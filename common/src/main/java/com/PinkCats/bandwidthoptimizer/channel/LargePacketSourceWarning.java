package com.PinkCats.bandwidthoptimizer.channel;

import net.minecraft.network.protocol.PacketFlow;
import net.minecraft.network.protocol.Packet;
import com.PinkCats.bandwidthoptimizer.report.ChannelTransportPacketRankSourceResolver;

import java.util.Locale;
import java.util.Objects;
import java.util.function.Consumer;

/** Bounded operational warning for logical packets that exceed the normal BO single-packet budget. */
public final class LargePacketSourceWarning {
    public static final int WARN_ABOVE_BYTES = 8 * 1024 * 1024;
    public static final int REJECT_ABOVE_BYTES = 64 * 1024 * 1024;
    public static final int MAX_WARNINGS_PER_WINDOW = 12;
    public static final long WINDOW_NANOS = 60_000_000_000L;
    private static final int MAX_SOURCE_CHARS = 128;
    private static final int MAX_PACKET_CHARS = 96;
    private static final RateLimiter PROCESS_LIMITER = new RateLimiter();

    private LargePacketSourceWarning() {}

    public static void inspect(Packet<?> packet, PacketFlow flow, int bytes) {
        Decision decision = classify(bytes);
        if (decision == Decision.NORMAL) return;
        String source = ChannelTransportPacketRankSourceResolver.resolveSourceKey(packet);
        String packetClass = packet == null ? "unknown" : packet.getClass().getName();
        if (decision == Decision.REJECT) {
            throw new IllegalArgumentException("BO logical packet exceeds 64 MiB: source=" + sanitize(source, MAX_SOURCE_CHARS)
                    + " packet=" + sanitize(packetClass, MAX_PACKET_CHARS) + " bytes=" + bytes);
        }
        warnIfNeeded(source, packetClass, flow, bytes);
    }

    public static Decision classify(int bytes) {
        if (bytes <= WARN_ABOVE_BYTES) return Decision.NORMAL;
        return bytes > REJECT_ABOVE_BYTES ? Decision.REJECT : Decision.WARN;
    }

    /** Logs one actionable warning per admitted oversized packet, with a process-wide fixed cap. */
    public static boolean warnIfNeeded(
            String sourceKey,
            String packetClass,
            PacketFlow flow,
            int bytes
    ) {
        return warnIfNeeded(sourceKey, packetClass, flow, bytes, System.nanoTime(), PROCESS_LIMITER,
                message -> com.PinkCats.bandwidthoptimizer.Bandwidthoptimizer.LOGGER.warn("[BO] {}", message));
    }

    static boolean warnIfNeeded(
            String sourceKey,
            String packetClass,
            PacketFlow flow,
            int bytes,
            long nowNanos,
            RateLimiter limiter,
            Consumer<String> sink
    ) {
        Objects.requireNonNull(limiter, "limiter");
        Objects.requireNonNull(sink, "sink");
        if (classify(bytes) != Decision.WARN || !limiter.tryAcquire(nowNanos)) return false;
        String source = sanitize(sourceKey, MAX_SOURCE_CHARS);
        String packet = sanitize(packetClass, MAX_PACKET_CHARS);
        String direction = flow == PacketFlow.CLIENTBOUND ? "server_to_client"
                : flow == PacketFlow.SERVERBOUND ? "client_to_server" : "unknown";
        sink.accept(String.format(Locale.ROOT,
                "Oversized logical packet: direction=%s source=%s packet=%s bytes=%d MiB=%.2f warnAboveMiB=8 rejectAboveMiB=64",
                direction, source, packet, bytes, bytes / (1024.0 * 1024.0)));
        return true;
    }

    private static String sanitize(String value, int maximum) {
        if (value == null || value.isBlank()) return "unknown";
        StringBuilder out = new StringBuilder(Math.min(value.length(), maximum));
        for (int i = 0; i < value.length() && out.length() < maximum; i++) {
            char c = value.charAt(i);
            out.append(Character.isISOControl(c) ? '?' : c);
        }
        return out.length() == 0 ? "unknown" : out.toString();
    }

    enum Decision { NORMAL, WARN, REJECT }

    static final class RateLimiter {
        private long windowStartedAt;
        private int admitted;
        private boolean initialized;

        synchronized boolean tryAcquire(long nowNanos) {
            if (!initialized || nowNanos - windowStartedAt >= WINDOW_NANOS || nowNanos < windowStartedAt) {
                initialized = true;
                windowStartedAt = nowNanos;
                admitted = 0;
            }
            if (admitted >= MAX_WARNINGS_PER_WINDOW) return false;
            admitted++;
            return true;
        }
    }
}
