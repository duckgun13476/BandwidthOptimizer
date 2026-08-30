package com.PinkCats.bandwidthoptimizer.channel;

import com.PinkCats.bandwidthoptimizer.debug.DiagnosticLog;
import com.PinkCats.bandwidthoptimizer.debug.DiagnosticToolRegistry;
import io.netty.channel.Channel;
import io.netty.channel.ChannelHandlerContext;
import io.netty.util.AttributeKey;
import net.minecraft.network.protocol.PacketFlow;

import java.util.LinkedHashMap;
import java.util.Map;

/** Bounded per-connection warning for unusually large clientbound BO wire bursts. */
public final class ChannelOutboundBurstWarning {

    private static final long WINDOW_NANOS = 1_000_000_000L;
    private static final long WARNING_COOLDOWN_NANOS = 30_000_000_000L;
    private static final long WIRE_THRESHOLD_BYTES = 512L * 1024L;
    private static final int MAX_SOURCES = 8;
    private static final AttributeKey<State> STATE_KEY =
            AttributeKey.valueOf("bandwidthoptimizer:clientbound_outbound_burst_warning");

    private ChannelOutboundBurstWarning() {
    }

    public static void recordLogicalPacket(
            ChannelHandlerContext context,
            PacketFlow flow,
            String packetClassName,
            int logicalBytes
    ) {
        if (!DiagnosticToolRegistry.isEnabled(DiagnosticToolRegistry.Tool.OUTBOUND_BURST)
                || flow != PacketFlow.CLIENTBOUND
                || context == null
                || context.channel() == null
                || logicalBytes <= 0) {
            return;
        }
        state(context.channel()).recordLogical(packetClassName, logicalBytes, System.nanoTime());
    }

    public static void recordWireFrame(
            ChannelHandlerContext context,
            PacketFlow flow,
            int wireBytes,
            boolean transportCarrier
    ) {
        if (!DiagnosticToolRegistry.isEnabled(DiagnosticToolRegistry.Tool.OUTBOUND_BURST)
                || flow != PacketFlow.CLIENTBOUND
                || context == null
                || context.channel() == null
                || wireBytes <= 0) {
            return;
        }
        Burst burst = state(context.channel()).recordWire(wireBytes, transportCarrier, System.nanoTime());
        if (burst != null) {
            DiagnosticLog.warn(
                    DiagnosticToolRegistry.Tool.OUTBOUND_BURST,
                    "v=1 domain=transport event=clientbound_burst channel={}, wireBytes={}, logicalBytes={}, wireFrames={}, transportFrames={}, logicalPackets={}, topSources={}",
                    ChannelIdentity.shortText(context.channel()),
                    burst.wireBytes,
                    burst.logicalBytes,
                    burst.wireFrames,
                    burst.transportFrames,
                    burst.logicalPackets,
                    burst.topSources
            );
        }
    }

    static void clear(Channel channel) {
        if (channel != null) {
            channel.attr(STATE_KEY).set(null);
        }
    }

    private static State state(Channel channel) {
        State current = channel.attr(STATE_KEY).get();
        if (current != null) {
            return current;
        }
        State created = new State();
        State raced = channel.attr(STATE_KEY).setIfAbsent(created);
        return raced == null ? created : raced;
    }

    static final class State {
        private long windowStartedNanos;
        private long warningAllowedAtNanos;
        private long wireBytes;
        private long logicalBytes;
        private int wireFrames;
        private int transportFrames;
        private int logicalPackets;
        private final Map<String, Long> sourceBytes = new LinkedHashMap<>();

        void recordLogical(String packetClassName, int bytes, long nowNanos) {
            rotateWindow(nowNanos);
            logicalBytes += bytes;
            logicalPackets++;
            String source = packetClassName == null || packetClassName.isBlank() ? "<unknown>" : packetClassName;
            if (sourceBytes.containsKey(source) || sourceBytes.size() < MAX_SOURCES) {
                sourceBytes.merge(source, (long) bytes, Long::sum);
            } else {
                sourceBytes.merge("<other>", (long) bytes, Long::sum);
            }
        }

        Burst recordWire(int bytes, boolean transportCarrier, long nowNanos) {
            rotateWindow(nowNanos);
            wireBytes += bytes;
            wireFrames++;
            if (transportCarrier) {
                transportFrames++;
            }
            if (wireBytes < WIRE_THRESHOLD_BYTES || nowNanos < warningAllowedAtNanos) {
                return null;
            }
            warningAllowedAtNanos = nowNanos + WARNING_COOLDOWN_NANOS;
            return new Burst(wireBytes, logicalBytes, wireFrames, transportFrames, logicalPackets, topSources());
        }

        private void rotateWindow(long nowNanos) {
            if (windowStartedNanos != 0L && nowNanos - windowStartedNanos < WINDOW_NANOS) {
                return;
            }
            windowStartedNanos = nowNanos;
            wireBytes = 0L;
            logicalBytes = 0L;
            wireFrames = 0;
            transportFrames = 0;
            logicalPackets = 0;
            sourceBytes.clear();
        }

        private String topSources() {
            StringBuilder result = new StringBuilder();
            sourceBytes.entrySet().stream()
                    .sorted(Map.Entry.<String, Long>comparingByValue().reversed())
                    .limit(3)
                    .forEach(entry -> {
                        if (result.length() > 0) {
                            result.append(',');
                        }
                        result.append(entry.getKey()).append('=').append(entry.getValue());
                    });
            return result.length() == 0 ? "<none>" : result.toString();
        }
    }

    record Burst(
            long wireBytes,
            long logicalBytes,
            int wireFrames,
            int transportFrames,
            int logicalPackets,
            String topSources
    ) {
    }
}
