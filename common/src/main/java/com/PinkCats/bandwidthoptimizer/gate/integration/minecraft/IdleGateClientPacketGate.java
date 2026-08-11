package com.PinkCats.bandwidthoptimizer.gate.integration.minecraft;

import com.PinkCats.bandwidthoptimizer.debug.DiagnosticLog;
import com.PinkCats.bandwidthoptimizer.debug.DiagnosticToolRegistry;
import com.PinkCats.bandwidthoptimizer.integration.minecraft.CustomPayloadPacketCompat;
import com.PinkCats.bandwidthoptimizer.gate.IdleGateClientController;
import com.PinkCats.bandwidthoptimizer.gate.IdleGateMode;
import net.minecraft.network.protocol.Packet;
import net.minecraft.network.protocol.PacketFlow;

import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;

public final class IdleGateClientPacketGate {

    private static final boolean CLIENT_GATE_ENABLED = Boolean.getBoolean("bandwidthoptimizer.idle.enableClientGate");
    private static final Set<String> BACKGROUND_DROP_PAYLOAD_CHANNELS = Set.of(
            "synaxis:cimulink_view_request"
    );
    private static final ConcurrentHashMap<String, AtomicLong> DROPPED_BY_CHANNEL = new ConcurrentHashMap<>();
    private static final AtomicLong TOTAL_DROPPED = new AtomicLong();
    private static final AtomicLong TOTAL_DROPPED_BYTES = new AtomicLong();
    private static final AtomicLong NEXT_LOG_MILLIS = new AtomicLong();

    private IdleGateClientPacketGate() {}

    public static boolean shouldDrop(Packet<?> packet, PacketFlow flow) {
        if (!CLIENT_GATE_ENABLED || Boolean.getBoolean("bandwidthoptimizer.idle.disableClientGate")) {
            return false;
        }
        if (packet == null || flow != PacketFlow.SERVERBOUND || IdleGateClientController.currentMode() != IdleGateMode.BACKGROUND_IDLE) {
            return false;
        }
        String className = packet.getClass().getName();
        if (!isServerboundCustomPayloadPacket(className)) {
            return false;
        }
        return BACKGROUND_DROP_PAYLOAD_CHANNELS.contains(normalizePayloadChannel(CustomPayloadPacketCompat.payloadChannel(packet)));
    }

    public static void recordDroppedPacket(Packet<?> packet, int encodedByteLength) {
        long total = TOTAL_DROPPED.incrementAndGet();
        long totalBytes = TOTAL_DROPPED_BYTES.addAndGet(Math.max(encodedByteLength, 0));
        if (DiagnosticToolRegistry.isEnabled(DiagnosticToolRegistry.Tool.IDLE_GATE_TRAFFIC)) {
            String payloadChannel = normalizePayloadChannel(CustomPayloadPacketCompat.payloadChannel(packet));
            String key = payloadChannel.isEmpty() ? "<unknown>" : payloadChannel;
            DROPPED_BY_CHANNEL.computeIfAbsent(key, ignored -> new AtomicLong()).incrementAndGet();
            long nowMillis = System.currentTimeMillis();
            long nextMillis = NEXT_LOG_MILLIS.get();
            if (nowMillis >= nextMillis && NEXT_LOG_MILLIS.compareAndSet(nextMillis, nowMillis + 30_000L)) {
                DiagnosticLog.info(
                        DiagnosticToolRegistry.Tool.IDLE_GATE_TRAFFIC,
                        "event=idle_gate_client_background_drop total={} bytes={} current={}",
                        total,
                        totalBytes,
                        key
                );
            }
        }
    }

    private static boolean isServerboundCustomPayloadPacket(String className) {
        return className != null && className.endsWith(".ServerboundCustomPayloadPacket");
    }

    private static String normalizePayloadChannel(String payloadChannel) {
        return payloadChannel == null ? "" : payloadChannel.trim().toLowerCase(java.util.Locale.ROOT);
    }
}
