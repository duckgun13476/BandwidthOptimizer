package com.PinkCats.bandwidthoptimizer.chunk.persistent;

import com.PinkCats.bandwidthoptimizer.channel.ChannelIdentity;
import com.PinkCats.bandwidthoptimizer.chunk.classify.packet.ChunkPacketDescriptor;
import com.PinkCats.bandwidthoptimizer.debug.DiagnosticLog;
import com.PinkCats.bandwidthoptimizer.debug.DiagnosticToolRegistry;
import io.netty.channel.Channel;
import io.netty.channel.ChannelHandlerContext;
import io.netty.util.AttributeKey;
import net.minecraft.network.protocol.Packet;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeUnit;

public final class ChunkPersistentManifestGate {

    public static final String WAIT_REASON = "await_persistent_client_cache_manifest";
    private static final AttributeKey<ManifestGateState<Packet<?>>> GATE_STATE_KEY =
            AttributeKey.valueOf("bandwidthoptimizer:persistent_manifest_gate");
    private static final String ENABLED_PROPERTY = "bandwidthoptimizer.chunk.persistentManifestGateEnabled";
    private static final String TIMEOUT_MILLIS_PROPERTY = "bandwidthoptimizer.chunk.persistentManifestGateTimeoutMillis";
    private static final String MAX_QUEUED_BYTES_PROPERTY = "bandwidthoptimizer.chunk.persistentManifestGateMaxQueuedBytes";
    private static final boolean DEFAULT_ENABLED = false;
    private static final long DEFAULT_TIMEOUT_MILLIS = 1_000L;
    private static final int MAX_QUEUED_CHUNK_PACKETS = 2048;
    private static final long DEFAULT_MAX_QUEUED_BYTES = 64L * 1024L * 1024L;

    private ChunkPersistentManifestGate() {}

    public static void arm(Channel channel, String reason) {
        if (!isEnabled() || channel == null || !channel.isOpen()) {
            return;
        }
        ManifestGateState<Packet<?>> state = getOrCreateState(channel);
        long timeoutMillis = timeoutMillis();
        long generation = state.arm(System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(timeoutMillis));
        channel.eventLoop().schedule(
                () -> releaseExpired(channel, generation, "persistent_manifest_gate_timeout"),
                timeoutMillis,
                TimeUnit.MILLISECONDS
        );
        if (BO_Diag_cacheManifestGate()) {
            DiagnosticLog.info(DiagnosticToolRegistry.Tool.CACHE_MANIFEST_GATE,
                    "event=arm channel={}, generation={}, timeoutMillis={}, reason={}",
                    ChannelIdentity.longText(channel), generation, timeoutMillis, safeText(reason, "server_login"));
        }
    }

    public static long currentGeneration(Channel channel) {
        if (!isEnabled() || channel == null) {
            return 0L;
        }
        ManifestGateState<Packet<?>> state = channel.attr(GATE_STATE_KEY).get();
        return state == null ? 0L : state.generation();
    }

    public static boolean acceptsGeneration(Channel channel, long generation) {
        if (!isEnabled()) {
            return true;
        }
        ManifestGateState<Packet<?>> state = channel == null ? null : channel.attr(GATE_STATE_KEY).get();
        return generation > 0L && state != null && state.generation() == generation;
    }

    public static boolean shouldWaitForManifest(ChannelHandlerContext context, ChunkPacketDescriptor descriptor) {
        if (!isEnabled() || context == null || context.channel() == null || descriptor == null
                || descriptor.coordinate() == null || !descriptor.coordinate().present()) {
            return false;
        }
        ManifestGateState<Packet<?>> state = context.channel().attr(GATE_STATE_KEY).get();
        if (state == null) {
            return false;
        }
        long generation = state.generation();
        if (state.expired(System.nanoTime(), generation)) {
            releaseExpired(context.channel(), generation, "persistent_manifest_gate_expired_before_chunk");
            return false;
        }
        return state.pending();
    }

