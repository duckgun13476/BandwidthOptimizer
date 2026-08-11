package com.PinkCats.bandwidthoptimizer.debug;

import com.PinkCats.bandwidthoptimizer.Bandwidthoptimizer;
import com.PinkCats.bandwidthoptimizer.channel.ChannelIdentity;
import com.PinkCats.bandwidthoptimizer.channel.ChannelTransportPacketCodec;
import com.PinkCats.bandwidthoptimizer.channel.capture.ChannelCapturedFrame;
import com.PinkCats.bandwidthoptimizer.server.stat.ChannelBandwidthStats;
import com.PinkCats.bandwidthoptimizer.server.stat.ServerBandwidthStatsRegistry;
import io.netty.channel.Channel;
import io.netty.channel.ChannelHandlerContext;
import io.netty.util.AttributeKey;
import net.minecraft.network.protocol.Packet;
import net.minecraft.network.protocol.PacketFlow;

import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.regex.Pattern;

public final class PacketClassTraceDiagnostic {

    private static final Pattern EXACT_CLASS_NAME = Pattern.compile(
            "[A-Za-z_$][A-Za-z0-9_$]*(\\.[A-Za-z_$][A-Za-z0-9_$]*)+"
    );
    private static final int DEFAULT_MAX_EVENTS_PER_MINUTE = 240;
    private static final int MAX_CONFIGURED_CLASSES = 64;
    private static final int MAX_EVENTS_PER_MINUTE = 10_000;
    private static final AttributeKey<SequenceState> SEQUENCE_STATE_KEY =
            AttributeKey.valueOf("bandwidthoptimizer:packet_class_trace_sequence");
    private static final ThreadLocal<MessageDigest> SHA_256 = ThreadLocal.withInitial(
            PacketClassTraceDiagnostic::newSha256
    );
    private static final AtomicLong SESSION_IDS = new AtomicLong();
    private static final ConcurrentHashMap<String, TraceSession> SESSIONS = new ConcurrentHashMap<>();

    private PacketClassTraceDiagnostic() {}

    static Set<String> parseExactClassNames(String configuredValue) {
        LinkedHashSet<String> configuredClasses = new LinkedHashSet<>();
        String safeConfiguredValue = configuredValue == null ? "" : configuredValue;
        for (String candidate : safeConfiguredValue.split(",")) {
            String className = candidate.trim();
            if (className.isEmpty()) {
                continue;
            }
            if (!EXACT_CLASS_NAME.matcher(className).matches()) {
                Bandwidthoptimizer.LOGGER.warn("Ignoring non-exact BO packet trace class name: {}", className);
                continue;
            }
            if (configuredClasses.size() >= MAX_CONFIGURED_CLASSES) {
                Bandwidthoptimizer.LOGGER.warn("Ignoring BO packet trace classes beyond the {} class limit", MAX_CONFIGURED_CLASSES);
                break;
            }
            configuredClasses.add(className);
        }

        return Set.copyOf(configuredClasses);
    }

    public static Set<String> requireExactClassNames(String configuredValue) {
        LinkedHashSet<String> classes = new LinkedHashSet<>();
        String safeConfiguredValue = configuredValue == null ? "" : configuredValue;
        for (String candidate : safeConfiguredValue.split("[,\\s]+")) {
            String className = candidate.trim();
            if (className.isEmpty()) {
                continue;
            }
            if (!EXACT_CLASS_NAME.matcher(className).matches()) {
                throw new IllegalArgumentException("Packet class must be an exact fully qualified Java class name: " + className);
            }
            if (classes.size() >= MAX_CONFIGURED_CLASSES) {
                throw new IllegalArgumentException("Packet trace accepts at most " + MAX_CONFIGURED_CLASSES + " classes");
            }
            classes.add(className);
        }
        if (classes.isEmpty()) {
            throw new IllegalArgumentException("At least one exact packet class is required");
        }
        return Set.copyOf(classes);
    }

