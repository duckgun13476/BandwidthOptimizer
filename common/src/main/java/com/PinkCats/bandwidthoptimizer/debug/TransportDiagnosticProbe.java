package com.PinkCats.bandwidthoptimizer.debug;

import com.PinkCats.bandwidthoptimizer.channel.ChannelIdentity;
import io.netty.channel.Channel;
import io.netty.channel.ChannelHandlerContext;
import io.netty.util.concurrent.SingleThreadEventExecutor;
import net.minecraft.network.protocol.Packet;
import net.minecraft.network.protocol.PacketFlow;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.LongAdder;

public final class TransportDiagnosticProbe {
    private static final long ENCODE_LOG_THRESHOLD_NANOS = TimeUnit.MILLISECONDS.toNanos(25L);
    private static final long HOOK_LOG_THRESHOLD_NANOS = TimeUnit.MILLISECONDS.toNanos(10L);
    private static final long FLUSH_DELAY_MILLIS = 1000L;
    private static final int MAX_FLUSH_ENTRIES = 8;
    private static final ConcurrentHashMap<Key, Summary> SUMMARIES = new ConcurrentHashMap<>();
    private static final AtomicBoolean FLUSH_SCHEDULED = new AtomicBoolean();
    private static final ScheduledExecutorService FLUSH_EXECUTOR =
            Executors.newSingleThreadScheduledExecutor(new DaemonThreadFactory());

    private TransportDiagnosticProbe() {}

    public static void BO_Diag_transportEncodeCost(
            ChannelHandlerContext context,
            PacketFlow flow,
            Packet<?> packet,
            int encodedBytes,
            long vanillaEncodeNanos,
            long boHookNanos
    ) {
        observeOutboundEncodeCost(context, flow, packet, encodedBytes, vanillaEncodeNanos, boHookNanos);
    }

    private static void observeOutboundEncodeCost(
            ChannelHandlerContext context,
            PacketFlow flow,
            Packet<?> packet,
            int encodedBytes,
            long vanillaEncodeNanos,
            long boHookNanos
    ) {
        if (!isEnabled()
                || context == null
                || packet == null
                || (vanillaEncodeNanos < ENCODE_LOG_THRESHOLD_NANOS && boHookNanos < HOOK_LOG_THRESHOLD_NANOS)) {
            return;
        }
        Channel channel = context.channel();
        String channelId = ChannelIdentity.longText(channel);
        String packetClass = packet.getClass().getName();
        Summary summary = SUMMARIES.computeIfAbsent(
                new Key(channelId, flow, packetClass),
                ignored -> new Summary()
        );
        summary.record(
                Math.max(encodedBytes, 0),
                vanillaEncodeNanos,
                boHookNanos,
                pendingTasks(channel),
                channel == null || channel.isWritable()
        );
        scheduleFlush();
    }

    private static void scheduleFlush() {
        if (!FLUSH_SCHEDULED.compareAndSet(false, true)) {
            return;
        }
        FLUSH_EXECUTOR.schedule(TransportDiagnosticProbe::flushSummaries, FLUSH_DELAY_MILLIS, TimeUnit.MILLISECONDS);
    }

