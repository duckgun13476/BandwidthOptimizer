package com.PinkCats.bandwidthoptimizer.channel.packet;

import com.PinkCats.bandwidthoptimizer.Bandwidthoptimizer;
import com.PinkCats.bandwidthoptimizer.Config;
import io.netty.buffer.ByteBuf;
import io.netty.channel.Channel;
import io.netty.channel.ChannelHandlerContext;
import io.netty.util.AttributeKey;
import net.minecraft.network.protocol.Packet;
import net.minecraft.network.protocol.PacketFlow;

import java.nio.ByteBuffer;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Arrays;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.LongAdder;
import java.util.concurrent.atomic.AtomicReference;

/**
 * Drops command trees with the same encoded length and SHA-256 fingerprint after the first
 * successful outbound encode on a connection.
 *
 * <p>The fingerprint is calculated from the final, player-specific packet bytes. This intentionally
 * avoids comparing the server dispatcher or permission metadata before Minecraft has filtered and
 * encoded it for the receiving player.</p>
 */
public final class ClientboundCommandTreeDeduplicator {

    private static final String COMMANDS_PACKET_CLASS_NAME =
            "net.minecraft.network.protocol.game.ClientboundCommandsPacket";
    private static final String LOGIN_PACKET_CLASS_NAME =
            "net.minecraft.network.protocol.game.ClientboundLoginPacket";
    private static final String START_CONFIGURATION_PACKET_CLASS_NAME =
            "net.minecraft.network.protocol.configuration.ClientboundStartConfigurationPacket";
    private static final String COMMON_START_CONFIGURATION_PACKET_CLASS_NAME =
            "net.minecraft.network.protocol.common.ClientboundStartConfigurationPacket";

    private static final AttributeKey<State> STATE_KEY =
            AttributeKey.valueOf("bandwidthoptimizer:clientbound_command_tree_deduplicator");
    private static final ThreadLocal<MessageDigest> SHA_256 = ThreadLocal.withInitial(
            ClientboundCommandTreeDeduplicator::newSha256
    );
    private static final AtomicBoolean HASH_FAILURE_REPORTED = new AtomicBoolean();
    private static final LongAdder HASHED_TREES = new LongAdder();
    private static final LongAdder HASHED_BYTES = new LongAdder();
    private static final LongAdder HASH_NANOS = new LongAdder();
    private static final LongAdder SENT_TREES = new LongAdder();
    private static final LongAdder DEDUPLICATED_TREES = new LongAdder();
    private static final LongAdder DEDUPLICATED_BYTES = new LongAdder();
    private static final LongAdder RESET_EPOCHS = new LongAdder();
    private static final LongAdder HASH_FAILURES = new LongAdder();

    private ClientboundCommandTreeDeduplicator() {}

    /**
     * Observes an encoded packet and removes it from {@code out} only when its final bytes have the
     * same length and SHA-256 fingerprint as the most recently sent command tree for the same
     * channel epoch.
     */
    public static boolean tryDropDuplicate(
            ChannelHandlerContext context,
            Packet<?> packet,
            PacketFlow packetFlow,
            ByteBuf out,
            int startIndexInclusive
    ) {
        if (context == null || packet == null || out == null) {
            return false;
        }

        return tryDropDuplicate(
                context.channel(),
                packet.getClass().getName(),
                packetFlow,
                out,
                startIndexInclusive
        );
    }

    static boolean tryDropDuplicate(
            Channel channel,
            String packetClassName,
            PacketFlow packetFlow,
            ByteBuf out,
            int startIndexInclusive
    ) {
        if (channel == null || packetClassName == null || out == null) {
            return false;
        }

        if (isConnectionEpochBoundary(packetClassName)) {
            reset(channel);
            return false;
        }
        if (packetFlow != PacketFlow.CLIENTBOUND || !COMMANDS_PACKET_CLASS_NAME.equals(packetClassName)) {
            return false;
        }
        if (!isEnabled()) {
            resetWithoutTelemetry(channel);
            return false;
        }

        int encodedLength = out.writerIndex() - startIndexInclusive;
        if (encodedLength <= 0) {
            return false;
        }

        long hashStartNanos = System.nanoTime();
        byte[] digest = fingerprint(out, startIndexInclusive, encodedLength);
        HASH_NANOS.add(Math.max(System.nanoTime() - hashStartNanos, 0L));
        if (digest == null) {
            HASH_FAILURES.increment();
            return false;
        }
        HASHED_TREES.increment();
        HASHED_BYTES.add(encodedLength);

        Decision decision = state(channel).evaluate(new Fingerprint(encodedLength, digest));
        if (decision == Decision.DUPLICATE) {
            DEDUPLICATED_TREES.increment();
            DEDUPLICATED_BYTES.add(encodedLength);
            out.writerIndex(startIndexInclusive);
            return true;
        }

        SENT_TREES.increment();
        return false;
    }