    public static boolean tryQueueWaitingPacket(
            ChannelHandlerContext context,
            Packet<?> packet,
            int encodedBytes,
            String traceReason
    ) {
        if (!isEnabled() || !WAIT_REASON.equals(traceReason) || context == null || context.channel() == null || packet == null) {
            return false;
        }
        Channel channel = context.channel();
        ManifestGateState<Packet<?>> state = channel.attr(GATE_STATE_KEY).get();
        if (state == null) {
            return false;
        }
        long generation = state.generation();
        if (state.expired(System.nanoTime(), generation)) {
            releaseExpired(channel, generation, "persistent_manifest_gate_expired_before_queue");
            return false;
        }
        QueueResult<Packet<?>> result = state.queue(packet, Math.max(encodedBytes, 0), MAX_QUEUED_CHUNK_PACKETS, maxQueuedBytes());
        if (!result.consumed()) {
            return false;
        }
        if (!result.releasedItems().isEmpty()) {
            flush(channel, result.releasedItems(), "persistent_manifest_gate_budget_release");
        }
        if (BO_Diag_cacheManifestGate()) {
            DiagnosticLog.info(DiagnosticToolRegistry.Tool.CACHE_MANIFEST_GATE,
                    "event=queue channel={}, generation={}, queued={}, queuedBytes={}, budgetRelease={}, packetClass={}",
                    ChannelIdentity.longText(channel), generation, state.queuedCount(), state.queuedBytes(),
                    !result.releasedItems().isEmpty(), packet.getClass().getName());
        }
        return true;
    }

    public static void complete(Channel channel, long generation, String reason) {
        if (isEnabled() && channel != null) {
            release(channel, generation, safeText(reason, "persistent_manifest_complete"));
        }
    }

    private static void releaseExpired(Channel channel, long generation, String reason) {
        ManifestGateState<Packet<?>> state = channel == null ? null : channel.attr(GATE_STATE_KEY).get();
        if (state != null && state.expired(System.nanoTime(), generation)) {
            release(channel, generation, reason);
        }
    }

    private static void release(Channel channel, long generation, String reason) {
        ManifestGateState<Packet<?>> state = channel.attr(GATE_STATE_KEY).get();
        if (state == null) {
            return;
        }
        ReleaseResult<Packet<?>> result = state.release(generation);
        if (result.released()) {
            flush(channel, result.items(), reason);
        }
    }

    private static void flush(Channel channel, List<Packet<?>> packets, String reason) {
        if (packets.isEmpty()) {
            return;
        }
        Runnable task = () -> {
            if (!channel.isOpen()) {
                return;
            }
            for (Packet<?> packet : packets) {
                channel.write(packet);
            }
            channel.flush();
            if (BO_Diag_cacheManifestGate()) {
                DiagnosticLog.info(DiagnosticToolRegistry.Tool.CACHE_MANIFEST_GATE,
                        "event=flush channel={}, packets={}, reason={}",
                        ChannelIdentity.longText(channel), packets.size(), safeText(reason, "manifest_gate_release"));
            }
        };
        if (channel.eventLoop().inEventLoop()) {
            task.run();
        } else {
            channel.eventLoop().execute(task);
        }
    }

    private static ManifestGateState<Packet<?>> getOrCreateState(Channel channel) {
        ManifestGateState<Packet<?>> existing = channel.attr(GATE_STATE_KEY).get();
        if (existing != null) {
            return existing;
        }
        ManifestGateState<Packet<?>> created = new ManifestGateState<>();
        ManifestGateState<Packet<?>> raced = channel.attr(GATE_STATE_KEY).setIfAbsent(created);
        ManifestGateState<Packet<?>> selected = raced == null ? created : raced;
        if (raced == null) {
            channel.closeFuture().addListener(ignored -> selected.close());
        }
        return selected;
    }

    private static long timeoutMillis() {
        try {
            return Math.max(50L, Math.min(Long.parseLong(System.getProperty(
                    TIMEOUT_MILLIS_PROPERTY, Long.toString(DEFAULT_TIMEOUT_MILLIS))), 5_000L));
        } catch (NumberFormatException ignored) {
            return DEFAULT_TIMEOUT_MILLIS;
        }
    }

    private static long maxQueuedBytes() {
        try {
            return Math.max(1024L * 1024L, Math.min(Long.parseLong(System.getProperty(
                    MAX_QUEUED_BYTES_PROPERTY, Long.toString(DEFAULT_MAX_QUEUED_BYTES))), 256L * 1024L * 1024L));
        } catch (NumberFormatException ignored) {
            return DEFAULT_MAX_QUEUED_BYTES;
        }
    }