    public static void configure(
            Channel channel,
            Set<String> classes,
            int minutes,
            int eventsPerMinute
    ) {
        if (channel == null || classes == null || classes.isEmpty()) {
            return;
        }
        int safeMinutes = DiagnosticToolRegistry.validateMinutes(minutes);
        int safeRate = Math.max(1, Math.min(eventsPerMinute, MAX_EVENTS_PER_MINUTE));
        String channelId = ChannelIdentity.longText(channel);
        TraceSession session = new TraceSession(
                SESSION_IDS.incrementAndGet(),
                Set.copyOf(classes),
                System.currentTimeMillis() + safeMinutes * 60_000L,
                safeRate
        );
        SESSIONS.put(channelId, session);
        channel.closeFuture().addListener(future -> SESSIONS.remove(channelId, session));
        DiagnosticToolRegistry.enable(DiagnosticToolRegistry.Tool.PACKET_CLASS_TRACE, safeMinutes);
    }

    public static void disable(Channel channel) {
        if (channel != null) {
            SESSIONS.remove(ChannelIdentity.longText(channel));
        }
    }

    public static void clearSessions() {
        SESSIONS.clear();
    }

    public static int defaultMaxEventsPerMinute() {
        return DEFAULT_MAX_EVENTS_PER_MINUTE;
    }

    public static boolean isConfiguredAndEnabled(ChannelHandlerContext context) {
        return session(context) != null;
    }

    public static void recordOutboundDirect(
            ChannelHandlerContext context,
            String protocolName,
            Packet<?> packet,
            PacketFlow packetFlow,
            byte[] packetBytes,
            String reason
    ) {
        TraceSession session = matchingSession(context, packet);
        if (session == null || !session.acquireRatePermit()) {
            return;
        }
        emit(createEvent(
                context,
                "encode",
                packetFlow,
                packet.getClass().getName(),
                protocolName,
                "direct",
                reason,
                packetBytes,
                packetBytes,
                1
        ));
    }

    public static void recordOutboundTransport(
            ChannelHandlerContext context,
            String protocolName,
            Packet<?> packet,
            PacketFlow packetFlow,
            byte[] originalPacketBytes,
            ChannelTransportPacketCodec.WrappedTransportFrame wrappedFrame
    ) {
        TraceSession session = matchingSession(context, packet);
        if (session == null || wrappedFrame == null || !session.acquireRatePermit()) {
            return;
        }
        emit(createEvent(
                context,
                "encode",
                packetFlow,
                packet.getClass().getName(),
                protocolName,
                "transparent",
                wrappedFrame.frameKind().name(),
                originalPacketBytes,
                wrappedFrame.transportFrameBytes(),
                Math.max(wrappedFrame.originalPacketCount(), 1)
        ));
    }

    public static OutboundBatchToken beginOutboundBatch(
            ChannelHandlerContext context,
            String protocolName,
            Packet<?> packet,
            PacketFlow packetFlow,
            byte[] originalPacketBytes
    ) {
        TraceSession session = matchingSession(context, packet);
        if (session == null || !session.acquireRatePermit()) {
            return null;
        }
        Endpoint endpoint = endpoint(context);
        return new OutboundBatchToken(
                ChannelIdentity.longText(context.channel()),
                session.id,
                System.currentTimeMillis(),
                nextSequence(context == null ? null : context.channel(), packetFlow),
                flowName(packetFlow),
                packet.getClass().getName(),
                safe(protocolName),
                endpoint,
                fingerprint(originalPacketBytes)
        );
    }

    public static void completeOutboundBatch(
            OutboundBatchToken token,
            String route,
            String detail,
            byte[] encodedBytes,
            int packetCount
    ) {
        if (token == null || !isSessionActive(token.channelId(), token.sessionId())) {
            return;
        }
        emit(new TraceEvent(
                token.timestampMillis(),
                token.sequence(),
                "encode",
                token.direction(),
                token.packetClass(),
                token.protocol(),
                safe(route),
                safe(detail),
                token.endpoint(),
                token.before(),
                fingerprint(encodedBytes),
                Math.max(packetCount, 1)
        ));
    }

    public static void recordInboundDirect(
            ChannelHandlerContext context,
            PacketFlow packetFlow,
            ChannelCapturedFrame capturedFrame,
            List<Object> decodedPackets,
            int outputSizeBeforeDecode
    ) {
        if (!isConfiguredAndEnabled(context) || capturedFrame == null || decodedPackets == null) {
            return;
        }
        byte[] encodedBytes = null;
        for (int index = Math.max(outputSizeBeforeDecode, 0); index < decodedPackets.size(); index++) {
            Object decodedPacket = decodedPackets.get(index);
            if (!(decodedPacket instanceof Packet<?> packet)) {
                continue;
            }
            TraceSession session = matchingSession(context, packet);
            if (session == null || !session.acquireRatePermit()) {
                continue;
            }
            if (encodedBytes == null) {
                encodedBytes = capturedFrame.copyEncodedBytes();
            }
            emit(createEvent(
                    context,
                    "decode",
                    packetFlow,
                    packet.getClass().getName(),
                    capturedFrame.protocolName(),
                    "direct",
                    "vanilla_decode",
                    encodedBytes,
                    encodedBytes,
                    1
            ));
        }
    }

