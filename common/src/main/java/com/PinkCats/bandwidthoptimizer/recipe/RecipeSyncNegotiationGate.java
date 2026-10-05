package com.PinkCats.bandwidthoptimizer.recipe;

import io.netty.channel.Channel;
import io.netty.buffer.ByteBuf;
import io.netty.buffer.ByteBufUtil;
import io.netty.channel.ChannelHandlerContext;
import io.netty.util.AttributeKey;
import com.PinkCats.bandwidthoptimizer.channel.ChannelTransportNetworkChannel;
import com.PinkCats.bandwidthoptimizer.channel.algorithm.batch.ChannelTransportBatchManager;
import com.PinkCats.bandwidthoptimizer.integration.minecraft.CustomPayloadPacketCompat;
import net.minecraft.network.protocol.Packet;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeUnit;

public final class RecipeSyncNegotiationGate {

    private static final AttributeKey<State> STATE_KEY =
            AttributeKey.valueOf("bandwidthoptimizer:recipe_sync_gate");
    private static final long TIMEOUT_MILLIS = 2_000L;
    private static final int MAX_PACKETS = 1024;
    private static final long MAX_BYTES = 64L * 1024L * 1024L;

    private RecipeSyncNegotiationGate() {}

    public static long arm(Channel channel) {
        if (!RecipeSyncRuntimeConfig.isEnabled() || channel == null || !channel.isOpen()) {
            return 0L;
        }
        State state = state(channel);
        ArmResult result = state.arm(System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(TIMEOUT_MILLIS));
        flush(channel, result.released());
        long generation = result.generation();
        channel.eventLoop().schedule(() -> release(channel, generation), TIMEOUT_MILLIS, TimeUnit.MILLISECONDS);
        return generation;
    }

    public static long currentGeneration(Channel channel) {
        State state = channel == null ? null : channel.attr(STATE_KEY).get();
        return state == null ? 0L : state.generation();
    }

    public static boolean tryQueue(ChannelHandlerContext context, Packet<?> packet, int encodedBytes) {
        return tryQueue(context, packet, encodedBytes, null);
    }

    public static boolean tryQueue(ChannelHandlerContext context, Packet<?> packet, ByteBuf out, int start) {
        int bytes = out.writerIndex() - start;
        boolean consumed = tryQueue(context, packet, bytes, out);
        if (consumed) out.writerIndex(start);
        return consumed;
    }

    private static boolean tryQueue(ChannelHandlerContext context, Packet<?> packet, int encodedBytes, ByteBuf out) {
        if (context == null || packet == null) {
            return false;
        }
        Channel channel = context.channel();
        State state = channel.attr(STATE_KEY).get();
        if (state == null) {
            return false;
        }
        if (state.isReplaying(packet)) {
            return false;
        }
        // BO carriers must progress: they include the scope/base negotiation and earlier batches.
        if (ChannelTransportNetworkChannel.TRANSPORT_PAYLOAD_ID.toString()
                .equals(CustomPayloadPacketCompat.payloadChannel(packet))) {
            return false;
        }
        QueueResult result = state.queue(packet, Math.max(encodedBytes, 0), RecipeSyncRuntimeConfig.isEnabled(), out);
        if (result.consumed()) {
            // Commit predecessors before the suspended packet or a capacity/timeout release.
            ChannelTransportBatchManager.flushOutboundBatchNow(context);
        }
        if (!result.released().isEmpty()) {
            flush(channel, result.released());
        }
        return result.consumed();
    }

    public static void complete(Channel channel, long generation) {
        release(channel, generation);
    }

    public static byte[] replayBytes(ChannelHandlerContext context, Packet<?> packet) {
        State state = context == null ? null : context.channel().attr(STATE_KEY).get();
        return state == null ? null : state.replayBytes(packet);
    }

    public static boolean isRecipePacket(Object packet) {
        if (packet == null) {
            return false;
        }
        String name = packet.getClass().getName();
        return name.endsWith("ClientboundUpdateRecipesPacket");
    }