    private static boolean isEnabled() {
        return Boolean.parseBoolean(System.getProperty(ENABLED_PROPERTY, Boolean.toString(DEFAULT_ENABLED)));
    }

    private static boolean BO_Diag_cacheManifestGate() {
        return DiagnosticToolRegistry.isEnabled(DiagnosticToolRegistry.Tool.CACHE_MANIFEST_GATE);
    }

    private static String safeText(String text, String fallback) {
        return text == null || text.isBlank() ? fallback : text;
    }

    static final class ManifestGateState<T> {
        private final ArrayDeque<QueuedItem<T>> queuedItems = new ArrayDeque<>();
        private boolean pending;
        private long generation;
        private long deadlineNanos;
        private long queuedBytes;

        synchronized long arm(long deadlineNanos) {
            this.generation = this.generation == Long.MAX_VALUE ? 1L : this.generation + 1L;
            this.pending = true;
            this.deadlineNanos = deadlineNanos;
            return this.generation;
        }

        synchronized long generation() { return this.generation; }
        synchronized boolean pending() { return this.pending; }
        synchronized int queuedCount() { return this.queuedItems.size(); }
        synchronized long queuedBytes() { return this.queuedBytes; }

        synchronized boolean expired(long nowNanos, long expectedGeneration) {
            return this.pending && this.generation == expectedGeneration && nowNanos >= this.deadlineNanos;
        }

        synchronized QueueResult<T> queue(T item, int encodedBytes, int maxItems, long maxBytes) {
            if (!this.pending || item == null) {
                return QueueResult.notConsumed();
            }
            long safeBytes = Math.max(encodedBytes, 0);
            boolean exceedsBudget = this.queuedItems.size() >= Math.max(maxItems, 1)
                    || safeAdd(this.queuedBytes, safeBytes) > Math.max(maxBytes, 1L);
            if (exceedsBudget) {
                ArrayList<T> released = copyItems();
                released.add(item);
                resetPending();
                return QueueResult.released(released);
            }
            this.queuedItems.addLast(new QueuedItem<>(item, safeBytes));
            this.queuedBytes = safeAdd(this.queuedBytes, safeBytes);
            return QueueResult.queued();
        }

        synchronized ReleaseResult<T> release(long expectedGeneration) {
            if (!this.pending || this.generation != expectedGeneration) {
                return ReleaseResult.ignored();
            }
            ArrayList<T> released = copyItems();
            resetPending();
            return ReleaseResult.released(released);
        }

        synchronized void close() {
            this.queuedItems.clear();
            this.queuedBytes = 0L;
            this.pending = false;
            this.deadlineNanos = 0L;
        }

        private ArrayList<T> copyItems() {
            ArrayList<T> items = new ArrayList<>(this.queuedItems.size());
            for (QueuedItem<T> queuedItem : this.queuedItems) {
                items.add(queuedItem.item());
            }
            return items;
        }

        private void resetPending() {
            this.queuedItems.clear();
            this.queuedBytes = 0L;
            this.pending = false;
            this.deadlineNanos = 0L;
        }

        private static long safeAdd(long left, long right) {
            return right > 0L && left > Long.MAX_VALUE - right ? Long.MAX_VALUE : left + right;
        }
    }

    record QueuedItem<T>(T item, long encodedBytes) {}

    record QueueResult<T>(boolean consumed, List<T> releasedItems) {
        static <T> QueueResult<T> notConsumed() { return new QueueResult<>(false, List.of()); }
        static <T> QueueResult<T> queued() { return new QueueResult<>(true, List.of()); }
        static <T> QueueResult<T> released(List<T> items) { return new QueueResult<>(true, List.copyOf(items)); }
    }

    record ReleaseResult<T>(boolean released, List<T> items) {
        static <T> ReleaseResult<T> ignored() { return new ReleaseResult<>(false, List.of()); }
        static <T> ReleaseResult<T> released(List<T> items) { return new ReleaseResult<>(true, List.copyOf(items)); }
    }
}
