package com.PinkCats.bandwidthoptimizer.connection;

import com.PinkCats.bandwidthoptimizer.channel.ChannelIdentity;
import com.PinkCats.bandwidthoptimizer.debug.DiagnosticLog;
import com.PinkCats.bandwidthoptimizer.debug.DiagnosticToolRegistry;
import io.netty.channel.Channel;
import io.netty.channel.ChannelHandlerContext;
import io.netty.util.AttributeKey;

import java.util.List;
import java.util.Locale;

public final class ConnectionDisconnectClassifier {

    private static final AttributeKey<State> STATE_KEY =
            AttributeKey.valueOf("bandwidthoptimizer:disconnect_classifier");

    private ConnectionDisconnectClassifier() {}

    public static void observeOutboundPacket(Channel channel, Object packet) {
        observePacketClass(channel, packetClassName(packet), "outbound-packet");
    }

    public static void observeInboundDecodedPackets(
            ChannelHandlerContext context,
            List<Object> decodedPackets,
            int outputSizeBeforeDecode
    ) {
        if (context == null || decodedPackets == null || decodedPackets.size() <= outputSizeBeforeDecode) {
            return;
        }
        int startIndex = Math.max(outputSizeBeforeDecode, 0);
        for (int index = startIndex; index < decodedPackets.size(); index++) {
            observeInboundPacketClass(context.channel(), packetClassName(decodedPackets.get(index)));
        }
    }

    static void observeInboundPacketClass(Channel channel, String packetClass) {
        if (channel == null) {
            return;
        }
        observePacketClass(channel, packetClass, "inbound-packet");
        State existing = channel.attr(STATE_KEY).get();
        if (existing == null && isConnectionSessionPacket(packetClass)) {
            existing = state(channel);
        }
        if (existing != null && isPlayPacket(packetClass)) {
            existing.markPlayObserved();
        }
    }

    public static void markLocalDisconnect(Channel channel) {
        if (channel != null) {
            state(channel).markLocalDisconnect();
        }
    }

    public static void observeException(Channel channel, Throwable throwable) {
        if (channel == null || throwable == null) {
            return;
        }
        ClassifiedThrowable classified = classifyThrowableChain(throwable);
        Category category = classified.category();
        state(channel).record(
                category,
                recoveryPolicy(category),
                "exceptionCaught",
                "",
                classified.throwableClass(),
                "",
                priority(category)
        );
    }

    public static void markBoInitiatedClose(Channel channel, String stage, Throwable throwable) {
        if (channel == null) {
            return;
        }
        state(channel).record(
                Category.BO_TRANSPORT_FAILURE,
                RecoveryPolicy.RECONNECT_CANDIDATE,
                "bo-close",
                "",
                throwable == null ? "" : throwable.getClass().getName(),
                safe(stage),
                priority(Category.BO_TRANSPORT_FAILURE)
        );
    }

    public static Decision onChannelInactive(Channel channel) {
        State existing = channel == null ? null : channel.attr(STATE_KEY).get();
        Decision decision = existing == null ? unknownDecision() : existing.decision();
        if (existing != null && DiagnosticToolRegistry.isEnabled(DiagnosticToolRegistry.Tool.CONNECTION_CLOSE)) {
            DiagnosticLog.info(
                    DiagnosticToolRegistry.Tool.CONNECTION_CLOSE,
                    "channel={} category={} recovery={} trigger={} packetClass={} throwable={} boStage={} evidenceAgeMs={}",
                    channel == null ? "<no-channel>" : ChannelIdentity.shortText(channel),
                    decision.category(),
                    decision.recoveryPolicy(),
                    decision.trigger(),
                    emptyAsDash(decision.packetClass()),
                    emptyAsDash(decision.throwableClass()),
                    emptyAsDash(decision.boStage()),
                    decision.evidenceAgeMillis()
            );
        }
        return decision;
    }

    public static Decision snapshot(Channel channel) {
        State existing = channel == null ? null : channel.attr(STATE_KEY).get();
        return existing == null ? unknownDecision() : existing.decision();
    }

    static void observePacketClass(Channel channel, String packetClass, String trigger) {
        if (channel == null || !isExplicitDisconnectPacket(packetClass)) {
            return;
        }
        state(channel).record(
                Category.EXPLICIT_DISCONNECT,
                RecoveryPolicy.DO_NOT_AUTO_RECONNECT,
                safe(trigger),
                packetClass,
                "",
                "",
                priority(Category.EXPLICIT_DISCONNECT)
        );
    }