    private static void release(Channel channel, long generation) {
        if (channel == null) {
            return;
        }
        State state = channel.attr(STATE_KEY).get();
        List<Queued> packets = state == null ? List.of() : state.release(generation);
        flush(channel, packets);
    }

    private static void flush(Channel channel, List<Queued> packets) {
        if (packets.isEmpty()) {
            return;
        }
        Runnable flush = () -> {
            if (!channel.isOpen()) {
                return;
            }
            for (Queued queued : packets) {
                State state = channel.attr(STATE_KEY).get();
                if (state != null) {
                    state.setReplaying(queued);
                }
                try {
                    channel.write(queued.packet());
                } finally {
                    if (state != null) {
                        state.setReplaying(null);
                    }
                }
            }
            channel.flush();
        };
        if (channel.eventLoop().inEventLoop()) {
            flush.run();
        } else {
            channel.eventLoop().execute(flush);
        }
    }

    private static State state(Channel channel) {
        State current = channel.attr(STATE_KEY).get();
        if (current != null) {
            return current;
        }
        State created = new State();
        State raced = channel.attr(STATE_KEY).setIfAbsent(created);
        State selected = raced == null ? created : raced;
        if (raced == null) {
            channel.closeFuture().addListener(ignored -> selected.close());
        }
        return selected;
    }

    private static final class State {
        private final ArrayDeque<Queued> packets = new ArrayDeque<>();
        private long generation;
        private long deadlineNanos;
        private long bytes;
        private boolean pending;
        private boolean started;
        private Queued replaying;

        synchronized boolean isReplaying(Packet<?> packet) {
            return this.replaying != null && this.replaying.packet() == packet;
        }

        synchronized byte[] replayBytes(Packet<?> packet) {
            return isReplaying(packet) ? this.replaying.encoded() : null;
        }

        synchronized void setReplaying(Queued queued) {
            this.replaying = queued;
        }

        synchronized ArmResult arm(long deadlineNanos) {
            ArrayList<Queued> released = drain();
            this.generation = this.generation == Long.MAX_VALUE ? 1L : this.generation + 1L;
            this.deadlineNanos = deadlineNanos;
            this.pending = true;
            this.started = false;
            return new ArmResult(this.generation, released);
        }

        synchronized long generation() {
            return this.generation;
        }

        synchronized QueueResult queue(Packet<?> packet, int encodedBytes, boolean enabled, ByteBuf out) {
            if (!this.pending) {
                return QueueResult.notConsumed();
            }
            if (!this.started && !isRecipePacket(packet)) {
                return QueueResult.notConsumed();
            }
            this.started = true;
            byte[] snapshot = out == null ? null : ByteBufUtil.getBytes(out, out.writerIndex() - encodedBytes, encodedBytes, true);
            if (!enabled || System.nanoTime() >= this.deadlineNanos
                    || this.packets.size() >= MAX_PACKETS
                    || this.bytes > MAX_BYTES - encodedBytes) {
                ArrayList<Queued> released = drain();
                released.add(new Queued(packet, snapshot));
                this.pending = false;
                return new QueueResult(true, released);
            }
            this.packets.addLast(new Queued(packet, snapshot));
            this.bytes += encodedBytes;
            return new QueueResult(true, List.of());
        }

        synchronized List<Queued> release(long expectedGeneration) {
            if (!this.pending || expectedGeneration != this.generation) {
                return List.of();
            }
            this.pending = false;
            return drain();
        }

        synchronized void close() {
            this.pending = false;
            this.packets.clear();
            this.bytes = 0L;
            this.started = false;
        }

        private ArrayList<Queued> drain() {
            ArrayList<Queued> released = new ArrayList<>(this.packets.size());
            while (!this.packets.isEmpty()) {
                released.add(this.packets.removeFirst());
            }
            this.bytes = 0L;
            return released;
        }
    }

    private record Queued(Packet<?> packet, byte[] encoded) {}
    private record ArmResult(long generation, List<Queued> released) {}
    private record QueueResult(boolean consumed, List<Queued> released) {
        static QueueResult notConsumed() {
            return new QueueResult(false, List.of());
        }
    }
}
