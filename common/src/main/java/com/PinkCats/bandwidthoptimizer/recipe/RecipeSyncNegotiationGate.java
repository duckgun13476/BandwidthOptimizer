package com.PinkCats.bandwidthoptimizer.recipe;

import io.netty.channel.Channel;
import io.netty.channel.ChannelHandlerContext;
import io.netty.util.AttributeKey;
import net.minecraft.network.protocol.Packet;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeUnit;

public final class RecipeSyncNegotiationGate {

    private static final AttributeKey<State> STATE_KEY =
            AttributeKey.valueOf("bandwidthoptimizer:recipe_sync_gate");
    private static final long TIMEOUT_MILLIS = 2_000L;
    private static final int MAX_PACKETS = 4;
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
        if (!RecipeSyncRuntimeConfig.isEnabled() || context == null || packet == null || !isRecipePacket(packet)) {
            return false;
        }
        Channel channel = context.channel();
        State state = channel.attr(STATE_KEY).get();
        if (state == null) {
            return false;
        }
        QueueResult result = state.queue(packet, Math.max(encodedBytes, 0));
        if (!result.released().isEmpty()) {
            flush(channel, result.released());
        }
        return result.consumed();
    }

    public static void complete(Channel channel, long generation) {
        release(channel, generation);
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
        List<Packet<?>> packets = state == null ? List.of() : state.release(generation);
        flush(channel, packets);
    }

    private static void flush(Channel channel, List<Packet<?>> packets) {
        if (packets.isEmpty()) {
            return;
        }
        Runnable flush = () -> {
            if (!channel.isOpen()) {
                return;
            }
            for (Packet<?> packet : packets) {
                channel.write(packet);
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

        synchronized ArmResult arm(long deadlineNanos) {
            ArrayList<Packet<?>> released = drain();
            this.generation = this.generation == Long.MAX_VALUE ? 1L : this.generation + 1L;
            this.deadlineNanos = deadlineNanos;
            this.pending = true;
            return new ArmResult(this.generation, released);
        }

        synchronized long generation() {
            return this.generation;
        }

        synchronized QueueResult queue(Packet<?> packet, int encodedBytes) {
            if (!this.pending) {
                return QueueResult.notConsumed();
            }
            if (System.nanoTime() >= this.deadlineNanos
                    || this.packets.size() >= MAX_PACKETS
                    || this.bytes > MAX_BYTES - encodedBytes) {
                ArrayList<Packet<?>> released = drain();
                released.add(packet);
                this.pending = false;
                return new QueueResult(true, released);
            }
            this.packets.addLast(new Queued(packet, encodedBytes));
            this.bytes += encodedBytes;
            return new QueueResult(true, List.of());
        }

        synchronized List<Packet<?>> release(long expectedGeneration) {
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
        }

        private ArrayList<Packet<?>> drain() {
            ArrayList<Packet<?>> released = new ArrayList<>(this.packets.size());
            while (!this.packets.isEmpty()) {
                released.add(this.packets.removeFirst().packet());
            }
            this.bytes = 0L;
            return released;
        }
    }

    private record Queued(Packet<?> packet, int bytes) {}
    private record ArmResult(long generation, List<Packet<?>> released) {}
    private record QueueResult(boolean consumed, List<Packet<?>> released) {
        static QueueResult notConsumed() {
            return new QueueResult(false, List.of());
        }
    }
}