    static Category classifyThrowable(Throwable throwable) {
        return classifyThrowableChain(throwable).category();
    }

    private static ClassifiedThrowable classifyThrowableChain(Throwable throwable) {
        if (throwable == null) {
            return new ClassifiedThrowable(Category.UNKNOWN, "");
        }
        Category selected = Category.UNKNOWN;
        String selectedClass = throwable.getClass().getName();
        Throwable current = throwable;
        for (int depth = 0; depth < 12 && current != null; depth++) {
            Category candidate = classifySingleThrowable(current);
            if (priority(candidate) > priority(selected)) {
                selected = candidate;
                selectedClass = current.getClass().getName();
            }
            Throwable cause = current.getCause();
            if (cause == null || cause == current) {
                break;
            }
            current = cause;
        }
        return new ClassifiedThrowable(selected, selectedClass);
    }

    private static Category classifySingleThrowable(Throwable throwable) {
        String className = throwable.getClass().getName().toLowerCase(Locale.ROOT);
        String message = safe(throwable.getMessage()).toLowerCase(Locale.ROOT);
        if (className.contains("ssl") || className.contains("certificate") || message.contains("authentication failed")) {
            return Category.SECURITY_FAILURE;
        }
        if (className.contains("decoderexception")
                || className.contains("encoderexception")
                || className.contains("corruptedframe")
                || message.contains("bad packet")
                || message.contains("invalid packet")) {
            return Category.PROTOCOL_FAILURE;
        }
        if (className.contains("connecttimeoutexception") || message.contains("connection timed out")) {
            return Category.CONNECT_TIMEOUT;
        }
        if (className.contains("readtimeoutexception")
                || className.contains("sockettimeoutexception")
                || message.contains("read timed out")) {
            return Category.READ_TIMEOUT;
        }
        if (message.contains("connection reset") || className.contains("nativeioexception")) {
            return Category.CONNECTION_RESET;
        }
        if (className.contains("eofexception")
                || className.contains("closedchannelexception")
                || message.contains("end of stream")
                || message.contains("connection closed")) {
            return Category.REMOTE_EOF;
        }
        if (className.contains("connectexception") || message.contains("connection refused")) {
            return Category.CONNECT_FAILURE;
        }
        return Category.UNKNOWN;
    }

    private static State state(Channel channel) {
        State existing = channel.attr(STATE_KEY).get();
        if (existing != null) {
            return existing;
        }
        State created = new State();
        State raced = channel.attr(STATE_KEY).setIfAbsent(created);
        return raced == null ? created : raced;
    }

    private static boolean isExplicitDisconnectPacket(String packetClass) {
        return "net.minecraft.network.protocol.login.ClientboundLoginDisconnectPacket".equals(packetClass)
                || "net.minecraft.network.protocol.game.ClientboundDisconnectPacket".equals(packetClass)
                || "net.minecraft.network.protocol.common.ClientboundDisconnectPacket".equals(packetClass);
    }

    private static boolean isConnectionSessionPacket(String packetClass) {
        return packetClass.startsWith("net.minecraft.network.protocol.login.")
                || packetClass.startsWith("net.minecraft.network.protocol.configuration.")
                || packetClass.startsWith("net.minecraft.network.protocol.common.")
                || packetClass.startsWith("net.minecraft.network.protocol.game.");
    }

    private static boolean isPlayPacket(String packetClass) {
        return packetClass.startsWith("net.minecraft.network.protocol.game.");
    }

    private static RecoveryPolicy recoveryPolicy(Category category) {
        return switch (category) {
            case READ_TIMEOUT, CONNECT_TIMEOUT, CONNECTION_RESET, REMOTE_EOF, CONNECT_FAILURE,
                    BO_TRANSPORT_FAILURE, UNEXPECTED_CLEAN_CLOSE ->
                    RecoveryPolicy.RECONNECT_CANDIDATE;
            case EXPLICIT_DISCONNECT, LOCAL_DISCONNECT, PROTOCOL_FAILURE, SECURITY_FAILURE ->
                    RecoveryPolicy.DO_NOT_AUTO_RECONNECT;
            case UNKNOWN -> RecoveryPolicy.MANUAL_REVIEW;
        };
    }

    private static int priority(Category category) {
        return switch (category) {
            case BO_TRANSPORT_FAILURE -> 100;
            case EXPLICIT_DISCONNECT -> 90;
            case LOCAL_DISCONNECT -> 85;
            case SECURITY_FAILURE -> 80;
            case PROTOCOL_FAILURE -> 70;
            case CONNECTION_RESET -> 60;
            case READ_TIMEOUT, CONNECT_TIMEOUT -> 50;
            case REMOTE_EOF, CONNECT_FAILURE -> 40;
            case UNEXPECTED_CLEAN_CLOSE, UNKNOWN -> 0;
        };
    }