    private static void flushSummaries() {
        FLUSH_SCHEDULED.set(false);
        if (!isEnabled()) {
            SUMMARIES.clear();
            return;
        }
        List<EntrySnapshot> snapshots = new ArrayList<>();
        for (var iterator = SUMMARIES.entrySet().iterator(); iterator.hasNext(); ) {
            var entry = iterator.next();
            iterator.remove();
            EntrySnapshot snapshot = entry.getValue().snapshot(entry.getKey());
            if (snapshot.count() > 0L) {
                snapshots.add(snapshot);
            }
        }
        snapshots.sort(Comparator.comparingLong(EntrySnapshot::maxHookNanos).reversed());
        int limit = Math.min(snapshots.size(), MAX_FLUSH_ENTRIES);
        for (int i = 0; i < limit; i++) {
            EntrySnapshot snapshot = snapshots.get(i);
            DiagnosticLog.warn(
                    DiagnosticToolRegistry.Tool.TRANSPORT_ENCODE_COST,
                    "window=1s, channel={}, flow={}, packetClass={}, samples={}, bytes={}, vanillaMaxMs={}, hookMaxMs={}, hookAvgMs={}, pendingTasksMax={}, writableLast={}",
                    snapshot.key().channelId(),
                    snapshot.key().flow(),
                    snapshot.key().packetClass(),
                    snapshot.count(),
                    snapshot.bytes(),
                    millis(snapshot.maxVanillaNanos()),
                    millis(snapshot.maxHookNanos()),
                    millis(snapshot.hookNanos() / Math.max(snapshot.count(), 1L)),
                    snapshot.maxPendingTasks(),
                    snapshot.writableLast()
            );
        }
        if (!SUMMARIES.isEmpty()) {
            scheduleFlush();
        }
    }

    private static boolean isEnabled() {
        return DiagnosticToolRegistry.isEnabled(DiagnosticToolRegistry.Tool.TRANSPORT_ENCODE_COST)
                || DiagnosticRuntimeSwitch.isEnabled(DiagnosticRuntimeSwitch.Topic.TRANSPORT);
    }

    private static double millis(long nanos) {
        return Math.round((Math.max(nanos, 0L) / 1_000_000.0D) * 100.0D) / 100.0D;
    }

    private static int pendingTasks(Channel channel) {
        if (channel == null || !(channel.eventLoop() instanceof SingleThreadEventExecutor executor)) {
            return -1;
        }
        return executor.pendingTasks();
    }

    private record Key(String channelId, PacketFlow flow, String packetClass) {}

    private record EntrySnapshot(
            Key key,
            long count,
            long bytes,
            long hookNanos,
            long maxVanillaNanos,
            long maxHookNanos,
            int maxPendingTasks,
            boolean writableLast
    ) {}

    private static final class Summary {
        private final LongAdder count = new LongAdder();
        private final LongAdder bytes = new LongAdder();
        private final LongAdder hookNanos = new LongAdder();
        private final AtomicLong maxVanillaNanos = new AtomicLong();
        private final AtomicLong maxHookNanos = new AtomicLong();
        private final AtomicLong maxPendingTasks = new AtomicLong(-1L);
        private volatile boolean writableLast = true;

        private void record(int encodedBytes, long vanillaEncodeNanos, long boHookNanos, int pendingTasks, boolean writable) {
            count.increment();
            bytes.add(encodedBytes);
            hookNanos.add(Math.max(boHookNanos, 0L));
            updateMax(maxVanillaNanos, Math.max(vanillaEncodeNanos, 0L));
            updateMax(maxHookNanos, Math.max(boHookNanos, 0L));
            updateMax(maxPendingTasks, pendingTasks);
            writableLast = writable;
        }

        private EntrySnapshot snapshot(Key key) {
            return new EntrySnapshot(
                    key,
                    count.sum(),
                    bytes.sum(),
                    hookNanos.sum(),
                    maxVanillaNanos.get(),
                    maxHookNanos.get(),
                    Math.toIntExact(Math.min(maxPendingTasks.get(), Integer.MAX_VALUE)),
                    writableLast
            );
        }

        private static void updateMax(AtomicLong target, long value) {
            long previous;
            do {
                previous = target.get();
                if (value <= previous) {
                    return;
                }
            } while (!target.compareAndSet(previous, value));
        }
    }

    private static final class DaemonThreadFactory implements ThreadFactory {
        @Override
        public Thread newThread(Runnable runnable) {
            Thread thread = new Thread(runnable, "bo-transport-diagnostic-flush");
            thread.setDaemon(true);
            return thread;
        }
    }
}
