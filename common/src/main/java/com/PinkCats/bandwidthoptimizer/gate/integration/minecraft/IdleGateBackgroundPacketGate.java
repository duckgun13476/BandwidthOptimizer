package com.PinkCats.bandwidthoptimizer.gate.integration.minecraft;

import com.PinkCats.bandwidthoptimizer.Bandwidthoptimizer;
import com.PinkCats.bandwidthoptimizer.debug.DiagnosticLog;
import com.PinkCats.bandwidthoptimizer.debug.DiagnosticToolRegistry;
import com.PinkCats.bandwidthoptimizer.integration.create.CreateMainPayloadCompat;
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
            "synaxis:cimulink_view_snapshot",
            "watut:main"
    );
    private static final Set<String> BACKGROUND_DROP_PACKET_TYPES = Set.of(
            "ClientboundLevelEventPacket",
            "ClientboundSoundPacket"
    );
    private static final ConcurrentHashMap<String, AtomicLong> DROPPED_BYTES_BY_CLASS = new ConcurrentHashMap<>();
    private static final ConcurrentHashMap<String, AtomicLong> DROPPED_PACKETS_BY_CLASS = new ConcurrentHashMap<>();
    private static final ConcurrentHashMap<String, AtomicLong> PASSED_BYTES_BY_CLASS = new ConcurrentHashMap<>();
    private static final ConcurrentHashMap<String, AtomicLong> PASSED_PACKETS_BY_CLASS = new ConcurrentHashMap<>();
    private static final AtomicLong TOTAL_DROPPED = new AtomicLong();
    private static final AtomicLong TOTAL_DROPPED_BYTES = new AtomicLong();
    private static final AtomicLong NEXT_LOG_MILLIS = new AtomicLong();
    private static final AtomicLong NEXT_PASSED_LOG_MILLIS = new AtomicLong();

    private IdleGateBackgroundPacketGate() {}

    public static String dropKey(Channel channel, Packet<?> packet, PacketFlow flow) {
        if (channel == null || packet == null || flow != PacketFlow.CLIENTBOUND) {
            return null;
        }
        IdleGateServerState.PlayerIdleState state = IdleGateServerState.snapshot(channel);
        if (IdleGateServerState.isResumeDirectWindow(channel)) {
            return null;
        }
        // Non-Create recovery policies use the encoder boundary when no earlier send hook applies.
        if (IdleGateRecoveryRegistry.tryCaptureForIdleGate(channel, packet, state)) {
            return keyOf(packet, null);
        }
        if (!state.mode().suppressesWorldPresentation()) {
            return null;
        }
        String className = packet.getClass().getName();
        if (BACKGROUND_DROP_PACKET_TYPES.contains(simpleName(className))) {
            return keyOf(packet, null);
        }
        if (!isClientboundCustomPayloadPacket(className)) {
            return null;
        }
        String presentationKey = CreateMainPayloadCompat.presentationOnlyKey(packet);
        if (presentationKey != null) {
            return keyOf(packet, presentationKey);
        }
        return BACKGROUND_DROP_PAYLOAD_CHANNELS.contains(normalizePayloadChannel(CustomPayloadPacketCompat.payloadChannel(packet)))
                ? keyOf(packet, null)
                : null;
    }

    public static boolean shouldDrop(Channel channel, Packet<?> packet, PacketFlow flow) {
        return dropKey(channel, packet, flow) != null;
    }

    public static void recordDroppedPacket(Packet<?> packet, int encodedByteLength) {
        recordDroppedPacket(packet, encodedByteLength, keyOf(packet, null));
    }

    public static void recordDroppedPacket(Packet<?> packet, int encodedByteLength, String dropKey) {
        recordDrop(dropKey == null ? keyOf(packet, null) : dropKey, encodedByteLength);
    }

    public static void recordPassedPacket(Channel channel, Packet<?> packet, PacketFlow flow, int encodedByteLength) {
        if (channel == null
                || packet == null
                || flow != PacketFlow.CLIENTBOUND
                || !DiagnosticToolRegistry.isEnabled(DiagnosticToolRegistry.Tool.IDLE_GATE_TRAFFIC)
                || IdleGateServerState.isResumeDirectWindow(channel)) {
            return;
        }
        if (!IdleGateServerState.snapshot(channel).mode().suppressesWorldPresentation()) {
            return;
        }
        String key = keyOf(packet, null);
        if (key.startsWith("ClientboundCustomPayloadPacket:bandwidthoptimizer:transport_")) {
            return;
        }
        PASSED_PACKETS_BY_CLASS.computeIfAbsent(key, ignored -> new AtomicLong()).incrementAndGet();
        PASSED_BYTES_BY_CLASS.computeIfAbsent(key, ignored -> new AtomicLong()).addAndGet(Math.max(encodedByteLength, 0));
        long nowMillis = System.currentTimeMillis();
        long nextMillis = NEXT_PASSED_LOG_MILLIS.get();
        if (nowMillis >= nextMillis && NEXT_PASSED_LOG_MILLIS.compareAndSet(nextMillis, nowMillis + 10_000L)) {
            DiagnosticLog.info(
                    DiagnosticToolRegistry.Tool.IDLE_GATE_TRAFFIC,
                    "event=idle_gate_background_pass packets={} bytes={} top={}",
                    total(PASSED_PACKETS_BY_CLASS),
                    total(PASSED_BYTES_BY_CLASS),
                    summarize(PASSED_BYTES_BY_CLASS, PASSED_PACKETS_BY_CLASS, 24)
            );
        }
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
        resetPassedPackets();
    }

    public static void resetPassedPackets() {
        PASSED_BYTES_BY_CLASS.clear();
        PASSED_PACKETS_BY_CLASS.clear();
        NEXT_PASSED_LOG_MILLIS.set(0L);
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

    private static String keyOf(Packet<?> packet, String detail) {
        String className = packet == null ? "<unknown>" : packet.getClass().getName();
        String simpleName = simpleName(className);
        if (isClientboundCustomPayloadPacket(className)) {
            String payloadChannel = normalizePayloadChannel(CustomPayloadPacketCompat.payloadChannel(packet));
            if (!payloadChannel.isEmpty()) {
                return simpleName + ":" + payloadChannel + (detail == null ? "" : "/" + detail);
            }
        }
        return detail == null ? simpleName : simpleName + ":" + detail;
    }

    private static void recordDrop(String key, int encodedByteLength) {
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
                    key,
                    topDroppedSummary());
        }
    }

    private static String topDroppedSummary() {
        return summarize(DROPPED_BYTES_BY_CLASS, DROPPED_PACKETS_BY_CLASS);
    }

    private static String summarize(ConcurrentHashMap<String, AtomicLong> bytesByKey, ConcurrentHashMap<String, AtomicLong> packetsByKey) {
        return summarize(bytesByKey, packetsByKey, 8);
    }

    private static String summarize(
            ConcurrentHashMap<String, AtomicLong> bytesByKey,
            ConcurrentHashMap<String, AtomicLong> packetsByKey,
            int limit
    ) {
        return bytesByKey.entrySet().stream()
                .sorted((left, right) -> Long.compare(right.getValue().get(), left.getValue().get()))
                .limit(limit)
                .map(entry -> entry.getKey()
                        + "=" + entry.getValue().get()
                        + "B/" + packetsByKey.getOrDefault(entry.getKey(), new AtomicLong()).get())
                .reduce((left, right) -> left + ", " + right)
                .orElse("<none>");
    }

    private static long total(ConcurrentHashMap<String, AtomicLong> values) {
        return values.values().stream().mapToLong(AtomicLong::get).sum();
    }

    public record Snapshot(long savedBytes, long savedPackets) {
        public static Snapshot empty() {
            return new Snapshot(0L, 0L);
        }
    }
}
