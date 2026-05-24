package com.PinkCats.bandwidthoptimizer.chunk.persistent;

import com.PinkCats.bandwidthoptimizer.Bandwidthoptimizer;
import com.PinkCats.bandwidthoptimizer.chunk.classify.packet.ChunkPacketDescriptor;
import com.PinkCats.bandwidthoptimizer.debug.DebugRuntimeConfig;
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
    private static final AttributeKey<ManifestGateState> GATE_STATE_KEY =
            AttributeKey.valueOf("bandwidthoptimizer:persistent_manifest_gate");
    private static final String TIMEOUT_MILLIS_PROPERTY =
            "bandwidthoptimizer.chunk.persistentManifestGateTimeoutMillis";
    private static final long DEFAULT_TIMEOUT_MILLIS = 1_000L;
    private static final int MAX_QUEUED_CHUNK_PACKETS = 2048;

    private ChunkPersistentManifestGate() {}

    public static void arm(Channel channel, String reason) {
        if (channel == null || !channel.isOpen()) {
            return;
        }

        ManifestGateState gateState = getOrCreateState(channel);
        long timeoutMillis = timeoutMillis();
        gateState.arm(System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(timeoutMillis));
        channel.eventLoop().schedule(
                () -> releaseExpired(channel, "persistent_manifest_gate_timeout"),
                timeoutMillis,
                TimeUnit.MILLISECONDS
        );
        if (DebugRuntimeConfig.isDiagnoseEnabled()) {
            Bandwidthoptimizer.LOGGER.info(
                    "[ChunkPersistentCache][ManifestGate][Arm] channel={}, timeoutMillis={}, reason={}",
                    com.PinkCats.bandwidthoptimizer.channel.ChannelIdentity.longText(channel),
                    timeoutMillis,
                    safeText(reason, "server_login")
            );
        }
    }

    public static boolean shouldWaitForManifest(ChannelHandlerContext context, ChunkPacketDescriptor descriptor) {
        if (context == null
                || context.channel() == null
                || descriptor == null
                || descriptor.coordinate() == null
                || !descriptor.coordinate().present()) {
            return false;
        }

        ManifestGateState gateState = context.channel().attr(GATE_STATE_KEY).get();
        if (gateState == null) {
            return false;
        }
        if (gateState.expired(System.nanoTime())) {
            releaseExpired(context.channel(), "persistent_manifest_gate_expired_before_chunk");
            return false;
        }
        return gateState.pending();
    }

    public static boolean tryQueueWaitingPacket(
            ChannelHandlerContext context,
            Packet<?> packet,
            String traceReason
    ) {
        if (!WAIT_REASON.equals(traceReason)
                || context == null
                || context.channel() == null
                || packet == null) {
            return false;
        }

        Channel channel = context.channel();
        ManifestGateState gateState = channel.attr(GATE_STATE_KEY).get();
        if (gateState == null) {
            return false;
        }
        if (gateState.expired(System.nanoTime())) {
            releaseExpired(channel, "persistent_manifest_gate_expired_before_queue");
            return false;
        }

        boolean queued = gateState.queue(packet);
        if (queued && DebugRuntimeConfig.isDiagnoseEnabled()) {
            Bandwidthoptimizer.LOGGER.info(
                    "[ChunkPersistentCache][ManifestGate][Queue] channel={}, queued={}, packetClass={}",
                    com.PinkCats.bandwidthoptimizer.channel.ChannelIdentity.longText(channel),
                    gateState.queuedCount(),
                    packet.getClass().getName()
            );
        }
        return queued;
    }

    public static void complete(Channel channel, String reason) {
        if (channel == null) {
            return;
        }
        release(channel, reason == null || reason.isBlank() ? "persistent_manifest_complete" : reason);
    }

    private static void releaseExpired(Channel channel, String reason) {
        if (channel == null) {
            return;
        }
        ManifestGateState gateState = channel.attr(GATE_STATE_KEY).get();
        if (gateState == null || !gateState.expired(System.nanoTime())) {
            return;
        }
        release(channel, reason);
    }

    private static void release(Channel channel, String reason) {
        ManifestGateState gateState = channel.attr(GATE_STATE_KEY).get();
        if (gateState == null) {
            return;
        }
        List<Packet<?>> queuedPackets = gateState.release();
        if (queuedPackets.isEmpty()) {
            return;
        }

        Runnable flushTask = () -> {
            for (Packet<?> queuedPacket : queuedPackets) {
                channel.write(queuedPacket);
            }
            channel.flush();
            if (DebugRuntimeConfig.isDiagnoseEnabled()) {
                Bandwidthoptimizer.LOGGER.info(
                        "[ChunkPersistentCache][ManifestGate][Flush] channel={}, packets={}, reason={}",
                        com.PinkCats.bandwidthoptimizer.channel.ChannelIdentity.longText(channel),
                        queuedPackets.size(),
                        safeText(reason, "manifest_gate_release")
                );
            }
        };
        if (channel.eventLoop().inEventLoop()) {
            flushTask.run();
        } else {
            channel.eventLoop().execute(flushTask);
        }
    }

    private static ManifestGateState getOrCreateState(Channel channel) {
        ManifestGateState existingState = channel.attr(GATE_STATE_KEY).get();
        if (existingState != null) {
            return existingState;
        }
        ManifestGateState newState = new ManifestGateState();
        ManifestGateState racedState = channel.attr(GATE_STATE_KEY).setIfAbsent(newState);
        return racedState == null ? newState : racedState;
    }

    private static long timeoutMillis() {
        String configuredValue = System.getProperty(TIMEOUT_MILLIS_PROPERTY, Long.toString(DEFAULT_TIMEOUT_MILLIS));
        try {
            return Math.max(50L, Math.min(Long.parseLong(configuredValue), 5_000L));
        } catch (NumberFormatException ignored) {
            return DEFAULT_TIMEOUT_MILLIS;
        }
    }

    private static String safeText(String text, String fallback) {
        return text == null || text.isBlank() ? fallback : text;
    }

    private static final class ManifestGateState {

        private final ArrayDeque<Packet<?>> queuedPackets = new ArrayDeque<>();
        private boolean pending;
        private long deadlineNanos;

        private synchronized void arm(long deadlineNanos) {
            this.queuedPackets.clear();
            this.pending = true;
            this.deadlineNanos = Math.max(deadlineNanos, System.nanoTime());
        }

        private synchronized boolean pending() {
            return this.pending;
        }

        private synchronized boolean expired(long nowNanos) {
            return this.pending && nowNanos >= this.deadlineNanos;
        }

        private synchronized boolean queue(Packet<?> packet) {
            if (!this.pending || packet == null || this.queuedPackets.size() >= MAX_QUEUED_CHUNK_PACKETS) {
                return false;
            }
            this.queuedPackets.add(packet);
            return true;
        }

        private synchronized int queuedCount() {
            return this.queuedPackets.size();
        }

        private synchronized List<Packet<?>> release() {
            this.pending = false;
            this.deadlineNanos = 0L;
            if (this.queuedPackets.isEmpty()) {
                return List.of();
            }
            ArrayList<Packet<?>> releasedPackets = new ArrayList<>(this.queuedPackets);
            this.queuedPackets.clear();
            return releasedPackets;
        }
    }
}