    public static void recordInboundTransport(
            ChannelHandlerContext context,
            String protocolName,
            PacketFlow packetFlow,
            Packet<?> packet,
            byte[] transportFrameBytes,
            byte[] restoredPacketBytes,
            ChannelTransportPacketCodec.FrameKind frameKind,
            int packetCount
    ) {
        TraceSession session = matchingSession(context, packet);
        if (session == null || !session.acquireRatePermit()) {
            return;
        }
        emit(createEvent(
                context,
                "decode",
                packetFlow,
                packet.getClass().getName(),
                protocolName,
                frameKind == ChannelTransportPacketCodec.FrameKind.BATCH ? "batch" : "transparent",
                frameKind == null ? "UNKNOWN" : frameKind.name(),
                transportFrameBytes,
                restoredPacketBytes,
                Math.max(packetCount, 1)
        ));
    }

    private static TraceSession matchingSession(ChannelHandlerContext context, Packet<?> packet) {
        TraceSession session = session(context);
        if (session == null || packet == null || !session.packetClasses.contains(packet.getClass().getName())) {
            return null;
        }
        return session;
    }

    private static TraceSession session(ChannelHandlerContext context) {
        if (context == null || context.channel() == null) {
            return null;
        }
        String channelId = ChannelIdentity.longText(context.channel());
        TraceSession session = SESSIONS.get(channelId);
        if (session == null) {
            return null;
        }
        if (System.currentTimeMillis() < session.expiresAtMillis) {
            return session;
        }
        SESSIONS.remove(channelId, session);
        return null;
    }

    private static boolean isSessionActive(String channelId, long sessionId) {
        TraceSession session = SESSIONS.get(channelId);
        if (session == null || session.id != sessionId) {
            return false;
        }
        if (System.currentTimeMillis() < session.expiresAtMillis) {
            return true;
        }
        SESSIONS.remove(channelId, session);
        return false;
    }

    private static TraceEvent createEvent(
            ChannelHandlerContext context,
            String stage,
            PacketFlow packetFlow,
            String packetClass,
            String protocol,
            String route,
            String detail,
            byte[] beforeBytes,
            byte[] afterBytes,
            int packetCount
    ) {
        return new TraceEvent(
                System.currentTimeMillis(),
                nextSequence(context == null ? null : context.channel(), packetFlow),
                stage,
                flowName(packetFlow),
                packetClass,
                safe(protocol),
                route,
                safe(detail),
                endpoint(context),
                fingerprint(beforeBytes),
                fingerprint(afterBytes),
                packetCount
        );
    }

    private static void emit(TraceEvent event) {
        if (event == null) {
            return;
        }
        Endpoint endpoint = event.endpoint();
        Fingerprint before = event.before();
        Fingerprint after = event.after();
        DiagnosticLog.info(
                DiagnosticToolRegistry.Tool.PACKET_CLASS_TRACE,
                "event=packet_class_trace timeMs={} sequence={} stage={} direction={} packetClass={} protocol={} connection={} playerName={} playerUuid={} route={} detail={} packetCount={} beforeBytes={} beforeSha256={} afterBytes={} afterSha256={}",
                event.timestampMillis(),
                event.sequence(),
                event.stage(),
                event.direction(),
                event.packetClass(),
                event.protocol(),
                endpoint.connection(),
                endpoint.playerName(),
                endpoint.playerUuid(),
                event.route(),
                event.detail(),
                event.packetCount(),
                before.length(),
                before.sha256(),
                after.length(),
                after.sha256()
        );
    }

    private static Endpoint endpoint(ChannelHandlerContext context) {
        Channel channel = context == null ? null : context.channel();
        ChannelBandwidthStats stats = ServerBandwidthStatsRegistry.getOrCreate(channel);
        ChannelBandwidthStats.Snapshot snapshot = stats == null ? null : stats.snapshot();
        UUID playerId = snapshot == null ? null : snapshot.playerId();
        return new Endpoint(
                ChannelIdentity.shortText(channel),
                snapshot == null || snapshot.playerName() == null ? "<unbound>" : snapshot.playerName(),
                playerId == null ? "<unbound>" : playerId.toString()
        );
    }

