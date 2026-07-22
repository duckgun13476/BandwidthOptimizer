package com.PinkCats.bandwidthoptimizer.gate.integration.minecraft;

import com.PinkCats.bandwidthoptimizer.Bandwidthoptimizer;
import com.PinkCats.bandwidthoptimizer.integration.minecraft.CustomPayloadPacketCompat;
import com.PinkCats.bandwidthoptimizer.gate.IdleGateMode;
import com.PinkCats.bandwidthoptimizer.gate.IdleGateServerState;
import com.PinkCats.bandwidthoptimizer.gate.recovery.IdleGateRecoveryRegistry;
import io.netty.channel.Channel;
import net.minecraft.network.protocol.Packet;
import net.minecraft.network.protocol.PacketFlow;

import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;

public final class IdleGateBackgroundPacketGate {

    private static final Set<String> BACKGROUND_DROP_PAYLOAD_CHANNELS = Set.of(
            "create:clientbound_chain_conveyor",
            "create:funnel_flap",
            "create:server_speed",
            "neoforge:custom_time_packet",
            "powerful_dummy:damage_data",
            "synaxis:cimulink_view_snapshot"
    );
    private static final Set<String> BACKGROUND_DROP_PACKET_TYPES = Set.of(
            "ClientboundSoundPacket"
    );
    private static final ConcurrentHashMap<String, AtomicLong> DROPPED_BYTES_BY_CLASS = new ConcurrentHashMap<>();
    private static final ConcurrentHashMap<String, AtomicLong> DROPPED_PACKETS_BY_CLASS = new ConcurrentHashMap<>();
    private static final AtomicLong TOTAL_DROPPED = new AtomicLong();
    private static final AtomicLong TOTAL_DROPPED_BYTES = new AtomicLong();
    private static final AtomicLong NEXT_LOG_MILLIS = new AtomicLong();

    private IdleGateBackgroundPacketGate() {}

    public static boolean shouldDrop(Channel channel, Packet<?> packet, PacketFlow flow) {
        if (channel == null || packet == null || flow != PacketFlow.CLIENTBOUND) {
            return false;
        }
        // Non-Create recovery policies use the encoder boundary when no earlier send hook applies.
        if (IdleGateRecoveryRegistry.tryCapture(channel, packet, null)) {
            return true;
        }
        if (IdleGateServerState.isResumeDirectWindow(channel)) {
            return false;
        }
        IdleGateServerState.PlayerIdleState state = IdleGateServerState.snapshot(channel);
        if (!state.mode().suppressesWorldPresentation()) {
            return false;
        }
        String className = packet.getClass().getName();
        if (BACKGROUND_DROP_PACKET_TYPES.contains(simpleName(className))) {
            return true;
        }
        return isClientboundCustomPayloadPacket(className)
                && BACKGROUND_DROP_PAYLOAD_CHANNELS.contains(normalizePayloadChannel(CustomPayloadPacketCompat.payloadChannel(packet)));
    }

    public static void recordDroppedPacket(Packet<?> packet, int encodedByteLength) {
        String className = packet == null ? "<unknown>" : packet.getClass().getName();
        recordDrop(packet, className, encodedByteLength);
    }

    public static Snapshot snapshot() {
        return new Snapshot(TOTAL_DROPPED_BYTES.get(), TOTAL_DROPPED.get());
    }

    public static void reset() {
        DROPPED_BYTES_BY_CLASS.clear();
        DROPPED_PACKETS_BY_CLASS.clear();
        TOTAL_DROPPED.set(0L);
        TOTAL_DROPPED_BYTES.set(0L);
        NEXT_LOG_MILLIS.set(0L);
    }

    private static boolean isClientboundCustomPayloadPacket(String className) {
        return className != null && className.endsWith(".ClientboundCustomPayloadPacket");
    }

    private static String normalizePayloadChannel(String payloadChannel) {
        return payloadChannel == null ? "" : payloadChannel.trim().toLowerCase(java.util.Locale.ROOT);
    }

    private static String simpleName(String className) {
        if (className == null) {
            return "";
        }
        int index = className.lastIndexOf('.');
        return index < 0 ? className : className.substring(index + 1);
    }

    private static String keyOf(Packet<?> packet, String className) {
        String simpleName = simpleName(className);
        if (isClientboundCustomPayloadPacket(className)) {
            String payloadChannel = normalizePayloadChannel(CustomPayloadPacketCompat.payloadChannel(packet));
            if (!payloadChannel.isEmpty()) {
                return simpleName + ":" + payloadChannel;
            }
        }
        return simpleName;
    }

    private static void recordDrop(Packet<?> packet, String className, int encodedByteLength) {
        String key = keyOf(packet, className);
        DROPPED_PACKETS_BY_CLASS.computeIfAbsent(key, ignored -> new AtomicLong()).incrementAndGet();
        DROPPED_BYTES_BY_CLASS.computeIfAbsent(key, ignored -> new AtomicLong()).addAndGet(Math.max(encodedByteLength, 0));
        long total = TOTAL_DROPPED.incrementAndGet();
        long totalBytes = TOTAL_DROPPED_BYTES.addAndGet(Math.max(encodedByteLength, 0));
        long nowMillis = System.currentTimeMillis();
        long nextMillis = NEXT_LOG_MILLIS.get();
        if (nowMillis >= nextMillis && NEXT_LOG_MILLIS.compareAndSet(nextMillis, nowMillis + 10_000L)) {
            Bandwidthoptimizer.LOGGER.info("[IdleGate] background drop total={} bytes={} current={} top={}",
                    total,
                    totalBytes,
                    simpleName(className),
                    topDroppedSummary());
        }
    }

    private static String topDroppedSummary() {
        return summarize(DROPPED_BYTES_BY_CLASS, DROPPED_PACKETS_BY_CLASS);
    }

    private static String summarize(ConcurrentHashMap<String, AtomicLong> bytesByKey, ConcurrentHashMap<String, AtomicLong> packetsByKey) {
        return bytesByKey.entrySet().stream()
                .sorted((left, right) -> Long.compare(right.getValue().get(), left.getValue().get()))
                .limit(8)
                .map(entry -> entry.getKey()
                        + "=" + entry.getValue().get()
                        + "B/" + packetsByKey.getOrDefault(entry.getKey(), new AtomicLong()).get())
                .reduce((left, right) -> left + ", " + right)
                .orElse("<none>");
    }

    public record Snapshot(long savedBytes, long savedPackets) {
        public static Snapshot empty() {
            return new Snapshot(0L, 0L);
        }
    }
}
