package com.PinkCats.bandwidthoptimizer.channel;

import io.netty.buffer.ByteBuf;
import io.netty.buffer.ByteBufUtil;
import io.netty.buffer.Unpooled;
import io.netty.channel.Channel;
import io.netty.channel.ChannelHandlerContext;
import io.netty.util.AttributeKey;

import java.util.ArrayDeque;
import java.util.Arrays;
import java.util.Deque;
import java.util.function.Consumer;

public final class ChannelTransportStreamingEpochGate {

    private static final int MAX_PENDING_BYTES = 16 * 1024 * 1024;
    private static final int MAX_PENDING_PACKETS = 16384;
    private static final AttributeKey<State> STATE_KEY =
            AttributeKey.valueOf("bandwidthoptimizer:streaming_epoch_gate");
    private ChannelTransportStreamingEpochGate() {
    }

    public static boolean deferIfClosed(
            ChannelHandlerContext context,
            boolean bypassClosedGate,
            ByteBuf out,
            int startIndexInclusive
    ) {
        return deferIfClosed(context, bypassClosedGate, out, startIndexInclusive, null);
    }

    public static void closeForEpoch(Channel channel, int epoch, int lastSequence) {
        if (channel == null || epoch <= 0 || lastSequence <= 0) {
            throw new IllegalArgumentException("Invalid streaming epoch boundary");
        }
        state(channel).close(epoch, lastSequence);
    }

    public static boolean deferIfClosed(
            ChannelHandlerContext context,
            boolean bypassClosedGate,
            ByteBuf out,
            int startIndexInclusive,
            Consumer<byte[]> onDeferred
    ) {
        if (context == null || out == null) {
            return false;
        }
        State state = context.channel().attr(STATE_KEY).get();
        if (state == null || bypassClosedGate) {
            return false;
        }
        int length = out.writerIndex() - startIndexInclusive;
        if (length <= 0) {
            return false;
        }
        byte[] encodedBytes = ByteBufUtil.getBytes(out, startIndexInclusive, length, false);
        if (!state.deferWriteIfClosed(context, encodedBytes)) {
            return false;
        }
        out.writerIndex(startIndexInclusive);
        if (onDeferred != null) {
            onDeferred.accept(encodedBytes);
        }
        return true;
    }

    public static boolean deferTaskIfClosed(Channel channel, int estimatedBytes, Runnable task) {
        if (channel == null || task == null) {
            return false;
        }
        State state = channel.attr(STATE_KEY).get();
        return state != null && state.deferTaskIfClosed(Math.max(estimatedBytes, 0), task);
    }

    public static boolean matchesClosedEpoch(Channel channel, int epoch, int lastSequence) {
        State state = channel == null ? null : channel.attr(STATE_KEY).get();
        return state != null && state.matches(epoch, lastSequence);
    }

    public static void release(Channel channel) {
        State state = channel == null ? null : channel.attr(STATE_KEY).get();
        if (state != null) {
            state.release();
        }
    }

    public static void clear(Channel channel) {
        if (channel != null) {
            State state = channel.attr(STATE_KEY).getAndSet(null);
            if (state != null) {
                state.clear();
            }
        }
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

    private static final class State {
        private final Deque<DeferredOperation> pending = new ArrayDeque<>();
        private int pendingBytes;
        private int epoch;
        private int lastSequence;
        private boolean closed;
        private boolean draining;

        private synchronized void close(int epoch, int lastSequence) {
            if (this.closed && (this.epoch != epoch || this.lastSequence != lastSequence)) {
                throw new IllegalStateException("A different streaming epoch is already closed");
            }
            this.epoch = epoch;
            this.lastSequence = lastSequence;
            this.closed = true;
        }

        private synchronized boolean matches(int epoch, int lastSequence) {
            return this.closed && this.epoch == epoch && this.lastSequence == lastSequence;
        }

        private synchronized boolean deferWriteIfClosed(ChannelHandlerContext context, byte[] encodedBytes) {
            if (!this.closed) {
                return false;
            }
            byte[] copiedBytes = Arrays.copyOf(encodedBytes, encodedBytes.length);
            enqueue(new DeferredOperation(context, copiedBytes, null, copiedBytes.length));
            return true;
        }

        private synchronized boolean deferTaskIfClosed(int estimatedBytes, Runnable task) {
            if (!this.closed) {
                return false;
            }
            enqueue(new DeferredOperation(null, null, task, estimatedBytes));
            return true;
        }

        private void enqueue(DeferredOperation operation) {
            if (this.pending.size() >= MAX_PENDING_PACKETS
                    || this.pendingBytes + operation.estimatedBytes() > MAX_PENDING_BYTES) {
                throw new IllegalStateException(
                        "Streaming epoch pending output exceeded its bounded queue: packets="
                                + this.pending.size()
                                + "/"
                                + MAX_PENDING_PACKETS
                                + ", bytes="
                                + this.pendingBytes
                                + "/"
                                + MAX_PENDING_BYTES
                );
            }
            this.pending.addLast(operation);
            this.pendingBytes += operation.estimatedBytes();
        }

        private void release() {
            ChannelHandlerContext flushContext = null;
            synchronized (this) {
                this.closed = false;
                this.epoch = 0;
                this.lastSequence = 0;
                this.draining = true;
            }
            while (true) {
                DeferredOperation operation;
                synchronized (this) {
                    operation = this.pending.pollFirst();
                    if (operation == null) {
                        this.pendingBytes = 0;
                        this.draining = false;
                        break;
                    }
                    this.pendingBytes -= operation.estimatedBytes();
                }
                if (operation.task() != null) {
                    operation.task().run();
                } else {
                    flushContext = operation.context();
                    operation.context().write(Unpooled.wrappedBuffer(operation.encodedBytes()));
                }
                synchronized (this) {
                    if (this.closed) {
                        this.draining = false;
                        break;
                    }
                }
            }
            if (flushContext != null) {
                flushContext.flush();
            }
        }

        private synchronized void clear() {
            this.pending.clear();
            this.pendingBytes = 0;
            this.epoch = 0;
            this.lastSequence = 0;
            this.closed = false;
            this.draining = false;
        }
    }

    private record DeferredOperation(
            ChannelHandlerContext context,
            byte[] encodedBytes,
            Runnable task,
            int estimatedBytes
    ) {
    }
}