    private static long nextSequence(Channel channel, PacketFlow packetFlow) {
        if (channel == null) {
            return 0L;
        }
        SequenceState state = channel.attr(SEQUENCE_STATE_KEY).get();
        if (state == null) {
            SequenceState created = new SequenceState();
            SequenceState raced = channel.attr(SEQUENCE_STATE_KEY).setIfAbsent(created);
            state = raced == null ? created : raced;
        }
        return packetFlow == PacketFlow.SERVERBOUND
                ? state.serverbound.incrementAndGet()
                : state.clientbound.incrementAndGet();
    }

    private static Fingerprint fingerprint(byte[] bytes) {
        byte[] safeBytes = bytes == null ? new byte[0] : bytes;
        MessageDigest digest = SHA_256.get();
        digest.reset();
        return new Fingerprint(safeBytes.length, hex(digest.digest(safeBytes)));
    }

    static String sha256ForTesting(byte[] bytes) {
        return fingerprint(bytes).sha256();
    }

    private static MessageDigest newSha256() {
        try {
            return MessageDigest.getInstance("SHA-256");
        } catch (NoSuchAlgorithmException exception) {
            throw new ExceptionInInitializerError(exception);
        }
    }

    private static String hex(byte[] bytes) {
        StringBuilder builder = new StringBuilder(bytes.length * 2);
        for (byte value : bytes) {
            builder.append(Character.forDigit((value >>> 4) & 0x0F, 16));
            builder.append(Character.forDigit(value & 0x0F, 16));
        }
        return builder.toString();
    }

    private static String flowName(PacketFlow packetFlow) {
        return packetFlow == null ? "unknown" : packetFlow.name().toLowerCase(Locale.ROOT);
    }

    private static String safe(String value) {
        if (value == null || value.isBlank()) {
            return "<unknown>";
        }
        return value.replaceAll("[\\r\\n\\t]", "_");
    }

    public record OutboundBatchToken(
            String channelId,
            long sessionId,
            long timestampMillis,
            long sequence,
            String direction,
            String packetClass,
            String protocol,
            Endpoint endpoint,
            Fingerprint before
    ) { }

    private record TraceEvent(
            long timestampMillis,
            long sequence,
            String stage,
            String direction,
            String packetClass,
            String protocol,
            String route,
            String detail,
            Endpoint endpoint,
            Fingerprint before,
            Fingerprint after,
            int packetCount
    ) { }

    private record Endpoint(String connection, String playerName, String playerUuid) { }

    private record Fingerprint(int length, String sha256) { }

    private static final class SequenceState {
        private final AtomicLong clientbound = new AtomicLong();
        private final AtomicLong serverbound = new AtomicLong();
    }

    private static final class TraceSession {
        private final long id;
        private final Set<String> packetClasses;
        private final long expiresAtMillis;
        private final int maxEventsPerMinute;
        private final AtomicLong rateMinute = new AtomicLong(Long.MIN_VALUE);
        private final AtomicInteger rateEvents = new AtomicInteger();
        private final AtomicLong rateDropNoticeMinute = new AtomicLong(Long.MIN_VALUE);

        private TraceSession(long id, Set<String> packetClasses, long expiresAtMillis, int maxEventsPerMinute) {
            this.id = id;
            this.packetClasses = packetClasses;
            this.expiresAtMillis = expiresAtMillis;
            this.maxEventsPerMinute = maxEventsPerMinute;
        }

        private boolean acquireRatePermit() {
            long minute = System.currentTimeMillis() / 60_000L;
            long observedMinute = this.rateMinute.get();
            if (observedMinute != minute && this.rateMinute.compareAndSet(observedMinute, minute)) {
                this.rateEvents.set(0);
            }
            if (this.rateEvents.incrementAndGet() <= this.maxEventsPerMinute) {
                return true;
            }
            if (this.rateDropNoticeMinute.getAndSet(minute) != minute) {
                DiagnosticLog.warn(
                        DiagnosticToolRegistry.Tool.PACKET_CLASS_TRACE,
                        "event=rate_limited maxEventsPerMinute={}",
                        this.maxEventsPerMinute
                );
            }
            return false;
        }
    }
}