    public static TelemetrySnapshot telemetrySnapshot() {
        return new TelemetrySnapshot(
                HASHED_TREES.sum(),
                HASHED_BYTES.sum(),
                HASH_NANOS.sum(),
                SENT_TREES.sum(),
                DEDUPLICATED_TREES.sum(),
                DEDUPLICATED_BYTES.sum(),
                RESET_EPOCHS.sum(),
                HASH_FAILURES.sum()
        );
    }

    public static void reset(Channel channel) {
        if (channel == null) {
            return;
        }
        State existingState = channel.attr(STATE_KEY).get();
        if (existingState != null && existingState.reset()) {
            RESET_EPOCHS.increment();
        }
    }

    static boolean isConnectionEpochBoundary(String packetClassName) {
        return LOGIN_PACKET_CLASS_NAME.equals(packetClassName)
                || START_CONFIGURATION_PACKET_CLASS_NAME.equals(packetClassName)
                || COMMON_START_CONFIGURATION_PACKET_CLASS_NAME.equals(packetClassName);
    }

    static void resetTelemetryForTests() {
        HASHED_TREES.reset();
        HASHED_BYTES.reset();
        HASH_NANOS.reset();
        SENT_TREES.reset();
        DEDUPLICATED_TREES.reset();
        DEDUPLICATED_BYTES.reset();
        RESET_EPOCHS.reset();
        HASH_FAILURES.reset();
        HASH_FAILURE_REPORTED.set(false);
    }

    private static boolean isEnabled() {
        return Boolean.parseBoolean(System.getProperty(
                Config.RuntimeProperty.Transport.COMMAND_TREE_DEDUP_ENABLED,
                Boolean.toString(Config.RuntimeProperty.Transport.DEFAULT_COMMAND_TREE_DEDUP_ENABLED)
        ));
    }

    private static State state(Channel channel) {
        State existingState = channel.attr(STATE_KEY).get();
        if (existingState != null) {
            return existingState;
        }
        State newState = new State();
        State racedState = channel.attr(STATE_KEY).setIfAbsent(newState);
        return racedState == null ? newState : racedState;
    }

    private static void resetWithoutTelemetry(Channel channel) {
        if (channel == null) {
            return;
        }
        State existingState = channel.attr(STATE_KEY).get();
        if (existingState != null) {
            existingState.reset();
        }
    }

    private static byte[] fingerprint(ByteBuf buffer, int startIndexInclusive, int length) {
        try {
            MessageDigest digest = SHA_256.get();
            digest.reset();
            int nioBufferCount = buffer.nioBufferCount();
            if (nioBufferCount == 1) {
                digest.update(buffer.nioBuffer(startIndexInclusive, length));
            } else if (nioBufferCount > 1) {
                for (ByteBuffer byteBuffer : buffer.nioBuffers(startIndexInclusive, length)) {
                    digest.update(byteBuffer);
                }
            } else {
                byte[] encodedBytes = new byte[length];
                buffer.getBytes(startIndexInclusive, encodedBytes);
                digest.update(encodedBytes);
            }
            return digest.digest();
        } catch (RuntimeException exception) {
            if (HASH_FAILURE_REPORTED.compareAndSet(false, true)) {
                Bandwidthoptimizer.LOGGER.warn(
                        "[Transport][CommandTreeDedup] SHA-256 fingerprint failed; command trees will pass through",
                        exception
                );
            }
            return null;
        }
    }

    private static MessageDigest newSha256() {
        try {
            return MessageDigest.getInstance("SHA-256");
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("SHA-256 is unavailable", exception);
        }
    }

    private enum Decision {
        SEND,
        DUPLICATE
    }

    private record Fingerprint(int encodedLength, byte[] sha256) {
        private Fingerprint {
            sha256 = Arrays.copyOf(sha256, sha256.length);
        }

        private boolean matches(Fingerprint other) {
            return other != null
                    && this.encodedLength == other.encodedLength
                    && MessageDigest.isEqual(this.sha256, other.sha256);
        }
    }

    private static final class State {
        private final AtomicReference<Fingerprint> lastSentFingerprint = new AtomicReference<>();

        private Decision evaluate(Fingerprint fingerprint) {
            while (true) {
                Fingerprint existingFingerprint = this.lastSentFingerprint.get();
                if (existingFingerprint != null && existingFingerprint.matches(fingerprint)) {
                    return Decision.DUPLICATE;
                }
                if (this.lastSentFingerprint.compareAndSet(existingFingerprint, fingerprint)) {
                    return Decision.SEND;
                }
            }
        }

        private boolean reset() {
            return this.lastSentFingerprint.getAndSet(null) != null;
        }
    }

    public record TelemetrySnapshot(
            long hashedTrees,
            long hashedBytes,
            long hashNanos,
            long sentTrees,
            long deduplicatedTrees,
            long deduplicatedBytes,
            long resetEpochs,
            long hashFailures
    ) {}
}