    private static String packetClassName(Object packet) {
        return packet == null ? "" : packet.getClass().getName();
    }

    private static Decision unknownDecision() {
        return new Decision(
                Category.UNKNOWN,
                RecoveryPolicy.MANUAL_REVIEW,
                "channelInactive",
                "",
                "",
                "",
                0L
        );
    }

    private static String safe(String value) {
        return value == null ? "" : value;
    }

    private static String emptyAsDash(String value) {
        return value == null || value.isEmpty() ? "-" : value;
    }

    public enum Category {
        BO_TRANSPORT_FAILURE,
        EXPLICIT_DISCONNECT,
        LOCAL_DISCONNECT,
        READ_TIMEOUT,
        CONNECT_TIMEOUT,
        CONNECTION_RESET,
        REMOTE_EOF,
        CONNECT_FAILURE,
        PROTOCOL_FAILURE,
        SECURITY_FAILURE,
        UNEXPECTED_CLEAN_CLOSE,
        UNKNOWN
    }

    public enum RecoveryPolicy {
        RECONNECT_CANDIDATE,
        DO_NOT_AUTO_RECONNECT,
        MANUAL_REVIEW
    }

    public record Decision(
            Category category,
            RecoveryPolicy recoveryPolicy,
            String trigger,
            String packetClass,
            String throwableClass,
            String boStage,
            long evidenceAgeMillis
    ) { }

    private record ClassifiedThrowable(Category category, String throwableClass) { }

    private static final class State {
        private Category category = Category.UNKNOWN;
        private RecoveryPolicy recoveryPolicy = RecoveryPolicy.MANUAL_REVIEW;
        private String trigger = "channelInactive";
        private String packetClass = "";
        private String throwableClass = "";
        private String boStage = "";
        private int priority;
        private long evidenceAtMillis;
        private boolean playObserved;
        private boolean localDisconnect;
        private long localDisconnectAtMillis;

        synchronized void markPlayObserved() {
            this.playObserved = true;
        }

        synchronized void markLocalDisconnect() {
            this.localDisconnect = true;
            this.localDisconnectAtMillis = System.currentTimeMillis();
        }

        synchronized void record(
                Category nextCategory,
                RecoveryPolicy nextRecoveryPolicy,
                String nextTrigger,
                String nextPacketClass,
                String nextThrowableClass,
                String nextBoStage,
                int nextPriority
        ) {
            if (nextPriority < this.priority) {
                return;
            }
            this.category = nextCategory;
            this.recoveryPolicy = nextRecoveryPolicy;
            this.trigger = safe(nextTrigger);
            this.packetClass = safe(nextPacketClass);
            this.throwableClass = safe(nextThrowableClass);
            this.boStage = safe(nextBoStage);
            this.priority = nextPriority;
            this.evidenceAtMillis = System.currentTimeMillis();
        }

        synchronized Decision decision() {
            if (this.localDisconnect
                    && this.category != Category.BO_TRANSPORT_FAILURE
                    && this.category != Category.EXPLICIT_DISCONNECT
                    && this.category != Category.PROTOCOL_FAILURE
                    && this.category != Category.SECURITY_FAILURE) {
                return new Decision(
                        Category.LOCAL_DISCONNECT,
                        RecoveryPolicy.DO_NOT_AUTO_RECONNECT,
                        "local-disconnect",
                        "",
                        "",
                        "",
                        ageMillis(this.localDisconnectAtMillis)
                );
            }
            if (this.category == Category.UNKNOWN && this.playObserved) {
                return new Decision(
                        Category.UNEXPECTED_CLEAN_CLOSE,
                        RecoveryPolicy.RECONNECT_CANDIDATE,
                        "channelInactive-after-play",
                        "",
                        "",
                        "",
                        0L
                );
            }
            long ageMillis = this.evidenceAtMillis <= 0L
                    ? 0L
                    : Math.max(0L, System.currentTimeMillis() - this.evidenceAtMillis);
            return new Decision(
                    this.category,
                    this.recoveryPolicy,
                    this.trigger,
                    this.packetClass,
                    this.throwableClass,
                    this.boStage,
                    ageMillis
            );
        }

        private static long ageMillis(long timestampMillis) {
            return timestampMillis <= 0L
                    ? 0L
                    : Math.max(0L, System.currentTimeMillis() - timestampMillis);
        }
    }
}
