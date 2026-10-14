package com.PinkCats.bandwidthoptimizer.channel.packet;

import io.netty.buffer.ByteBuf;
import io.netty.channel.Channel;
import io.netty.channel.ChannelHandlerContext;
import io.netty.util.AttributeKey;
import net.minecraft.network.protocol.Packet;
import net.minecraft.network.protocol.PacketFlow;

import java.util.concurrent.atomic.AtomicLong;

/** Exact native header/footer state reuse; never coalesces changing updates. */
public final class ClientboundTabListDeduplicator {
    public static final String ENABLED_PROPERTY = "bandwidthoptimizer.transport.tabListDedupEnabled";
    static final int MAX_BASELINE_BYTES = 64 * 1024;
    static final long REFRESH_NANOS = 5_000_000_000L;
    private static final String TAB_PACKET = net.minecraft.network.protocol.game.ClientboundTabListPacket.class.getName();
    private static final AttributeKey<State> STATE_KEY = AttributeKey.valueOf("bandwidthoptimizer:tab_list_dedup");
    private static final AtomicLong STARTED_AT = new AtomicLong(System.currentTimeMillis());
    private static final AtomicLong SAVED_BYTES = new AtomicLong();
    private static final AtomicLong DROPPED_PACKETS = new AtomicLong();
    private static final AtomicLong ACCEPTED_BYTES = new AtomicLong();
    private static final AtomicLong ACCEPTED_PACKETS = new AtomicLong();

    private ClientboundTabListDeduplicator() {}

    public static Candidate inspect(ChannelHandlerContext context, Packet<?> packet, PacketFlow flow,
                                    ByteBuf out, int start) {
        return context == null || packet == null ? Candidate.NONE
                : inspect(context.channel(), packet.getClass().getName(), flow, out, start, System.nanoTime());
    }

    static Candidate inspect(Channel channel, String type, PacketFlow flow, ByteBuf out, int start, long now) {
        if (channel == null || type == null || out == null || flow != PacketFlow.CLIENTBOUND) return Candidate.NONE;
        if (type.endsWith("ClientboundLoginPacket") || type.endsWith("ClientboundStartConfigurationPacket")
                || type.endsWith("ClientboundRespawnPacket")) {
            reset(channel);
            return Candidate.NONE;
        }
        if (!TAB_PACKET.equals(type)) return Candidate.NONE;
        if (!Boolean.parseBoolean(System.getProperty(ENABLED_PROPERTY, "true"))) {
            reset(channel);
            return Candidate.NONE;
        }
        int length = out.writerIndex() - start;
        if (start < 0 || length <= 0) return Candidate.NONE;
        State state = state(channel);
        if (length > MAX_BASELINE_BYTES) {
            state.clear();
            return new Candidate(state, state.generation, null, length, now, false);
        }
        if (state.bytes != null && state.bytes.length == length && now - state.committedAt < REFRESH_NANOS
                && equal(out, start, state.bytes)) {
            out.writerIndex(start);
            add(SAVED_BYTES, length);
            add(DROPPED_PACKETS, 1);
            return Candidate.DROPPED;
        }
        byte[] bytes = new byte[length];
        out.getBytes(start, bytes);
        return new Candidate(state, state.generation, bytes, length, now, false);
    }

    /** Call only after native bytes were accepted by transport or the ordered epoch queue. */
    public static void commit(Candidate candidate) {
        if (candidate == null || candidate.state == null || candidate.dropped
                || candidate.generation != candidate.state.generation) return;
        candidate.state.bytes = candidate.bytes;
        candidate.state.committedAt = candidate.now;
        // Invalidate older candidates, including repeat commit calls.
        candidate.state.generation++;
        add(ACCEPTED_BYTES, candidate.length);
        add(ACCEPTED_PACKETS, 1);
    }

    public static void reset(Channel channel) {
        if (channel != null) {
            State state = channel.attr(STATE_KEY).get();
            if (state != null) state.clear();
        }
    }

    public static Snapshot snapshot() {
        return new Snapshot(STARTED_AT.get(), SAVED_BYTES.get(), DROPPED_PACKETS.get(),
                ACCEPTED_BYTES.get(), ACCEPTED_PACKETS.get());
    }

    private static State state(Channel channel) {
        State state = channel.attr(STATE_KEY).get();
        if (state != null) return state;
        State created = new State();
        State raced = channel.attr(STATE_KEY).setIfAbsent(created);
        if (raced != null) return raced;
        channel.closeFuture().addListener(ignored -> created.clear());
        return created;
    }

    private static boolean equal(ByteBuf buffer, int start, byte[] bytes) {
        for (int i = 0; i < bytes.length; i++) if (buffer.getByte(start + i) != bytes[i]) return false;
        return true;
    }

    private static void add(AtomicLong counter, long value) {
        counter.updateAndGet(previous -> previous >= Long.MAX_VALUE - value ? Long.MAX_VALUE : previous + value);
    }

    private static final class State {
        private byte[] bytes;
        private long committedAt;
        private long generation;
        private void clear() { bytes = null; generation++; }
    }

    public static final class Candidate {
        private static final Candidate NONE = new Candidate(null, 0, null, 0, 0, false);
        private static final Candidate DROPPED = new Candidate(null, 0, null, 0, 0, true);
        private final State state;
        private final long generation;
        private final byte[] bytes;
        private final int length;
        private final long now;
        private final boolean dropped;
        private Candidate(State state, long generation, byte[] bytes, int length, long now, boolean dropped) {
            this.state = state; this.generation = generation; this.bytes = bytes;
            this.length = length; this.now = now; this.dropped = dropped;
        }
        public boolean dropped() { return dropped; }
    }

    public record Snapshot(long startedAtMillis, long savedRawBytes, long suppressedPackets,
                           long acceptedRawBytes, long acceptedPackets) {}
}
