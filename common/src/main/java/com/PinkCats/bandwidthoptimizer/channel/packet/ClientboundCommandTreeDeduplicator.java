package com.PinkCats.bandwidthoptimizer.channel.packet;

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

/** Deduplicates byte-identical clientbound command trees after vanilla encoding. */
public final class ClientboundCommandTreeDeduplicator {

    private static final String COMMANDS_PACKET = "net.minecraft.network.protocol.game.ClientboundCommandsPacket";
    private static final AttributeKey<State> STATE_KEY =
            AttributeKey.valueOf("bandwidthoptimizer:clientbound_command_tree_deduplicator");
    private static final ThreadLocal<MessageDigest> SHA_256 =
            ThreadLocal.withInitial(ClientboundCommandTreeDeduplicator::newSha256);

    private ClientboundCommandTreeDeduplicator() {
    }

    public static Candidate inspect(
            ChannelHandlerContext context,
            Packet<?> packet,
            PacketFlow flow,
            ByteBuf out,
            int startIndexInclusive
    ) {
        return context == null || packet == null
                ? Candidate.NONE
                : inspect(context.channel(), packet.getClass().getName(), flow, out, startIndexInclusive);
    }

    static Candidate inspect(
            Channel channel,
            String packetClassName,
            PacketFlow flow,
            ByteBuf out,
            int startIndexInclusive
    ) {
        if (channel == null || packetClassName == null || out == null) {
            return Candidate.NONE;
        }
        if (isConnectionEpochBoundary(packetClassName)) {
            reset(channel);
            return Candidate.NONE;
        }
        if (flow != PacketFlow.CLIENTBOUND || !COMMANDS_PACKET.equals(packetClassName)) {
            return Candidate.NONE;
        }
        if (!isEnabled()) {
            clearWhenDisabled(channel);
            return Candidate.NONE;
        }

        int encodedLength = out.writerIndex() - startIndexInclusive;
        if (encodedLength <= 0) {
            return Candidate.NONE;
        }
        Fingerprint fingerprint = fingerprint(out, startIndexInclusive, encodedLength);
        if (fingerprint == null) {
            return Candidate.NONE;
        }
        if (state(channel).matches(fingerprint)) {
            out.writerIndex(startIndexInclusive);
            return Candidate.DROPPED;
        }
        return new Candidate(channel, fingerprint, false);
    }

    /** Records only a packet that was accepted by the encoder or epoch queue. */
    public static void commit(Candidate candidate) {
        if (candidate == null || candidate == Candidate.NONE || candidate.dropped) {
            return;
        }
        state(candidate.channel).commit(candidate.fingerprint);
    }

    public static void reset(Channel channel) {
        if (channel != null) {
            state(channel).reset();
        }
    }

    static boolean isConnectionEpochBoundary(String packetClassName) {
        return packetClassName.endsWith("ClientboundLoginPacket")
                || packetClassName.endsWith("ClientboundStartConfigurationPacket");
    }

    private static boolean isEnabled() {
        return Boolean.parseBoolean(System.getProperty(Config.RuntimeProperty.Transport.COMMAND_TREE_DEDUP_ENABLED,
                Boolean.toString(Config.RuntimeProperty.Transport.DEFAULT_COMMAND_TREE_DEDUP_ENABLED)));
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

    private static void clearWhenDisabled(Channel channel) {
        State current = channel.attr(STATE_KEY).get();
        if (current != null) {
            current.reset();
        }
    }

    private static Fingerprint fingerprint(ByteBuf buffer, int index, int length) {
        try {
            MessageDigest digest = SHA_256.get();
            digest.reset();
            int nioBufferCount = buffer.nioBufferCount();
            if (nioBufferCount == 1) {
                digest.update(buffer.nioBuffer(index, length));
            } else if (nioBufferCount > 1) {
                for (ByteBuffer part : buffer.nioBuffers(index, length)) {
                    digest.update(part);
                }
            } else {
                byte[] bytes = new byte[length];
                buffer.getBytes(index, bytes);
                digest.update(bytes);
            }
            return new Fingerprint(length, digest.digest());
        } catch (RuntimeException exception) {
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

    public static final class Candidate {
        private static final Candidate NONE = new Candidate(null, null, false);
        private static final Candidate DROPPED = new Candidate(null, null, true);
        private final Channel channel;
        private final Fingerprint fingerprint;
        private final boolean dropped;

        private Candidate(Channel channel, Fingerprint fingerprint, boolean dropped) {
            this.channel = channel;
            this.fingerprint = fingerprint;
            this.dropped = dropped;
        }

        public boolean dropped() {
            return dropped;
        }
    }

    private record Fingerprint(int length, byte[] sha256) {
        private Fingerprint {
            sha256 = Arrays.copyOf(sha256, sha256.length);
        }

        private boolean matches(Fingerprint other) {
            return other != null && length == other.length && MessageDigest.isEqual(sha256, other.sha256);
        }
    }

    private static final class State {
        private volatile Fingerprint lastCommitted;

        private boolean matches(Fingerprint fingerprint) {
            return fingerprint.matches(lastCommitted);
        }

        private void commit(Fingerprint fingerprint) {
            lastCommitted = fingerprint;
        }

        private void reset() {
            lastCommitted = null;
        }
    }
}
